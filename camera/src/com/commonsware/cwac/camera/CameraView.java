/***
 Copyright (c) 2013-2014 CommonsWare, LLC
 Portions Copyright (C) 2007 The Android Open Source Project

 Licensed under the Apache License, Version 2.0 (the "License"); you may
 not use this file except in compliance with the License. You may obtain
 a copy of the License at
 http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.commonsware.cwac.camera;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.CameraInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.commonsware.cwac.camera.CameraHost.FailureReason;

public class CameraView extends ViewGroup implements AutoFocusCallback {

    private static final int[] ROTATION_DEGREES = {0, 90, 180, 270};
    private static final int UPDATE_RATE_US = 200 * 1000;

    static final String TAG="CWAC-Camera";
    private PreviewStrategy previewStrategy;
    private Camera.Size previewSize;
    private Camera camera=null;
    private boolean inPreview=false;
    private CameraHost host=null;
    private OnOrientationChange onOrientationChange=null;
    private int displayOrientation=-1;
    private int outputOrientation=-1;
    private int cameraId=-1;
    private MediaRecorder recorder=null;
    private Camera.Parameters previewParams=null;
    private boolean isDetectingFaces=false;
    private boolean isAutoFocusing=false;
    private Camera.PreviewCallback previewCallback;

    private static final Executor cameraExecutor = Executors.newSingleThreadExecutor();

    public CameraView(Context context) {
        super(context);

        onOrientationChange=new OnOrientationChange(context);
    }

    public CameraView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        onOrientationChange=new OnOrientationChange(context);

        if (context instanceof CameraHostProvider) {
            setHost(((CameraHostProvider)context).getCameraHost());
        }
        else {
            throw new IllegalArgumentException("To use the two- or "
                    + "three-parameter constructors on CameraView, "
                    + "your activity needs to implement the "
                    + "CameraHostProvider interface");
        }
    }

    public CameraHost getHost() {
        return(host);
    }

    // must call this after constructor, before onResume()

    public void setHost(CameraHost host) {
        this.host=host;

        if (host.getDeviceProfile().useTextureView()) {
            previewStrategy=new TexturePreviewStrategy(this);
        }
        else {
            previewStrategy=new SurfacePreviewStrategy(this);
        }
    }

    public Camera.Parameters getCameraParameters() {
        return previewParams;
    }

    public void setCameraParameters(final Camera.Parameters parameters) {
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                setCameraParametersSync(parameters);
            }
        });
    }

    /**
     * Run only in executor
     * @param parameters
     */
    private void setCameraParametersSync(Camera.Parameters parameters) {
        try {
            if (camera != null && parameters != null) {

                    camera.setParameters(parameters);

            }
            previewParams = parameters;
        } catch (RuntimeException e) {  //FIXME
            e.printStackTrace();
        }
    }

    /**
     * Run only in executor
     */
    private Camera.Parameters getCameraParametersSync() {
        if (camera != null) {
            try {
                previewParams = camera.getParameters();
            } catch (RuntimeException e) {
                android.util.Log.e(getClass().getSimpleName(), "Could not work with camera parameters?", e);
            }
        }
        return previewParams;
    }

    /**
     * You must call {@code super.onCameraOpen} first
     * @param camera
     */
    public void onCameraOpen(Camera camera) throws RuntimeException{
        if (getActivity().getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            onOrientationChange.enable();
        }

        setCameraDisplayOrientation();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
                && getHost() instanceof Camera.FaceDetectionListener) {
            camera.setFaceDetectionListener((Camera.FaceDetectionListener)getHost());
        }

        setPreviewCallback(previewCallback);
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public void onResume() {
        onOrientationChange.resetOrientation();
        addView(previewStrategy.getWidget());


        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (camera == null) {
                    cameraId = getHost().getCameraId();

                    if (cameraId >= 0) {
                        try {
                            camera = Camera.open(cameraId);
                            getCameraParametersSync(); //sets previewParams
                            onCameraOpen(camera);
                        } catch (Exception e) {
                            getHost().onCameraFail(FailureReason.UNKNOWN);
                        }
                    } else {
                        getHost().onCameraFail(FailureReason.NO_CAMERAS_REPORTED);
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
    }

    // based on CameraPreview.java from ApiDemos

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width=
                resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        final int height=
                resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        setMeasuredDimension(width, height);

        if (width > 0 && height > 0) {
            if (camera != null && getCameraParameters() != null) {
                Camera.Size newSize=null;

                try {
                    if (getHost().getRecordingHint() != CameraHost.RecordingHint.STILL_ONLY) {
                        newSize=
                                getHost().getPreferredPreviewSizeForVideo(getDisplayOrientation(),
                                        width,
                                        height,
                                        getCameraParameters(),
                                        null);
                    }

                    if (newSize == null || newSize.width * newSize.height < 65536) {
                        newSize=
                                getHost().getPreviewSize(getDisplayOrientation(),
                                        width, height,
                                        getCameraParameters());
                    }
                }
                catch (Exception e) {
                    android.util.Log.e(getClass().getSimpleName(),
                            "Could not work with camera parameters?",
                            e);
                    // TODO get this out to library clients
                }

                if (newSize != null) {
                    if (previewSize == null) {
                        previewSize=newSize;
                    }
                    else if (previewSize.width != newSize.width
                            || previewSize.height != newSize.height) {
                        if (inPreview) {
                            stopPreview();
                        }

                        previewSize=newSize;
                        initPreview(width, height, false);
                    }
                }
            }
        }
    }

    public Camera.Size getPreviewSize() {
        return previewSize;
    }

    // based on CameraPreview.java from ApiDemos

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() > 0) {
            final View child=getChildAt(0);
            final int width=r - l;
            final int height=b - t;
            int previewWidth=width;
            int previewHeight=height;

            // handle orientation

            if (previewSize != null && previewSize.height > 0 && previewSize.width > 0 ) {
                if (getDisplayOrientation() == 90
                        || getDisplayOrientation() == 270) {
                    previewWidth=previewSize.height;
                    previewHeight=previewSize.width;
                }
                else {
                    previewWidth=previewSize.width;
                    previewHeight=previewSize.height;
                }
            }

            if (previewWidth == 0 || previewHeight == 0) {
                return;
            }

            boolean useFirstStrategy=
                    (width * previewHeight > height * previewWidth);
            boolean useFullBleed=getHost().useFullBleedPreview();

            if ((useFirstStrategy && !useFullBleed)
                    || (!useFirstStrategy && useFullBleed)) {
                final int scaledChildWidth=
                        previewWidth * height / previewHeight;
                child.layout((width - scaledChildWidth) / 2, 0,
                        (width + scaledChildWidth) / 2, height);
            }
            else {
                final int scaledChildHeight=
                        previewHeight * width / previewWidth;
                child.layout(0, (height - scaledChildHeight) / 2, width,
                        (height + scaledChildHeight) / 2);
            }
        }
    }

    public int getDisplayOrientation() {
        return(displayOrientation);
    }

    public void lockToLandscape(boolean enable) {
        if (enable) {
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            onOrientationChange.enable();
        }
        else {
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            onOrientationChange.disable();
        }

        post(new Runnable() {
            @Override
            public void run() {
                setCameraDisplayOrientation();
            }
        });
    }

    public void restartPreview() {
        if (!inPreview) {
            startPreview();
        }
    }

    public void takePicture(boolean needBitmap, boolean needByteArray) {
        PictureTransaction xact=new PictureTransaction(getHost());

        takePicture(xact.needBitmap(needBitmap)
                .needByteArray(needByteArray));
    }

    public void takePicture(final PictureTransaction xact) {
        if (inPreview) {
            if (isAutoFocusing) {
                throw new IllegalStateException(
                        "Camera cannot take a picture while auto-focusing");
            }
            else {
                getCameraParametersSync();

                Camera.Parameters pictureParams=camera.getParameters();
                Camera.Size pictureSize=
                        xact.host.getPictureSize(xact, pictureParams);

                pictureParams.setPictureSize(pictureSize.width, pictureSize.height);
                pictureParams.setPictureFormat(ImageFormat.JPEG);

                if (xact.flashMode != null) {
                    pictureParams.setFlashMode(xact.flashMode);
                }

                if (!onOrientationChange.isEnabled()) {
                    setCameraPictureOrientation(pictureParams);
                }

                camera.setParameters(xact.host.adjustPictureParameters(xact, pictureParams));
                xact.cameraView=CameraView.this;

                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        takePictureAsync(xact);
                    }
                }, xact.host.getDeviceProfile().getPictureDelay());

                inPreview=false;
            }
        }
        else {
            throw new IllegalStateException(
                    "Preview mode must have started before you can take a picture");
        }
    }

    private void takePictureAsync(final PictureTransaction xact) {
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    try {
                        camera.takePicture(xact, null,
                                new PictureTransactionCallback(xact));
                    }
                    catch (Exception e) {
                        android.util.Log.e(getClass().getSimpleName(),
                                "Exception taking a picture", e);
                        // TODO get this out to library clients
                    }
                }
            }
        });
    }

    public boolean isRecording() {
        return(recorder != null);
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

        Camera.Parameters pictureParams=camera.getParameters();

        setCameraPictureOrientation(pictureParams);
        camera.setParameters(pictureParams);

        stopPreview();
        camera.unlock();

        try {
            recorder=new MediaRecorder();
            recorder.setCamera(camera);
            getHost().configureRecorderAudio(cameraId, recorder);
            recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            getHost().configureRecorderProfile(cameraId, recorder);
            getHost().configureRecorderOutput(cameraId, recorder);
            recorder.setOrientationHint(outputOrientation);
            previewStrategy.attach(recorder);
            recorder.prepare();
            recorder.start();
        }
        catch (IOException e) {
            recorder.release();
            recorder=null;
            throw e;
        }
    }

    public void stopRecording() throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            throw new UnsupportedOperationException(
                    "Video recording supported only on API Level 11+");
        }

        MediaRecorder tempRecorder=recorder;

        recorder=null;
        tempRecorder.stop();
        tempRecorder.release();
        camera.reconnect();
    }

    public void autoFocus() {
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (inPreview && camera != null) {
                    try {
                        camera.autoFocus(CameraView.this);
                        isAutoFocusing = true;
                    } catch (RuntimeException e) {
                        android.util.Log.e(getClass().getSimpleName(), "Could not auto focus?", e);
                    }

                }
            }
        });
    }

    public void cancelAutoFocus() {
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    camera.cancelAutoFocus();
                }
            }
        });
    }

    public boolean isAutoFocusAvailable() {
        return(inPreview);
    }

    @Override
    public void onAutoFocus(boolean success, Camera camera) {
        isAutoFocusing=false;

        if (getHost() instanceof AutoFocusCallback) {
            getHost().onAutoFocus(success, camera);
        }
    }

    public String getFlashMode() {
        return(getCameraParameters().getFlashMode());
    }

    public void setFlashMode(final String mode) {
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    Camera.Parameters params=getCameraParametersSync();
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
        }
        else {
            Camera.Parameters params=getCameraParametersSync();

            if (level >= 0 && level <= params.getMaxZoom()) {
                return(new ZoomTransaction(camera, level));
            }
            else {
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
                && getCameraParametersSync().getMaxNumDetectedFaces() > 0) {
            camera.startFaceDetection();
            isDetectingFaces=true;
        }
    }

    public void stopFaceDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
                && camera != null && isDetectingFaces) {
            camera.stopFaceDetection();
            isDetectingFaces=false;
        }
    }

    public void setPreviewCallback(Camera.PreviewCallback callback) {
        previewCallback = callback;

        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    try {
                        if (getHost().getDeviceProfile().isCustomRom()) {
                            camera.setPreviewCallback(previewCallback);
                        } else {
                            camera.setPreviewCallbackWithBuffer(previewCallback);
                        }
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    public void addPreviewCallbackBuffer(final byte[] buffer) {
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (camera != null && buffer != null) {
                    camera.addCallbackBuffer(buffer);
                }
            }
        });
    }

    public boolean doesZoomReallyWork() {
        Camera.CameraInfo info=new Camera.CameraInfo();
        Camera.getCameraInfo(getHost().getCameraId(), info);

        return(getHost().getDeviceProfile().doesZoomActuallyWork(info.facing == CameraInfo.CAMERA_FACING_FRONT));
    }

    void previewCreated() {
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    try {
                        previewStrategy.attach(camera);
                    }
                    catch (IOException e) {
                        getHost().handleException(e);
                    }
                }
            }
        });
    }

    void previewDestroyed() {
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    previewStopped();
                    camera.setPreviewCallback(null);
                    camera.release();
                    camera = null;
                }
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
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    Camera.Parameters parameters = getCameraParametersSync();

                    if (previewSize == null) {
                        previewSize = getHost().getPreviewSize(getDisplayOrientation(), w, h, parameters);
                    }

                    parameters.setPreviewSize(previewSize.width, previewSize.height);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                        parameters.setRecordingHint(getHost().getRecordingHint() != CameraHost.RecordingHint.STILL_ONLY);
                    }

                    setCameraParametersSync(getHost().adjustPreviewParameters(parameters));

                    post(new Runnable() {
                        @Override
                        public void run() {
                            requestLayout();
                        }
                    });

                    startPreview();
                }
            }
        });
    }

    public void startPreview() {
        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                startPreviewSync();
            }
        });
    }

    private void startPreviewSync() {
        try {
            if (camera != null) {
                camera.startPreview();
                inPreview = true;
                getHost().autoFocusAvailable();
            }
        } catch (RuntimeException e) {  //FIXME
            e.printStackTrace();
        }
    }

    public void stopPreview() {
        cameraExecutor.execute(new Runnable() {
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
                getHost().autoFocusUnavailable();
                camera.setPreviewCallback(null);
                camera.stopPreview();
            }
        } catch (RuntimeException e) {  //FIXME
            e.printStackTrace();
        }
    }

    // based on
    // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
    // and http://stackoverflow.com/a/10383164/115145

    private void setCameraDisplayOrientation() {
        Camera.CameraInfo info=new Camera.CameraInfo();
        int rotation=getActivity().getWindowManager().getDefaultDisplay().getRotation();
        int degrees=0;
        DisplayMetrics dm=new DisplayMetrics();

        Camera.getCameraInfo(cameraId, info);
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(dm);

        switch (rotation) {
            case Surface.ROTATION_0:
                degrees=0;
                break;
            case Surface.ROTATION_90:
                degrees=90;
                break;
            case Surface.ROTATION_180:
                degrees=180;
                break;
            case Surface.ROTATION_270:
                degrees=270;
                break;
        }

        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            displayOrientation=(info.orientation + degrees) % 360;
            displayOrientation=(360 - displayOrientation) % 360;
        }
        else {
            displayOrientation=(info.orientation - degrees + 360) % 360;
        }

        cameraExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    boolean wasInPreview=inPreview;

                    if (inPreview) {
                        stopPreviewSync();
                    }

                    camera.setDisplayOrientation(displayOrientation);

                    if (wasInPreview) {
                        startPreviewSync();
                    }
                }
            }
        });
    }

    private void setCameraPictureOrientation(Camera.Parameters params) {
        Camera.CameraInfo info=new Camera.CameraInfo();

        Camera.getCameraInfo(cameraId, info);

        if (getActivity().getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            outputOrientation=
                    getCameraPictureRotation(getActivity().getWindowManager()
                            .getDefaultDisplay()
                            .getOrientation());
        }
        else if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            outputOrientation=(360 - displayOrientation) % 360;
        }
        else {
            outputOrientation=displayOrientation;
        }

        params.setRotation(outputOrientation);
    }

    // based on:
    // http://developer.android.com/reference/android/hardware/Camera.Parameters.html#setRotation(int)

    private int getCameraPictureRotation(int orientation) {
        Camera.CameraInfo info=new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation=0;

        orientation=(orientation + 45) / 90 * 90;

        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            rotation=(info.orientation - orientation + 360) % 360;
        }
        else { // back-facing camera
            rotation=(info.orientation + orientation) % 360;
        }

        return(rotation);
    }

    Activity getActivity() {
        return((Activity)getContext());
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

                Camera.Parameters params = getCameraParametersSync();

                params.setRotation(outputOrientation);
                try {
                    setCameraParametersSync(params);
                    currentOrientation = orientation;
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
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
        PictureTransaction xact=null;

        PictureTransactionCallback(PictureTransaction xact) {
            this.xact=xact;
        }

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            if (previewParams != null && camera != null) {
                try {
                    camera.setParameters(previewParams);
                } catch (RuntimeException e) {
                    Log.e(getClass().getSimpleName(), "Could not set camera parameters!", e);
                }
            }

            if (data != null) {
                new ImageCleanupTask(getContext(), data, cameraId, xact).start();
            }

            if (!xact.useSingleShotMode()) {
                startPreview();
            }
        }
    }
}