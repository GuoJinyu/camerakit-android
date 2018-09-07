package com.wonderkiln.camerakit;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.hardware.display.DisplayManagerCompat;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.wonderkiln.camerakit.core.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.wonderkiln.camerakit.CameraKit.Constants.FACING_BACK;
import static com.wonderkiln.camerakit.CameraKit.Constants.FACING_FRONT;
import static com.wonderkiln.camerakit.CameraKit.Constants.FLASH_AUTO;
import static com.wonderkiln.camerakit.CameraKit.Constants.FLASH_OFF;
import static com.wonderkiln.camerakit.CameraKit.Constants.FLASH_ON;
import static com.wonderkiln.camerakit.CameraKit.Constants.FLASH_TORCH;
import static com.wonderkiln.camerakit.CameraKit.Constants.PERMISSIONS_LAZY;
import static com.wonderkiln.camerakit.CameraKit.Constants.PERMISSIONS_PICTURE;
import static com.wonderkiln.camerakit.CameraKit.Constants.PERMISSIONS_STRICT;

public class CameraView extends CameraViewLayout {

    private static final String TAG = CameraView.class.getSimpleName();

    private static Handler sWorkerHandler;

    static {
        // Initialize a single worker thread. This can be static since only a single camera
        // reference can exist at a time.
        HandlerThread workerThread = new HandlerThread("CameraViewWorker");
        workerThread.setDaemon(true);
        workerThread.start();
        sWorkerHandler = new Handler(workerThread.getLooper());
    }

    @Facing
    private int mFacing;

    @Flash
    private int mFlash;

    @Focus
    private int mFocus;

    @CaptureMethod
    private int mMethod;

    private boolean mPinchToZoom;

    private float mZoom;

    @Permissions
    private int mPermissions;

    @VideoQuality
    private int mVideoQuality;
    private int mJpegQuality;
    private int mVideoBitRate;
    private boolean mLockVideoAspectRatio;
    private boolean mCropOutput;
    private boolean mDoubleTapToToggleFacing;

    private boolean mAdjustViewBounds;

    private int mPreviewWidth;
    private int mPreviewHeight;
    private int mCaptureWidth;
    private int mCaptureHeight;


    private DisplayOrientationDetector mDisplayOrientationDetector;
    private CameraImpl mCameraImpl;

    private PreviewImpl mPreviewImpl;

    private boolean mIsStarted;

    private EventDispatcher mEventDispatcher;

    private FocusLayout focusLayout;

    public CameraView(@NonNull Context context) {
        this(context, null);
    }

