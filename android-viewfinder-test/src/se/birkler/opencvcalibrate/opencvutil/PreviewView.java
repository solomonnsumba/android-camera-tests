package se.birkler.opencvcalibrate.opencvutil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import se.birkler.opencvcalibrate.util.XLog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

/**
 * A simple wrapper around a Camera and a SurfaceView that renders a centered preview of the Camera
 * to the surface. We need to center the SurfaceView because not all devices have cameras that
 * support preview sizes at the same aspect ratio as the device's display.
 * 
 * Startup states:
 * onLayout (with vga size)
 * 
 * 
 */
public class PreviewView extends RelativeLayout implements Runnable, Camera.PictureCallback, Camera.ShutterCallback, SurfaceTextureListener
{
	private static final int CAMERA_INIT_DELAY = 500;
	private Rect mSurfaceViewVisibleRect = new Rect();

	public interface OpenCVProcessor {
		void processFrame(Canvas canvas, int width, int height, Mat yuvData, Mat grayData);
	}
	
	public interface PictureCallback {
		public void onShutter();
		public void onPictureTaken(byte[] data);
	}

	static final int PREFERRED_HEIGHT = 480;
	static final int PREFERRED_WIDTH = 640;
	private static final int INIT_MESSAGE_ID = 0;
    TextureView mSurfaceView;
    Size mPreviewSize;
    List<Size> mSupportedPreviewSizes;
    Camera mCamera;
	private OpenCVProcessor mProcessor;
	private float mFieldOfView = -1;
	private FrameLayout mResultSurfaceViewOwner;
    private TextureView mResultSurfaceView;
    private ArrayBlockingQueue<PreviewBuffer> mPreviewFrames = new ArrayBlockingQueue<PreviewBuffer>(3);
	private boolean mThreadRun;
	private int frameCount;
	private long prevTime;
	private Thread mThread;
	
	class PreviewBuffer {
		public ByteBuffer mPreviewCallbackBuffer;
		public Mat mYuv;
		public Mat mGraySubmat;
		
		public PreviewBuffer(int width, int height, int format) {
			int bytesneeded = height * width * ImageFormat.getBitsPerPixel(format) / 8; 
			mPreviewCallbackBuffer = ByteBuffer.allocateDirect(bytesneeded);
			mYuv = new MatByteBufferWrapper(mPreviewCallbackBuffer,height + height / 2, width, CvType.CV_8UC1);
			mGraySubmat = mYuv.submat(0, height, 0, width);
		}
	}
	PreviewBuffer mPreviewBuffer1;
	Handler mInitHandler;
	
	PictureCallback mPictureCallback = null;
	private AudioManager mAudioManager;
	private Paint mPreviewFramePaint;
	private PreviewBuffer mPreviewBuffer2;
	
    public PreviewView(Context context, AttributeSet attr) {
        super(context,attr);

        mSurfaceView = new TextureView(context);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
        lp.addRule(RelativeLayout.CENTER_IN_PARENT);
        mSurfaceView.setLayoutParams(lp);
        mSurfaceView.setOpaque(true);
        mSurfaceView.setSurfaceTextureListener(this);

        addView(mSurfaceView);
        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        //mCameraHolder = mSurfaceView.getHolder();
        //mCameraHolder.addCallback(this);
        //mCameraHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        mResultSurfaceView = new TextureView(context);
        mResultSurfaceView.setOpaque(false);
        lp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
        lp.addRule(RelativeLayout.CENTER_IN_PARENT);
        mResultSurfaceView.setLayoutParams(lp);
        mResultSurfaceView.setSurfaceTextureListener(this);
        
        mResultSurfaceViewOwner = new FrameLayout(context);
        lp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
        lp.addRule(RelativeLayout.CENTER_IN_PARENT);
        mResultSurfaceViewOwner.setLayoutParams(lp);
        mResultSurfaceViewOwner.addView(mResultSurfaceView);
        addView(mResultSurfaceViewOwner);
        
        
        //mResultSurfaceView.setZOrderOnTop(true);
        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        //mResultHolder = mResultSurfaceView.getHolder();
        //mResultHolder.setFormat(PixelFormat.TRANSPARENT);
        //mResultHolder.addCallback(this);
        
        setWillNotDraw(false);
        mInitHandler = new Handler() {
        	@Override
        	public void handleMessage(Message msg) {
        		initCameraAndStartPreview();
        	}
        };

	    mThread = new Thread(this);
	    mThread.setPriority(Thread.MIN_PRIORITY+1);
	    mThread.setName("Processing thread");
	    
		//mMediaPlayer = MediaPlayer.create(context,R.raw.shutter_sound);

	    //mMediaPlayer.prepare();
	    mAudioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
	    
    	mPreviewFramePaint = new Paint();
    	mPreviewFramePaint.setColor(Color.CYAN);
    	mPreviewFramePaint.setAlpha(128);
    	mPreviewFramePaint.setStrokeWidth((float) 3.0);
    }

