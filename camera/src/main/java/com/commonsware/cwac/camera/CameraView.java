/***
 * Copyright (c) 2013-2014 CommonsWare, LLC
 * Portions Copyright (C) 2007 The Android Open Source Project
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.commonsware.cwac.camera;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.CameraInfo;
import android.hardware.SensorManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.commonsware.cwac.camera.CameraHost.FailureReason;

import java.io.IOException;

public class CameraView extends ViewGroup implements AutoFocusCallback {

    private static final int[] ROTATION_DEGREES = {0, 90, 180, 270};
    private static final int UPDATE_RATE_US = 200 * 1000;

    static final String TAG = "CWAC-Camera";
    private PreviewStrategy previewStrategy;
    private Camera.Size previewSize;
    private Camera camera = null;
    private boolean inPreview = false;
    private CameraHost host = null;
    private OnOrientationChange onOrientationChange = null;
    private int displayOrientation = -1;
    private int outputOrientation = -1;
    private int cameraId = -1;
    private MediaRecorder recorder = null;
    private Camera.Parameters previewParams = null;
    private boolean isDetectingFaces = false;
    private boolean isAutoFocusing = false;
    private Camera.PreviewCallback previewCallback;
    private static HandlerThread thread;
    private static Handler handler;

    private OrientationEventListener orientationEventListener;
    private int lastRotation;
    private WindowManager windowManager;

    private boolean isOrientationLocked = false;
    private boolean isOrientationHardLocked = false;

    static {
        thread = new HandlerThread("CWAC_CAMERA", HandlerThread.MAX_PRIORITY);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public CameraView(Context context) {
        super(context);
        onOrientationChange = new OnOrientationChange(context);
    }

    public CameraView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        onOrientationChange = new OnOrientationChange(context);

        if (context instanceof CameraHostProvider) {
            setCameraHost(((CameraHostProvider) context).getCameraHost());
        } else {
            throw new IllegalArgumentException("To use the two- or "
                    + "three-parameter constructors on CameraView, "
                    + "your activity needs to implement the "
                    + "CameraHostProvider interface");
        }
    }

    public CameraHost getCameraHost() {
        return (host);
    }

    // must call this after constructor, before onResume()

    public void setCameraHost(CameraHost host) {
        this.host = host;

        if (host.getDeviceProfile().useTextureView()) {
            previewStrategy = new TexturePreviewStrategy(this);
        } else {
            previewStrategy = new SurfacePreviewStrategy(this);
        }
    }

    public synchronized Camera.Parameters getCameraParameters() {
        if (camera != null && previewParams == null) {
            try {
                previewParams = camera.getParameters();
            } catch (RuntimeException e) {
                android.util.Log.v(getClass().getSimpleName(), "getCameraParameters(). Could not work with camera parameters.");
            }
        }

        return previewParams;
    }

    public void setCameraParameters(final Camera.Parameters parameters) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                setCameraParametersSync(parameters);
            }
        });
    }

    /**
     * Run only in executor
     *
     * @param parameters
     */
    protected void setCameraParametersSync(Camera.Parameters parameters) {
        try {
            if (camera != null && parameters != null) {

                camera.setParameters(parameters);

            }
            previewParams = parameters;
        } catch (RuntimeException e) {
            android.util.Log.v(getClass().getSimpleName(),
                    "setCameraParametersSync(). Could not set camera parameters.");
        }
    }

    /**
     * You must call {@code super.onCameraOpen} first
     *
     * @param camera
     */
    public void onCameraOpen(Camera camera) throws RuntimeException {
        if (getActivity().getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                && !isOrientationHardLocked) {
            onOrientationChange.enable();
        }

        setCameraDisplayOrientation();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
                && getCameraHost() instanceof Camera.FaceDetectionListener) {
            camera.setFaceDetectionListener((Camera.FaceDetectionListener) getCameraHost());
        }

        setPreviewCallback(previewCallback);

        if (orientationEventListener == null) {
            windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            orientationEventListener = new OrientationEventListener(getContext(),
                    SensorManager.SENSOR_DELAY_NORMAL) {
                @Override
                public void onOrientationChanged(int orientation) {

                    Display display = windowManager.getDefaultDisplay();
                    int rotation = display.getRotation();
                    if (rotation != lastRotation) {
                        setCameraDisplayOrientation();

                        lastRotation = rotation;
                    }
                }
            };
        }
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }

        if (this.isOrientationLocked) {
            lockOrientation();
        }
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public void onResume() {
        onOrientationChange.resetOrientation();
        ViewGroup parent = (ViewGroup) previewStrategy.getWidget().getParent();
        if (parent != null) {
            parent.removeAllViews();
        }
        addView(previewStrategy.getWidget());


        handler.post(new Runnable() {
            @Override
            public void run() {
                if (camera == null) {
                    try {
                        cameraId = getCameraHost().getCameraId();
                    } catch (RuntimeException e) {
                        getCameraHost().onCameraFail(FailureReason.UNKNOWN);
                    }

                    if (cameraId >= 0) {
                        try {
                            camera = Camera.open(cameraId);
                            getCameraParameters(); //sets previewParams
                            onCameraOpen(camera);
                        } catch (Exception e) {
                            getCameraHost().onCameraFail(FailureReason.UNKNOWN);
                        }
                    } else {
                        getCameraHost().onCameraFail(FailureReason.NO_CAMERAS_REPORTED);
                    }
                }
            }
        });
    }

    public void onPause() {
        previewDestroyed();
        if (previewStrategy.getWidget() != null) {
            removeView(previewStrategy.getWidget());
        }
        this.onOrientationChange.disable();
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
    }

    // based on CameraPreview.java from ApiDemos

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width =
                resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        final int height =
                resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        setMeasuredDimension(width, height);

        if (width > 0 && height > 0) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (camera != null && getCameraParameters() != null) {
                        Camera.Size newSize = null;

                        try {
                            if (getCameraHost().getRecordingHint() != CameraHost.RecordingHint.STILL_ONLY) {
                                newSize =
                                        getCameraHost().getPreferredPreviewSizeForVideo(getDisplayOrientation(),
                                                width,
                                                height,
                                                getCameraParameters(),
                                                null);
                            }

                            if (newSize == null || newSize.width * newSize.height < 65536) {
                                newSize =
                                        getCameraHost().getPreviewSize(getDisplayOrientation(),
                                                width, height,
                                                getCameraParameters());
                            }
                        } catch (Exception e) {
                            android.util.Log.v(getClass().getSimpleName(),
                                    "onMeasure(). Could not work with camera parameters.");
                        }

                        if (newSize != null) {
                            if (previewSize == null) {
                                previewSize = newSize;
                            } else if (previewSize.width != newSize.width
                                    || previewSize.height != newSize.height) {
                                if (inPreview) {
                                    stopPreview();
                                }

                                previewSize = newSize;
                                initPreview(width, height, false);
                            }
                        }
                        post(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        invalidate();
                                    }
                                }
                        );
                    }
                }
            });
        }
    }

    public Camera.Size getPreviewSize() {
        return previewSize;
    }

    // based on CameraPreview.java from ApiDemos

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() > 0) {
            final View child = getChildAt(0);
            final int width = r - l;
            final int height = b - t;
            int previewWidth = width;
            int previewHeight = height;

            // handle orientation

            if (previewSize != null && previewSize.height > 0 && previewSize.width > 0) {
                if (getDisplayOrientation() == 90
                        || getDisplayOrientation() == 270) {
                    previewWidth = previewSize.height;
                    previewHeight = previewSize.width;
                } else {
                    previewWidth = previewSize.width;
                    previewHeight = previewSize.height;
                }
            }

            if (previewWidth == 0 || previewHeight == 0) {
                return;
            }

            boolean useFirstStrategy =
                    (width * previewHeight > height * previewWidth);
            boolean useFullBleed = getCameraHost().useFullBleedPreview();

            if ((useFirstStrategy && !useFullBleed)
                    || (!useFirstStrategy && useFullBleed)) {
                final int scaledChildWidth =
                        previewWidth * height / previewHeight;
                child.layout((width - scaledChildWidth) / 2, 0,
                        (width + scaledChildWidth) / 2, height);
            } else {
                final int scaledChildHeight =
                        previewHeight * width / previewWidth;
                child.layout(0, (height - scaledChildHeight) / 2, width,
                        (height + scaledChildHeight) / 2);
            }
        }
    }

    public int getDisplayOrientation() {
        return (displayOrientation);
    }

    @Deprecated
    public void lockToLandscape() {
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        lockOrientation();
    }

    @Deprecated
    public void lockToPortrait() {
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);

        lockOrientation();
    }

    public void lockToLandscape(boolean lockPicture) {
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        this.isOrientationLocked = true;
        this.isOrientationHardLocked = lockPicture;
    }

    public void lockToPortrait(boolean lockPicture) {
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);

        this.isOrientationLocked = true;
        this.isOrientationHardLocked = lockPicture;
    }

    private void lockOrientation() {
        post(new Runnable() {
            @Override
            public void run() {
                setCameraDisplayOrientationAsync();
                if (!isOrientationHardLocked) {
                    onOrientationChange.enable();
                } else {
                    setPictureOrientationAsync();
                }
            }
        });
    }

    public void unlockOrientation() {
        this.isOrientationLocked = false;
        this.isOrientationHardLocked = false;
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        onOrientationChange.disable();

        post(new Runnable() {
            @Override
            public void run() {
                setCameraDisplayOrientationAsync();
            }
        });
    }

    public void restartPreview() {
        if (!inPreview) {
            startPreview();
        }
    }

    public void takePicture(boolean needBitmap, boolean needByteArray) {
        PictureTransaction xact = new PictureTransaction(getCameraHost());

        takePicture(xact.needBitmap(needBitmap)
                .needByteArray(needByteArray));
    }

    public void takePicture(final PictureTransaction xact) {
        takePictureAsync(xact);
    }

    private void takePictureAsync(final PictureTransaction xact) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (inPreview) {
                    if (isAutoFocusing) {
                        Log.e(getClass().getSimpleName(),
                                "Camera cannot take a picture while auto-focusing");
                    } else {
                        xact.cameraView = CameraView.this;
                        tryTakePicture(xact);
                    }
                } else {
                    Log.e(getClass().getSimpleName(),
                            "Preview mode must have started before you can take a picture");
                }
            }
        });
    }

    private void tryTakePicture(PictureTransaction xact) {
        if (camera != null) {
            try {
                inPreview = false;

                getCameraParameters();

                Camera.Parameters pictureParams = camera.getParameters();

                if (!onOrientationChange.isEnabled()) {
                    setCameraPictureOrientation(pictureParams);
                }

                camera.setParameters(xact.host.adjustPictureParameters(xact, pictureParams));
                camera.takePicture(xact.host.getShutterCallback(), null,
                        new PictureTransactionCallback(xact));
            } catch (Exception e) {
                Log.e(getClass().getSimpleName(),
                        "Exception taking a picture", e);
                // TODO get this out to library clients

                inPreview = true;
            }
        }
    }

    public boolean isRecording() {
        return (recorder != null);
    }

    public void record() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            throw new UnsupportedOperationException(
                    "Video recording supported only on API Level 11+");
        }

        if (displayOrientation != 0 && displayOrientation != 180) {
            throw new UnsupportedOperationException(
                    "Video recording supported only in landscape");
        }

        Camera.Parameters pictureParams = camera.getParameters();

        setCameraPictureOrientation(pictureParams);
        camera.setParameters(pictureParams);

        stopPreview();
        camera.unlock();

        try {
            recorder = new MediaRecorder();
            recorder.setCamera(camera);
            getCameraHost().configureRecorderAudio(cameraId, recorder);
            recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            getCameraHost().configureRecorderProfile(cameraId, recorder);
            getCameraHost().configureRecorderOutput(cameraId, recorder);
            recorder.setOrientationHint(outputOrientation);
            previewStrategy.attach(recorder);
            recorder.prepare();
            recorder.start();
        } catch (IOException e) {
            recorder.release();
            recorder = null;
            throw e;
        }
    }

    public void stopRecording() throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            throw new UnsupportedOperationException(
                    "Video recording supported only on API Level 11+");
        }

        MediaRecorder tempRecorder = recorder;

        recorder = null;
        tempRecorder.stop();
        tempRecorder.release();
        camera.reconnect();
    }

    public void autoFocus() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (inPreview && camera != null) {
                    try {
                        camera.autoFocus(CameraView.this);
                        isAutoFocusing = true;
                    } catch (RuntimeException e) {
                        Log.e(getClass().getSimpleName(), "Could not auto focus?", e);
                    }

                }
            }
        });
    }

    public void cancelAutoFocus() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    try {
                        isAutoFocusing = false;
                        camera.cancelAutoFocus();
                    } catch (RuntimeException e) {
                        Log.e(getClass().getSimpleName(), "Could not cancel auto focus?", e);
                    }
                }
            }
        });
    }

    public boolean isAutoFocusAvailable() {
        return (inPreview);
    }

    @Override
    public void onAutoFocus(boolean success, Camera camera) {
        isAutoFocusing = false;

        if (getCameraHost() instanceof AutoFocusCallback) {
            getCameraHost().onAutoFocus(success, camera);
        }
    }

    public String getFlashMode() {
        return (previewParams.getFlashMode());
    }

    public void setFlashMode(final String mode) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    Camera.Parameters params = getCameraParameters();
                    params.setFlashMode(mode);
                    setCameraParametersSync(params);
                }
            }
        });
    }

    public ZoomTransaction zoomTo(int level) {
        if (camera == null) {
            throw new IllegalStateException(
                    "Yes, we have no camera, we have no camera today");
        } else {
            Camera.Parameters params = getCameraParameters();

            if (level >= 0 && level <= params.getMaxZoom()) {
                return (new ZoomTransaction(camera, level));
            } else {
                throw new IllegalArgumentException(
                        String.format("Invalid zoom level: %d",
                                level));
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public void startFaceDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
                && camera != null && !isDetectingFaces
                && getCameraParameters().getMaxNumDetectedFaces() > 0) {
            camera.startFaceDetection();
            isDetectingFaces = true;
        }
    }

    public void stopFaceDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
                && camera != null && isDetectingFaces) {
            camera.stopFaceDetection();
            isDetectingFaces = false;
        }
    }

    public void setPreviewCallback(final Camera.PreviewCallback callback) {
        previewCallback = callback;

        handler.post(new Runnable() {
            @Override
            public void run() {
                setPreviewCallbackSync(callback);
            }
        });
    }

    public void addPreviewCallbackBuffer(final byte[] buffer) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                addPreviewCallbackBufferSync(buffer);
            }
        });
    }

    protected void setPreviewCallbackSync(Camera.PreviewCallback callback) {
        previewCallback = callback;

        if (camera != null) {
            try {
                if (getCameraHost().getDeviceProfile().isCustomRom()) {
                    camera.setPreviewCallback(previewCallback);
                } else {
                    camera.setPreviewCallbackWithBuffer(previewCallback);
                }
            } catch (RuntimeException e) {
                android.util.Log.e(getClass().getSimpleName(),
                        "setPreviewCallbackSync(). Could not set preview callback.",e);
            }
        }
    }

    protected void addPreviewCallbackBufferSync(final byte[] buffer) {
        if (camera != null && buffer != null) {
            camera.addCallbackBuffer(buffer);
        }
    }

    public boolean doesZoomReallyWork() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(getCameraHost().getCameraId(), info);

        return (getCameraHost().getDeviceProfile().doesZoomActuallyWork(info.facing == CameraInfo.CAMERA_FACING_FRONT));
    }

    void previewCreated() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    try {
                        previewStrategy.attach(camera);
                    } catch (IOException | RuntimeException e) {
                        getCameraHost().handleException(e);
                    }
                }
            }
        });
    }

    void previewDestroyed() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    try {
                        if (inPreview) {
                            stopPreviewSync();
                        } else {
                            camera.setPreviewCallback(null);
                        }
                        camera.release();
                    } catch (RuntimeException e) {
                        android.util.Log.e(getClass().getSimpleName(),
                                "Could not release camera.",
                                e);
                    }
                    camera = null;
                }

                CameraView.this.onOrientationChange.disable();
            }
        });
    }

    void previewReset(int width, int height) {
        previewStopped();
        initPreview(width, height);
    }

    private void previewStopped() {
        if (inPreview) {
            stopPreview();
        }
    }

    public void initPreview(int w, int h) {
        initPreview(w, h, true);
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public void initPreview(final int w, final int h, boolean firstRun) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    try {
                        Camera.Parameters parameters = getCameraParameters();
                        if (previewSize == null) {
                            previewSize = getCameraHost().getPreviewSize(getDisplayOrientation(), w, h, parameters);
                        }

                        parameters.setPreviewSize(previewSize.width, previewSize.height);

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                            parameters.setRecordingHint(getCameraHost().getRecordingHint() != CameraHost.RecordingHint.STILL_ONLY);
                        }

                        setCameraParametersSync(getCameraHost().adjustPreviewParameters(parameters));
                    } catch (Exception e) {
                        android.util.Log.v(getClass().getSimpleName(),
                                "initPreview(). Could not work with camera parameters.");
                    }

                    post(new Runnable() {
                        @Override
                        public void run() {
                            requestLayout();
                        }
                    });

                    startPreviewSync();
                }
            }
        });
    }

    public void startPreview() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                startPreviewSync();
            }
        });
    }

    protected void startPreviewSync() {
        try {
            if (camera != null) {
                camera.startPreview();
                inPreview = true;
                getCameraHost().autoFocusAvailable();
            }
        } catch (RuntimeException e) {
            android.util.Log.v(getClass().getSimpleName(),
                    "startPreviewSync(). Could not start preview.");
        }
    }

    public void stopPreview() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                stopPreviewSync();
            }
        });
    }

    private void stopPreviewSync() {
        try {
            if (camera != null) {
                inPreview = false;
                getCameraHost().autoFocusUnavailable();
                camera.setPreviewCallback(null);
                camera.stopPreview();
            }
        } catch (RuntimeException e) {  //FIXME
            android.util.Log.v(getClass().getSimpleName(),
                    "stopPreviewSync(). Could not stop preview.");
        }
    }

    private void setCameraDisplayOrientationAsync() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                setCameraDisplayOrientation();
            }
        });
    }

    private void setPictureOrientationAsync() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Camera.Parameters parameters = getCameraParameters();
                if (parameters != null) {
                    setCameraPictureOrientation(parameters);
                    setCameraParametersSync(parameters);
                }
            }
        });
    }

    // based on
    // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
    // and http://stackoverflow.com/a/10383164/115145

    private void setCameraDisplayOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        DisplayMetrics dm = new DisplayMetrics();

        Camera.getCameraInfo(cameraId, info);
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(dm);

        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            displayOrientation = (info.orientation + degrees) % 360;
            displayOrientation = (360 - displayOrientation) % 360;
        } else {
            displayOrientation = (info.orientation - degrees + 360) % 360;
        }

        if (camera != null) {
            boolean wasInPreview = inPreview;

            if (inPreview) {
                stopPreviewSync();
            }

            try {
                camera.setDisplayOrientation(displayOrientation);
            } catch (RuntimeException e) {
                android.util.Log.v(getClass().getSimpleName(),
                        "setCameraDisplayOrientation(). Could not set camera display orientation.");
            }

            if (wasInPreview) {
                startPreviewSync();
            }
        }
    }

    private void setCameraPictureOrientation(Camera.Parameters params) {
        Camera.CameraInfo info = new Camera.CameraInfo();

        Camera.getCameraInfo(cameraId, info);

        if (getActivity().getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                && !isOrientationHardLocked) {
            outputOrientation =
                    getCameraPictureRotation(getActivity().getWindowManager()
                            .getDefaultDisplay()
                            .getOrientation());
        } else if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            outputOrientation = (360 - displayOrientation) % 360;
        } else {
            outputOrientation = displayOrientation;
        }

        params.setRotation(outputOrientation);
    }

    // based on:
    // http://developer.android.com/reference/android/hardware/Camera.Parameters.html#setRotation(int)

    private int getCameraPictureRotation(int orientation) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = 0;

        orientation = (orientation + 45) / 90 * 90;

        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            rotation = (info.orientation - orientation + 360) % 360;
        } else { // back-facing camera
            rotation = (info.orientation + orientation) % 360;
        }

        return (rotation);
    }

    Activity getActivity() {
        return ((Activity) getContext());
    }

    private class OnOrientationChange extends OrientationEventListener {

        private int currentOrientation = ORIENTATION_UNKNOWN;
        private boolean isEnabled = false;

        public OnOrientationChange(Context context) {
            super(context, UPDATE_RATE_US);
            disable();
        }

        public void resetOrientation() {
            currentOrientation = ORIENTATION_UNKNOWN;
        }

        @Override
        public void onOrientationChanged(int orientation) {
            if (camera == null || !canDetectOrientation() || orientation == ORIENTATION_UNKNOWN) {
                return;
            }

            orientation = getClosestRotationDegree(orientation);

            if (orientation != currentOrientation) {
                outputOrientation = getCameraPictureRotation(orientation);

                Camera.Parameters params = getCameraParameters();

                params.setRotation(outputOrientation);
                setCameraParametersSync(params);
                currentOrientation = orientation;
            }
        }

        private int getClosestRotationDegree(int rotation) {
            for (int value : ROTATION_DEGREES) {
                final int diff = Math.abs(rotation - value);

                if (diff < 45) {
                    return value;
                }
            }
            return 0;
        }

        @Override
        public void enable() {
            isEnabled = true;
            super.enable();
        }

        @Override
        public void disable() {
            isEnabled = false;
            super.disable();
        }

        boolean isEnabled() {
            return (isEnabled);
        }
    }

    private class PictureTransactionCallback implements
            Camera.PictureCallback {
        PictureTransaction xact = null;

        PictureTransactionCallback(PictureTransaction xact) {
            this.xact = xact;
        }

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            if (previewParams != null) {
                CameraView.this.setCameraParameters(previewParams);
            }

            final byte[] finalizedData = data;
            if (finalizedData != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            new ImageCleanupTask(getContext(), finalizedData, cameraId, xact).run();
                        } catch (Throwable e) {
                            Log.e("CameraView", "Error camera thread stopped", e);
                        }
                    }
                });
            }

            if (!xact.useSingleShotMode()) {
                startPreview();
            }
        }
    }
}