    public CameraView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (attrs != null) {
            TypedArray a = context.getTheme().obtainStyledAttributes(
                    attrs,
                    R.styleable.CameraView,
                    0, 0);

            try {
                mFacing = a.getInteger(R.styleable.CameraView_ckFacing, CameraKit.Defaults.DEFAULT_FACING);
                mFlash = a.getInteger(R.styleable.CameraView_ckFlash, CameraKit.Defaults.DEFAULT_FLASH);
                mFocus = a.getInteger(R.styleable.CameraView_ckFocus, CameraKit.Defaults.DEFAULT_FOCUS);
                mMethod = a.getInteger(R.styleable.CameraView_ckMethod, CameraKit.Defaults.DEFAULT_METHOD);
                mPinchToZoom = a.getBoolean(R.styleable.CameraView_ckPinchToZoom, CameraKit.Defaults.DEFAULT_PINCH_TO_ZOOM);
                mZoom = a.getFloat(R.styleable.CameraView_ckZoom, CameraKit.Defaults.DEFAULT_ZOOM);
                mPermissions = a.getInteger(R.styleable.CameraView_ckPermissions, CameraKit.Defaults.DEFAULT_PERMISSIONS);
                mVideoQuality = a.getInteger(R.styleable.CameraView_ckVideoQuality, CameraKit.Defaults.DEFAULT_VIDEO_QUALITY);
                mJpegQuality = a.getInteger(R.styleable.CameraView_ckJpegQuality, CameraKit.Defaults.DEFAULT_JPEG_QUALITY);
                mCropOutput = a.getBoolean(R.styleable.CameraView_ckCropOutput, CameraKit.Defaults.DEFAULT_CROP_OUTPUT);
                mVideoBitRate = a.getInteger(R.styleable.CameraView_ckVideoBitRate, CameraKit.Defaults.DEFAULT_VIDEO_BIT_RATE);
                mDoubleTapToToggleFacing = a.getBoolean(R.styleable.CameraView_ckDoubleTapToToggleFacing, CameraKit.Defaults.DEFAULT_DOUBLE_TAP_TO_TOGGLE_FACING);
                mLockVideoAspectRatio = a.getBoolean(R.styleable.CameraView_ckLockVideoAspectRatio, false);
                mAdjustViewBounds = a.getBoolean(R.styleable.CameraView_android_adjustViewBounds, CameraKit.Defaults.DEFAULT_ADJUST_VIEW_BOUNDS);
                mPreviewWidth = a.getInteger(R.styleable.CameraView_ckPreviewWidth, CameraKit.Defaults.DEFAULT_PREVIEW_WIDTH);
                mPreviewHeight = a.getInteger(R.styleable.CameraView_ckPreviewHeight, CameraKit.Defaults.DEFAULT_PREVIEW_HEIGHT);
                mCaptureWidth = a.getInteger(R.styleable.CameraView_ckCaptureWidth, CameraKit.Defaults.DEFAULT_CAPTURE_WIDTH);
                mCaptureHeight = a.getInteger(R.styleable.CameraView_ckCaptureHeight, CameraKit.Defaults.DEFAULT_CAPTURE_HEIGHT);
            } finally {
                a.recycle();
            }
        }

        mEventDispatcher = new EventDispatcher();

        mPreviewImpl = new SurfaceViewPreview(context, this);
        mCameraImpl = new Camera1(mEventDispatcher, mPreviewImpl);

        mIsStarted = false;

        // Handle situations where there's only 1 camera & it's front facing OR it's a chromebook in laptop mode
        WindowManager windowService = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        boolean isChromebookInLaptopMode = (context.getPackageManager().hasSystemFeature("org.chromium.arc.device_management") && windowService.getDefaultDisplay().getRotation() == Surface.ROTATION_0);
        if (mCameraImpl.frontCameraOnly() || isChromebookInLaptopMode) {
            mFacing = FACING_FRONT;
        }

        setFacing(mFacing);
        setFlash(mFlash);
        setFocus(mFocus);
        setMethod(mMethod);
        setPinchToZoom(mPinchToZoom);
        setZoom(mZoom);
        setPermissions(mPermissions);
        setVideoQuality(mVideoQuality);
        setVideoBitRate(mVideoBitRate);
        setLockVideoAspectRatio(mLockVideoAspectRatio);
        setCaptureHeight(mCaptureHeight);
        setCaptureWidth(mCaptureWidth);
        setPreviewHeight(mPreviewHeight);
        setPreviewWidth(mPreviewWidth);