    @Override
    public void  onFinishInflate () {
    	super.onFinishInflate();
    }
    
    
    //@Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    	int width = Integer.MAX_VALUE;
    	int height = Integer.MAX_VALUE;
    	int mostWidth = Integer.MAX_VALUE;
    	int mostHeight = Integer.MAX_VALUE;
    	final int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
    	final int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
    	boolean resizeWidth = widthSpecMode != MeasureSpec.EXACTLY;
    	boolean resizeHeight = heightSpecMode != MeasureSpec.EXACTLY;
    	
    	//if (widthSpecMode == MeasureSpec.UNSPECIFIED) width = Integer.MAX_VALUE;
    	//if (heightSpecMode == MeasureSpec.UNSPECIFIED) height = Integer.MAX_VALUE;
    	
    	if (widthSpecMode == MeasureSpec.EXACTLY && heightMeasureSpec == MeasureSpec.EXACTLY) {
    		width = MeasureSpec.getSize(widthMeasureSpec);
    		height = MeasureSpec.getSize(heightMeasureSpec);
    	} else if (widthSpecMode == MeasureSpec.UNSPECIFIED && heightMeasureSpec == MeasureSpec.UNSPECIFIED) {
    		
    	}
    	else {
    		double aspect = 640.0 / 480.0;
    		if (widthSpecMode == MeasureSpec.EXACTLY || widthSpecMode == MeasureSpec.AT_MOST) {
    			mostWidth = MeasureSpec.getSize(widthMeasureSpec);
    		}
    		if (heightSpecMode == MeasureSpec.EXACTLY || heightSpecMode == MeasureSpec.AT_MOST) {
    			mostHeight = MeasureSpec.getSize(heightMeasureSpec);
    		}
    		if ((double)mostWidth > (double)mostHeight * aspect) {
        		width =  (int) ((double)mostHeight * aspect);
        		height = mostHeight;
    		} else {
        		width =  mostWidth;
        		height = (int) ((double)mostWidth / aspect);
    		}
		}

    	/*
        // We purposely disregard child measurements because act as a
        // wrapper to a SurfaceView that centers the camera preview instead
        // of stretching it.
        if (mPreviewSize != null) {
            setMeasuredDimension(mPreviewSize.width, mPreviewSize.height);
        }
        else {
            setMeasuredDimension(PREFERRED_WIDTH,PREFERRED_HEIGHT);
        }*/
    	
