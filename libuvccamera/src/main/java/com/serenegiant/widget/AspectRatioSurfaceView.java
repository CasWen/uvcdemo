package com.serenegiant.widget;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceView;

import com.serenegiant.encoder.IVideoEncoder;
import com.serenegiant.encoder.MediaEncoder;
import com.serenegiant.encoder.MediaVideoEncoder;
import com.serenegiant.opengl.EGLBase;
import com.serenegiant.opengl.GLDrawer2D;
import com.serenegiant.opengl.GLHelper;
import com.serenegiant.utils.FpsCounter;
import com.serenegiant.uvccamera.BuildConfig;

/**
 * change the view size with keeping the specified aspect ratio.
 * if you set this view with in a FrameLayout and set property "android:layout_gravity="center",
 * you can show this view in the center of screen and keep the aspect ratio of content
 * it is better that can set the aspect ratio as xml property
 *
 * @author admin
 */
public class AspectRatioSurfaceView extends SurfaceView    // API >= 14
        implements IAspectRatioView {

    private static final boolean DEBUG = BuildConfig.DEBUG;    // TODO set false on release
    private static final String TAG = AspectRatioSurfaceView.class.getSimpleName();

    private double mRequestedAspect = -1.0;
    private FpsCounter mFpsCounter = new FpsCounter();
    private RenderAgent mRenderAgent;

    public AspectRatioSurfaceView(final Context context) {
        this(context, null, 0);
    }

    public AspectRatioSurfaceView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AspectRatioSurfaceView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void setAspectRatio(final double aspectRatio) {
        if (aspectRatio < 0) {
            throw new IllegalArgumentException();
        }
        // Use a range as a standard for comparing whether floating point numbers are equal
        float diff = 1e-6f;
        if (Math.abs(mRequestedAspect - aspectRatio) > diff) {
            mRequestedAspect = aspectRatio;
            new Handler(Looper.getMainLooper()).post(() -> {
                requestLayout();
            });
        }
    }

    @Override
    public void setAspectRatio(final int width, final int height) {
        setAspectRatio(width / (double) height);
    }

    @Override
    public double getAspectRatio() {
        return mRequestedAspect;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        if (mRequestedAspect > 0) {
            int initialWidth = MeasureSpec.getSize(widthMeasureSpec);
            int initialHeight = MeasureSpec.getSize(heightMeasureSpec);

            final int horizPadding = getPaddingLeft() + getPaddingRight();
            final int vertPadding = getPaddingTop() + getPaddingBottom();
            initialWidth -= horizPadding;
            initialHeight -= vertPadding;

            final double viewAspectRatio = (double) initialWidth / initialHeight;
            final double aspectDiff = mRequestedAspect / viewAspectRatio - 1;

            if (Math.abs(aspectDiff) > 0.01) {
                if (aspectDiff > 0) {
                    // width priority decision
                    initialHeight = (int) (initialWidth / mRequestedAspect);
                } else {
                    // height priority decision
                    initialWidth = (int) (initialHeight * mRequestedAspect);
                }
                initialWidth += horizPadding;
                initialHeight += vertPadding;
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(initialWidth, MeasureSpec.EXACTLY);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(initialHeight, MeasureSpec.EXACTLY);
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }


    /**
     * render camera frames on this view on a private thread
     *
     * @author saki
     */
    private static final class RenderAgent
            implements SurfaceTexture.OnFrameAvailableListener {

        private RenderAgent.RenderHandler mThread;
        private boolean mIsActive = true;
        private FpsCounter mFpsCounter;

        public RenderAgent(final FpsCounter counter,
                           final SurfaceTexture surface, final int width, final int height) {
            mThread = new RenderAgent.RenderHandler(this, surface, width, height);
            mThread.sendEmptyMessage(MSG_INIT);
            mFpsCounter = counter;
        }

        public final void setVideoEncoder(final IVideoEncoder encoder) {
            if (DEBUG) Log.v(TAG, "setVideoEncoder:");
            if (mIsActive) {
                mThread.sendMessage(mThread.obtainMessage(MSG_SET_ENCODER, encoder));
            }
        }

        public final SurfaceTexture getPreviewTexture() {
            if (DEBUG) Log.v(TAG, "getPreviewTexture:");
            if (mIsActive) {
                synchronized (mThread.mSync) {
                    mThread.sendEmptyMessage(MSG_CREATE_SURFACE_TEXTURE);
                    try {
                        mThread.mSync.wait();
                    } catch (final InterruptedException e) {
                    }
                    return mThread.mPreviewSurfaceTexture;
                }
            } else {
                return null;
            }
        }

        public void resize(final int width, final int height) {
            if (DEBUG) Log.v(TAG, "resize:");
            if (mIsActive) {
                synchronized (mThread.mSync) {
                    mThread.sendMessage(mThread.obtainMessage(MSG_RESIZE, width, height));
                    try {
                        mThread.mSync.wait();
                    } catch (final InterruptedException e) {
                    }
                }
            }
        }

        public final void release() {
            if (DEBUG) Log.v(TAG, "release:");
            if (mIsActive) {
                mIsActive = false;
                mThread.removeMessages(MSG_REQUEST_RENDER);
                mThread.removeMessages(MSG_SET_ENCODER);
                mThread.sendEmptyMessage(MSG_RELEASE);
            }
        }

        @Override
        public final void onFrameAvailable(final SurfaceTexture surfaceTexture) {
            if (mIsActive) {
                mFpsCounter.count();
                mThread.sendEmptyMessage(MSG_REQUEST_RENDER);
            }
        }

        private static final int MSG_INIT = 0;
        private static final int MSG_REQUEST_RENDER = 1;
        private static final int MSG_SET_ENCODER = 2;
        private static final int MSG_CREATE_SURFACE_TEXTURE = 3;
        private static final int MSG_RESIZE = 4;
        private static final int MSG_RELEASE = 9;

        private static final class RenderHandler extends Handler {
            private static String TAG_HANDLER = RenderAgent.RenderHandler.class.getSimpleName();
            private Object mSync = new Object();
            private SurfaceTexture mSurface;
            private RenderAgent mParent;
            private EGLBase mEgl;
            /**
             * IEglSurface instance related to this TextureView
             */
            private EGLBase.IEglSurface mEglSurface;
            private GLDrawer2D mDrawer;
            private int mTexId = -1;
            /**
             * SurfaceTexture instance to receive video images
             */
            private SurfaceTexture mPreviewSurfaceTexture;
            private float[] mStMatrix = new float[16];
            private MediaEncoder mEncoder;
            private int mViewWidth, mViewHeight;

            /**
             * constructor
             *
             * @param surface: drawing surface came from TextureView
             */
            public RenderHandler(final RenderAgent renderAgent, final SurfaceTexture surface, final int width, final int height) {
                super(createLooper());
                mParent = renderAgent;
                mSurface = surface;
                mViewWidth = width;
                mViewHeight = height;
            }

            private static Looper createLooper() {
                HandlerThread thread = new HandlerThread("RenderThread", Process.THREAD_PRIORITY_MORE_FAVORABLE);
                // start this HandlerThread, prepare for getLooper
                thread.start();
                return thread.getLooper();
            }

            @Override
            public final void handleMessage(final Message msg) {
                switch (msg.what) {
                    case MSG_INIT:
                        init();
                        break;
                    case MSG_REQUEST_RENDER:
                        onDrawFrame();
                        break;
                    case MSG_SET_ENCODER:
                        setEncoder((MediaEncoder) msg.obj);
                        break;
                    case MSG_CREATE_SURFACE_TEXTURE:
                        updatePreviewSurfaceTexture();
                        break;
                    case MSG_RESIZE:
                        resize(msg.arg1, msg.arg2);
                        break;
                    case MSG_RELEASE:
                        release();
                        break;
                    default:
                        super.handleMessage(msg);
                }
            }

            public void resize(final int width, final int height) {
                if ((width > 0 && width != mViewWidth) || (height > 0 && height != mViewHeight)) {
                    mViewWidth = width;
                    mViewHeight = height;
                    updatePreviewSurfaceTexture();
                } else {
                    synchronized (mSync) {
                        mSync.notifyAll();
                    }
                }
            }

            public final void updatePreviewSurfaceTexture() {
                if (DEBUG) Log.i(TAG, TAG_HANDLER + "#updatePreviewSurfaceTexture:");
                synchronized (mSync) {
                    if (mPreviewSurfaceTexture != null) {
                        if (DEBUG)
                            Log.d(TAG, "updatePreviewSurfaceTexture:release mPreviewSurface");
                        mPreviewSurfaceTexture.setOnFrameAvailableListener(null);
                        mPreviewSurfaceTexture.release();
                        mPreviewSurfaceTexture = null;
                    }
                    mEglSurface.makeCurrent();
                    if (mTexId >= 0) {
                        mDrawer.deleteTex(mTexId);
                    }
                    // create texture and SurfaceTexture for input from camera
                    mTexId = mDrawer.initTex();
                    if (DEBUG) Log.v(TAG, "updatePreviewSurfaceTexture:tex_id=" + mTexId);
                    mPreviewSurfaceTexture = new SurfaceTexture(mTexId);
                    mPreviewSurfaceTexture.setDefaultBufferSize(mViewWidth, mViewHeight);
                    mPreviewSurfaceTexture.setOnFrameAvailableListener(mParent);
                    // notify to caller thread that previewSurface is ready
                    mSync.notifyAll();
                }
            }

            public final void setEncoder(final MediaEncoder encoder) {
                if (DEBUG) Log.v(TAG, TAG_HANDLER + "#setEncoder:encoder=" + encoder);
                if (encoder != null && (encoder instanceof MediaVideoEncoder)) {
                    ((MediaVideoEncoder) encoder).setEglContext(mEglSurface.getContext(), mTexId);
                }
                mEncoder = encoder;
            }

            /*
             * Now you can get frame data as ByteBuffer(as YUV/RGB565/RGBX/NV21 pixel format) using IFrameCallback interface
             * with UVCCamera#setFrameCallback instead of using following code samples.
             */
/*			// for part1
 			private static final int BUF_NUM = 1;
			private static final int BUF_STRIDE = 640 * 480;
			private static final int BUF_SIZE = BUF_STRIDE * BUF_NUM;
			int cnt = 0;
			int offset = 0;
			final int pixels[] = new int[BUF_SIZE];
			final IntBuffer buffer = IntBuffer.wrap(pixels); */
/*			// for part2
			private ByteBuffer buf = ByteBuffer.allocateDirect(640 * 480 * 4);
 */

            /**
             * draw a frame (and request to draw for video capturing if it is necessary)
             */
            public final void onDrawFrame() {
                mEglSurface.makeCurrent();
                // update texture(came from camera)
                mPreviewSurfaceTexture.updateTexImage();
                // get texture matrix
                mPreviewSurfaceTexture.getTransformMatrix(mStMatrix);
                // notify video encoder if it exist
                if (mEncoder != null) {
                    // notify to capturing thread that the camera frame is available.
                    if (mEncoder instanceof MediaVideoEncoder) {
                        ((MediaVideoEncoder) mEncoder).frameAvailableSoon(mStMatrix);
                    } else {
                        mEncoder.frameAvailableSoon();
                    }
                }
                // draw to preview screen
                mDrawer.draw(mTexId, mStMatrix, 0);
                mEglSurface.swap();
/*				// sample code to read pixels into Buffer and save as a Bitmap (part1)
				buffer.position(offset);
				GLES20.glReadPixels(0, 0, 640, 480, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
				if (++cnt == 100) { // save as a Bitmap, only once on this sample code
					// if you save every frame as a Bitmap, app will crash by Out of Memory exception...
					Log.i(TAG, "Capture image using glReadPixels:offset=" + offset);
					final Bitmap bitmap = createBitmap(pixels,offset,  640, 480);
					final File outputFile = MediaMuxerWrapper.getCaptureFile(Environment.DIRECTORY_DCIM, ".png");
					try {
						final BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile));
						try {
							try {
								bitmap.compress(CompressFormat.PNG, 100, os);
								os.flush();
								bitmap.recycle();
							} catch (IOException e) {
							}
						} finally {
							os.close();
						}
					} catch (FileNotFoundException e) {
					} catch (IOException e) {
					}
				}
				offset = (offset + BUF_STRIDE) % BUF_SIZE;
*/
/*				// sample code to read pixels into Buffer and save as a Bitmap (part2)
		        buf.order(ByteOrder.LITTLE_ENDIAN);	// it is enough to call this only once.
		        GLES20.glReadPixels(0, 0, 640, 480, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
		        buf.rewind();
				if (++cnt == 100) {	// save as a Bitmap, only once on this sample code
					// if you save every frame as a Bitmap, app will crash by Out of Memory exception...
					final File outputFile = MediaMuxerWrapper.getCaptureFile(Environment.DIRECTORY_DCIM, ".png");
			        BufferedOutputStream os = null;
					try {
				        try {
				            os = new BufferedOutputStream(new FileOutputStream(outputFile));
				            Bitmap bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
				            bmp.copyPixelsFromBuffer(buf);
				            bmp.compress(Bitmap.CompressFormat.PNG, 90, os);
				            bmp.recycle();
				        } finally {
				            if (os != null) os.close();
				        }
					} catch (FileNotFoundException e) {
					} catch (IOException e) {
					}
				}
*/
            }

/*			// sample code to read pixels into IntBuffer and save as a Bitmap (part1)
			private static Bitmap createBitmap(final int[] pixels, final int offset, final int width, final int height) {
				final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
				paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
						0, 0, 1, 0, 0,
						0, 1, 0, 0, 0,
						1, 0, 0, 0, 0,
						0, 0, 0, 1, 0
					})));

				final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
				final Canvas canvas = new Canvas(bitmap);

				final Matrix matrix = new Matrix();
				matrix.postScale(1.0f, -1.0f);
				matrix.postTranslate(0, height);
				canvas.concat(matrix);

				canvas.drawBitmap(pixels, offset, width, 0, 0, width, height, false, paint);

				return bitmap;
			} */

            private void init() {
                if (DEBUG) Log.v(TAG, TAG_HANDLER + "#init:");
                // create EGLContext for this thread
                mEgl = EGLBase.createFrom(null, false, false);
                mEglSurface = mEgl.createFromSurface(mSurface);
                mEglSurface.makeCurrent();
                // create drawing object
                mDrawer = new GLDrawer2D(true);
            }

            private void release() {
                if (DEBUG) Log.v(TAG, TAG_HANDLER + "#release:");
                if (mDrawer != null) {
                    mDrawer.release();
                    mDrawer = null;
                }
                if (mPreviewSurfaceTexture != null) {
                    mPreviewSurfaceTexture.release();
                    mPreviewSurfaceTexture = null;
                }
                if (mTexId >= 0) {
                    GLHelper.deleteTex(mTexId);
                    mTexId = -1;
                }
                if (mEglSurface != null) {
                    mEglSurface.release();
                    mEglSurface = null;
                }
                if (mEgl != null) {
                    mEgl.release();
                    mEgl = null;
                }

                synchronized (mSync) {
                    mParent = null;
                    mSync.notifyAll();
                }
            }
        }
    }

}