        if (!isInEditMode()) {
            mDisplayOrientationDetector = new DisplayOrientationDetector(context) {
                @Override
                public void onDisplayOrDeviceOrientationChanged(int displayOrientation, int deviceOrientation) {
//                    mCameraImpl.setDisplayAndDeviceOrientation(displayOrientation, deviceOrientation);
//                    mPreviewImpl.setDisplayOrientation(displayOrientation);
                }
            };

            switch (mFocus) {
                case CameraKit.Constants.FOCUS_TAP_WITH_MARKER:
                    focusLayout = new FocusMarkerLayout(getContext());
                    addView(focusLayout);
                    break;
                case CameraKit.Constants.FOCUS_TAP_WITH_RECT:
                    focusLayout = new FocusRectLayout(getContext());
                    addView(focusLayout);
                    break;
                default:
                    break;
            }
        }
        mCameraImpl.setTapToAutofocusListener(new Camera.AutoFocusCallback() {
            @Override
            public void onAutoFocus(boolean success, Camera camera) {
                if (mFocus == CameraKit.Constants.FOCUS_TAP_WITH_MARKER || mFocus == CameraKit.Constants.FOCUS_TAP_WITH_RECT) {
                    focusLayout.focused(success);
                }
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!isInEditMode()) {
            mDisplayOrientationDetector.enable(
                    ViewCompat.isAttachedToWindow(this)
                            ? DisplayManagerCompat.getInstance(getContext().getApplicationContext())
                            .getDisplay(Display.DEFAULT_DISPLAY)
                            : null
            );
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (!isInEditMode()) {
            mDisplayOrientationDetector.disable();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mAdjustViewBounds) {
            Size previewSize = getPreviewSize();
            if (previewSize != null) {
                if (getLayoutParams().width == LayoutParams.WRAP_CONTENT) {
                    int height = MeasureSpec.getSize(heightMeasureSpec);
                    float ratio = (float) height / (float) previewSize.getHeight();
                    int width = (int) (previewSize.getWidth() * ratio);
                    super.onMeasure(
                            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                            heightMeasureSpec
                    );
                    return;
                } else if (getLayoutParams().height == LayoutParams.WRAP_CONTENT) {
                    int width = MeasureSpec.getSize(widthMeasureSpec);
                    float ratio = (float) width / (float) previewSize.getWidth();
                    int height = (int) (previewSize.getHeight() * ratio);
                    super.onMeasure(
                            widthMeasureSpec,
                            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                    );
                    return;
                }
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public boolean isStarted() {
        return mIsStarted;
    }

    public void addController(CameraKitController controller) {

    }

    public void start() {
        if (mIsStarted || !isEnabled()) {
            // Already started, do nothing.
            return;
        }
        mIsStarted = true;
        resetZoomDirectly();
        int cameraCheck = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA);
        int audioCheck = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO);

        switch (mPermissions) {
            case PERMISSIONS_STRICT:
                if (cameraCheck != PackageManager.PERMISSION_GRANTED || audioCheck != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(true, true);
                    return;
                }
                break;

            case PERMISSIONS_LAZY:
                if (cameraCheck != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(true, true);
                    return;
                }
                break;

            case PERMISSIONS_PICTURE:
                if (cameraCheck != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(true, false);
                    return;
                }
                break;
        }

        sWorkerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.start();
            }
        }, 100);
    }