    	setMeasuredDimension(width,height);
    	mResultSurfaceView.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
    	mResultSurfaceViewOwner.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
        //final int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        //final int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        //setMeasuredDimension(width, height);
        //tMeasuredDimension(mPreviewSize.width, mPreviewSize.height);

    }

	@Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
		if (changed) {
	        final int width = r - l;
	        final int height = b - t;
	        
	        int previewWidth = width;
	        int previewHeight = height;
	        if (mPreviewSize != null) {
	            previewWidth = mPreviewSize.width;
	            previewHeight = mPreviewSize.height;
	        }
	        
	        // Center the child SurfaceView within the parent.
	        if (width * previewHeight > height * previewWidth) {
	            final int scaledChildWidth = previewWidth * height / previewHeight;
	            l = (width - scaledChildWidth) / 2;
	            t = 0;
	            r = (width + scaledChildWidth) / 2;
	            b = height;
	        } else {
	            final int scaledChildHeight = previewHeight * width / previewWidth;
	            l = 0;
	            t = (height - scaledChildHeight) / 2;
	            r = width;
	            b = (height + scaledChildHeight) / 2;
	        }
	    	super.onLayout(changed,l,t,r,b);
	    	mSurfaceView.layout(l, t,r, b);
	    	mResultSurfaceViewOwner.layout(l, t,r, b);
	    	//mResultSurfaceView.layout(l, t,r, b);
	    	float scale; 
	    	int w = 640;
	    	int h = 480;
	    	if (mPreviewSize != null) {
	    		scale = (float)(b-t) / (float)mPreviewSize.height;
	    		w =  mPreviewSize.width;
	    		h = mPreviewSize.height;
	    	} else {
	    		scale = (float)(b-t) / 480;
	    	}
	    	//In the middle
    		mResultSurfaceView.layout((l+r-w) / 2, (t+b-h) / 2, (l+r+w) / 2, (t+b+h) / 2);	
	    	//Matrix transform = new Matrix();
	    	//transform.preScale(scale, scale);
	    	//mResultSurfaceView.setTransform(transform);
 	    	mResultSurfaceViewOwner.setScaleX(scale);
	    	mResultSurfaceViewOwner.setScaleY(scale);
	    	mSurfaceView.getLocalVisibleRect(mSurfaceViewVisibleRect);
	    	XLog.d("surfaceView:" + mSurfaceView.toString() + mSurfaceViewVisibleRect.toString());
		}
    }
	

	@Override
	public void onSurfaceTextureAvailable(SurfaceTexture surface, int width,	int height) {
        // The Surface has been created, acquire the camera and tell it where to draw.
    	if (surface == mSurfaceView.getSurfaceTexture()) {
            //Debug.startMethodTracing("opencvtrace_viewfinder");
    		mThreadRun = true;
		    mThread.start();
    		mInitHandler.removeMessages(INIT_MESSAGE_ID);
    		mInitHandler.sendMessageDelayed(Message.obtain(mInitHandler, INIT_MESSAGE_ID), CAMERA_INIT_DELAY);

    	} 

	}

	@Override
	public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        // Surface will be destroyed when we return, so stop the preview.
    	if (surface == mSurfaceView.getSurfaceTexture()) {
    		mInitHandler.removeMessages(INIT_MESSAGE_ID);
			//Debug.stopMethodTracing();
	        if (mCamera != null) {
			    mThread.interrupt();
			    if (mCamera != null) {
		            mCamera.stopPreview();
		            mCamera.release();
		            mCamera = null;
			    }
			    try {
					mThread.join(500);
				} catch (InterruptedException e) {
					XLog.e("Cannot interrupt processing thread",e);
				}
	        }
    	}
		return false;
	}

	@Override
	public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width,	int height) {
    	if (surface == mSurfaceView.getSurfaceTexture()) {
    		mInitHandler.removeMessages(INIT_MESSAGE_ID);
    		mInitHandler.sendMessageDelayed(Message.obtain(mInitHandler, INIT_MESSAGE_ID), CAMERA_INIT_DELAY);
    	}
	}
	

	@Override
	public void onSurfaceTextureUpdated(SurfaceTexture surface) {
	}

    
    public void setProcessor(OpenCVProcessor processor) {
    	mProcessor = processor;
    }
    
    private void initCameraAndStartPreview() {
    	if (mCamera == null && mSurfaceView.getSurfaceTexture() != null) {
    		try {
	    	    mCamera = Camera.open();
		        Camera.Parameters parameters = mCamera.getParameters();
	            ///Set preview and picture size
	            mSupportedPreviewSizes = parameters.getSupportedPreviewSizes();
	        	mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, PREFERRED_WIDTH, PREFERRED_HEIGHT);
	        	parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
	        	parameters.setPictureSize(mPreviewSize.width, mPreviewSize.height);
	        	parameters.setJpegQuality(97);
		        mFieldOfView = parameters.getHorizontalViewAngle();
		        mFieldOfView = 65.0f;
		        mFieldOfView /= mPreviewSize.width; 
	            mCamera.setParameters(parameters);

	            ///Callbacks and butffers
			    mCamera.setPreviewCallbackWithBuffer (new Camera.PreviewCallback() {
					public void onPreviewFrame(byte[] data, Camera camera) {
						if (data.equals(mPreviewBuffer1.mPreviewCallbackBuffer.array())) {
				            try {
								PreviewView.this.mPreviewFrames.put(mPreviewBuffer1);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
						else if (data.equals(mPreviewBuffer2.mPreviewCallbackBuffer.array())) {
				            try {
								PreviewView.this.mPreviewFrames.put(mPreviewBuffer2);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
			        }
			    });
				mPreviewBuffer1 = new PreviewBuffer(mPreviewSize.width,mPreviewSize.height, parameters.getPreviewFormat());
				mPreviewBuffer2 = new PreviewBuffer(mPreviewSize.width,mPreviewSize.height, parameters.getPreviewFormat());
	            mCamera.addCallbackBuffer(mPreviewBuffer1.mPreviewCallbackBuffer.array());
	            mCamera.addCallbackBuffer(mPreviewBuffer2.mPreviewCallbackBuffer.array());
	            
	            ///Start it up
	            mCamera.setPreviewTexture(mSurfaceView.getSurfaceTexture());
		        mCamera.startPreview();
	            requestLayout();
    		} catch (RuntimeException e) {
    			XLog.e("Failed to connect to camera",e);
			} catch (IOException e) {
				XLog.e("Cannot init camera",e);
			} 
    	} else {
    		mInitHandler.removeMessages(INIT_MESSAGE_ID);
    		mInitHandler.sendMessageDelayed(Message.obtain(mInitHandler, INIT_MESSAGE_ID), CAMERA_INIT_DELAY);
    	}
    }

    
    @Override
    public void onDraw(Canvas canvas) {
    	super.onDraw(canvas);
    	canvas.drawRect(0, 0,getWidth(),getHeight(), mPreviewFramePaint);
    }


    public float getFOVPerPixel() {
    	return mFieldOfView;
    }
    
    
	public void takePicture(PictureCallback piccallback) {
		if (mCamera != null) {
			if (mPictureCallback == null) {
				mAudioManager.setStreamMute(AudioManager.STREAM_SYSTEM, true);
				mPictureCallback = piccallback;
				mCamera.takePicture(this, null, this);
			}
		}
	}
	
    public void run() {
        mThreadRun = true;
        XLog.i("Starting processing thread");
        while (mThreadRun) {
        	Mat grayMat = null;
        	Mat yuvMat = null;
        	PreviewBuffer frame;
			try {
				frame = mPreviewFrames.take();
        	
	    		frameCount++;
	    		long now = System.currentTimeMillis();
	    		if (prevTime > 0) {
	    			long diff = now - prevTime;
	    			if (diff > 2000) {
	    				Log.d("OpenCVDemo:",String.format("frames/s:%.2f data.size=%d",frameCount * 1000.0f / diff,frame == null ? 0 : frame.mPreviewCallbackBuffer.capacity()));
	    				frameCount = 0;
	    				prevTime = now;
	    			}
	    		}
	    		else {
	    			prevTime = now;
	    		}
	
        		grayMat = frame.mGraySubmat;
        		yuvMat = frame.mYuv;
        		
	    		if (mProcessor != null) {
	                Canvas canvas = mResultSurfaceView.lockCanvas();
	                if (canvas != null) {
	                	mProcessor.processFrame(canvas, mPreviewSize.width,mPreviewSize.height, yuvMat,grayMat);
	                	mResultSurfaceView.unlockCanvasAndPost(canvas);
	                } 
	    		}
	            //Need to add back the callback buffer
	            byte[] framedata = frame.mPreviewCallbackBuffer.array();
	            if (mCamera != null) {
	            	mCamera.addCallbackBuffer(framedata);
	            }
			} catch (InterruptedException e) {
				mThreadRun = false;
			}
        }
    }


    static private Size getOptimalPreviewSize(List<Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) w / h;
        if (sizes == null) return null;

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;
        // Try to find vga
        for (Size size : sizes) {
            if (size.height == 480 && size.width == 640) {
            	return size;
            }
        }

        // Try to find an size match aspect ratio and size
        for (Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

	@Override
	
	
	public void onShutter() {
		mAudioManager.setStreamMute(AudioManager.STREAM_SYSTEM, false);
		if (mPictureCallback != null) {
			mPictureCallback.onShutter();
		}
		//mMediaPlayer.seekTo(0);
		//mMediaPlayer.start();
	}

	@Override
	public void onPictureTaken(byte[] data, Camera camera) {
		if (mPictureCallback != null) {
			mPictureCallback.onPictureTaken(data);
			mPictureCallback = null;
		}
		camera.startPreview();
	}

}