    public void stop() {
        if (!mIsStarted) {
            // Already stopped, do nothing.
            return;
        }
        mIsStarted = false;

        sWorkerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.stop();
            }
        }, 100);
    }


    @Override
    protected CameraImpl getCameraImpl() {
        return mCameraImpl;
    }

    @Override
    protected PreviewImpl getPreviewImpl() {
        return mPreviewImpl;
    }

    @Override
    protected void onZoom(float modifier, boolean start) {
        Log.d(TAG, modifier + " modifier");
        if (mPinchToZoom) {
            mCameraImpl.modifyZoom((modifier - 1) * 0.8f + 1);
        }
    }

    @Override
    protected void onZoomDirectly(float zoom) {
        if (mPinchToZoom) {
            mCameraImpl.setZoomDirectly(zoom);
        }
    }

    @Override
    protected void handleZoom(boolean isZoomIn) {
        mCameraImpl.setZoom(isZoomIn);
    }

    @Override
    protected void onTapToFocus(float x, float y) {
        if (mFocus == CameraKit.Constants.FOCUS_TAP || mFocus == CameraKit.Constants.FOCUS_TAP_WITH_MARKER || mFocus == CameraKit.Constants.FOCUS_TAP_WITH_RECT) {
            if (mFocus == CameraKit.Constants.FOCUS_TAP_WITH_MARKER || mFocus == CameraKit.Constants.FOCUS_TAP_WITH_RECT) {
                focusLayout.focus(x, y);
            }

            float px = x * getPreviewImpl().getWidth() - getPreviewImpl().getX();
            float py = y * getPreviewImpl().getHeight() - getPreviewImpl().getY();
            mCameraImpl.setFocusArea(px / (float) getPreviewImpl().getWidth(), py / (float) getPreviewImpl().getHeight());
        }
    }

    @Override
    protected void onToggleFacing() {
        if (mDoubleTapToToggleFacing) {
            toggleFacing();
        }
    }

    @Nullable
    public CameraProperties getCameraProperties() {
        return mCameraImpl.getCameraProperties();
    }

    @Facing
    public int getFacing() {
        return mFacing;
    }

    public boolean isFacingFront() {
        return mFacing == CameraKit.Constants.FACING_FRONT;
    }

    public boolean isFacingBack() {
        return mFacing == CameraKit.Constants.FACING_BACK;
    }

    public void setFacing(@Facing final int facing) {
        this.mFacing = facing;
        sWorkerHandler.post(new Runnable() {
            @Override
            public void run() {
                mCameraImpl.setFacing(facing);
            }
        });
    }

    public void setFlash(@Flash int flash) {
        this.mFlash = flash;
        mCameraImpl.setFlash(flash);
    }

    @Flash
    public int getFlash() {
        return mFlash;
    }

    public void setFocus(@Focus int focus) {
        this.mFocus = focus;
        if (this.mFocus == CameraKit.Constants.FOCUS_TAP_WITH_MARKER || mFocus == CameraKit.Constants.FOCUS_TAP_WITH_RECT) {
            mCameraImpl.setFocus(CameraKit.Constants.FOCUS_TAP);
            return;
        }

        mCameraImpl.setFocus(mFocus);
    }

    public void setMethod(@CaptureMethod int method) {
        this.mMethod = method;
        mCameraImpl.setMethod(mMethod);
    }

    public void setPinchToZoom(boolean zoom) {
        this.mPinchToZoom = zoom;
    }

    public void setZoom(float zoom) {
        this.mZoom = zoom;
        mCameraImpl.setZoom(zoom);
    }

    public void setPermissions(@Permissions int permissions) {
        this.mPermissions = permissions;
    }

    public void setVideoQuality(@VideoQuality int videoQuality) {
        this.mVideoQuality = videoQuality;
        mCameraImpl.setVideoQuality(mVideoQuality);
    }

    public void setVideoBitRate(int videoBirRate) {
        this.mVideoBitRate = videoBirRate;
        mCameraImpl.setVideoBitRate(mVideoBitRate);
    }

    public void setLockVideoAspectRatio(boolean lockVideoAspectRatio) {
        this.mLockVideoAspectRatio = lockVideoAspectRatio;
        mCameraImpl.setLockVideoAspectRatio(lockVideoAspectRatio);
    }

    public void setJpegQuality(int jpegQuality) {
        this.mJpegQuality = jpegQuality;
    }

    public void setCropOutput(boolean cropOutput) {
        this.mCropOutput = cropOutput;
    }

    @Facing
    public int toggleFacing() {
        switch (mFacing) {
            case FACING_BACK:
                setFacing(FACING_FRONT);
                break;

            case FACING_FRONT:
                setFacing(FACING_BACK);
                break;
        }

        return mFacing;
    }

    @Flash
    public int toggleFlash() {
        switch (mFlash) {
            case FLASH_OFF:
                setFlash(FLASH_ON);
                break;

            case FLASH_ON:
                setFlash(FLASH_AUTO);
                break;

            case FLASH_AUTO:
            case FLASH_TORCH:
                setFlash(FLASH_OFF);
                break;
        }

        return mFlash;
    }

    public void captureImage() {
        captureImage(null);
    }

    public void captureImage(final CameraKitEventCallback<CameraKitImage> callback) {
        mCameraImpl.captureImage(new CameraImpl.ImageCapturedCallback() {
            @Override
            public void imageCaptured(byte[] jpeg) {
                if (jpeg == null) {
                    callback.callback(null);
                    return;
                }
                PostProcessor postProcessor = new PostProcessor(jpeg);
                postProcessor.setJpegQuality(mJpegQuality);
                postProcessor.setFacing(mFacing);
                if (mCropOutput)
                    postProcessor.setCropOutput(AspectRatio.of(getWidth(), getHeight()));

                CameraKitImage image = new CameraKitImage(postProcessor.getJpeg());
                if (callback != null) callback.callback(image);
                mEventDispatcher.dispatch(image);
            }
        });
    }

    public void captureVideo() {
        captureVideo(null, null);
    }

    public void captureVideo(File videoFile) {
        captureVideo(videoFile, null);
    }

    public void captureVideo(CameraKitEventCallback<CameraKitVideo> callback) {
        captureVideo(null, callback);
    }

    public void captureVideo(File videoFile, final CameraKitEventCallback<CameraKitVideo> callback) {
        mCameraImpl.captureVideo(videoFile, new CameraImpl.VideoCapturedCallback() {
            @Override
            public void videoCaptured(File file) {
                CameraKitVideo video = new CameraKitVideo(file);
                if (callback != null) callback.callback(video);
                mEventDispatcher.dispatch(video);
            }
        });
    }

    public void stopVideo() {
        mCameraImpl.stopVideo();
    }

    public Size getPreviewSize() {
        return mCameraImpl != null ? mCameraImpl.getPreviewResolution() : null;
    }

    public Size getCaptureSize() {
        return mCameraImpl != null ? mCameraImpl.getCaptureResolution() : null;
    }

    private void requestPermissions(boolean requestCamera, boolean requestAudio) {
        Activity activity = null;
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                activity = (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }

        List<String> permissions = new ArrayList<>();
        if (requestCamera) permissions.add(Manifest.permission.CAMERA);
        if (requestAudio) permissions.add(Manifest.permission.RECORD_AUDIO);

        if (activity != null) {
            ActivityCompat.requestPermissions(
                    activity,
                    permissions.toArray(new String[permissions.size()]),
                    CameraKit.Constants.PERMISSION_REQUEST_CAMERA);
        }
    }

    public void addCameraKitListener(CameraKitEventListener CameraKitEventListener) {
        mEventDispatcher.addListener(CameraKitEventListener);
    }

    public void bindCameraKitListener(Object object) {
        mEventDispatcher.addBinding(object);
    }

    public void setPreviewCallback(Camera.PreviewCallback callback) {
        mCameraImpl.setPreviewCallback(callback);
    }

    public int getPreviewRotation() {
        return mCameraImpl.getPreviewRotation();
    }

    public void setRequestedFps(float requestedFps) {
        mCameraImpl.setRequestedFps(requestedFps);
    }

    public int getPreviewWidth() {
        return mPreviewWidth;
    }

    public void setPreviewWidth(int mPreviewWidth) {
        this.mPreviewWidth = mPreviewWidth;
        if (mPreviewWidth != 0 && mPreviewHeight != 0 && mCameraImpl != null) {
            mCameraImpl.setPreviewResolution(mPreviewWidth, mPreviewHeight);
        }
    }

    public int getPreviewHeight() {
        return mPreviewHeight;
    }

    public void setPreviewHeight(int mPreviewHeight) {
        this.mPreviewHeight = mPreviewHeight;
        if (mPreviewWidth != 0 && mPreviewHeight != 0 && mCameraImpl != null) {
            mCameraImpl.setPreviewResolution(mPreviewWidth, mPreviewHeight);
        }
    }

    public int getCaptureWidth() {
        return mCaptureWidth;
    }

    public void setCaptureWidth(int mCaptureWidth) {
        this.mCaptureWidth = mCaptureWidth;
        if (mCaptureWidth != 0 && mCaptureHeight != 0 && mCameraImpl != null) {
            mCameraImpl.setCaptureResolution(mCaptureWidth, mCaptureHeight);
        }
    }

    public int getCaptureHeight() {
        return mCaptureHeight;
    }

    public void setCaptureHeight(int mCaptureHeight) {
        this.mCaptureHeight = mCaptureHeight;
        if (mCaptureWidth != 0 && mCaptureHeight != 0 && mCameraImpl != null) {
            mCameraImpl.setCaptureResolution(mCaptureWidth, mCaptureHeight);
        }
    }

}