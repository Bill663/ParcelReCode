package com.parcelrecode.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

public class CameraCaptureActivity extends Activity {
    private static final String LOG_TAG = "ParcelCamera";

    private TextureView preview;
    private Button shutterButton;
    private Button flashButton;
    private Uri outputUri;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader imageReader;
    private String cameraId;
    private Size previewSize;
    private Size captureSize;
    private boolean hasFlash;
    private boolean flashOn = true;
    private boolean finishingCapture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        outputUri = getIntent().getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        if (outputUri == null) {
            finishWithFailure();
            return;
        }
        setContentView(createContentView());
    }

    private FrameLayout createContentView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new TextureView(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView hint = new TextView(this);
        hint.setText("Frame the parcel label");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(18);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(4, 0, 2, Color.BLACK);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        hintParams.topMargin = dp(78);
        hintParams.leftMargin = dp(24);
        hintParams.rightMargin = dp(24);
        root.addView(hint, hintParams);

        flashButton = new Button(this);
        flashButton.setAllCaps(false);
        flashButton.setTextColor(Color.WHITE);
        flashButton.setBackgroundColor(0x99000000);
        flashButton.setOnClickListener(view -> toggleFlash());
        updateFlashButton();
        FrameLayout.LayoutParams flashParams = new FrameLayout.LayoutParams(
                dp(132),
                dp(52),
                Gravity.TOP | Gravity.END
        );
        flashParams.topMargin = dp(22);
        flashParams.rightMargin = dp(16);
        root.addView(flashButton, flashParams);

        shutterButton = new Button(this);
        shutterButton.setText("Capture");
        shutterButton.setAllCaps(false);
        shutterButton.setTextSize(18);
        shutterButton.setTextColor(Color.WHITE);
        shutterButton.setBackgroundColor(getColor(R.color.brand));
        shutterButton.setOnClickListener(view -> captureStillPicture());
        FrameLayout.LayoutParams shutterParams = new FrameLayout.LayoutParams(
                dp(180),
                dp(64),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        shutterParams.bottomMargin = dp(34);
        root.addView(shutterButton, shutterParams);

        Button cancelButton = new Button(this);
        cancelButton.setText("Cancel");
        cancelButton.setAllCaps(false);
        cancelButton.setTextColor(Color.WHITE);
        cancelButton.setBackgroundColor(0x66000000);
        cancelButton.setOnClickListener(view -> finishWithFailure());
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(
                dp(120),
                dp(52),
                Gravity.TOP | Gravity.START
        );
        cancelParams.topMargin = dp(22);
        cancelParams.leftMargin = dp(16);
        root.addView(cancelButton, cancelParams);

        return root;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCameraThread();
        if (preview.isAvailable()) {
            openCamera();
        } else {
            preview.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
            };

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            finishWithFailure();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            finishWithFailure();
        }
    };

    private void startCameraThread() {
        cameraThread = new HandlerThread("parcel-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finishWithFailure();
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            configureCamera(manager);
            updateFlashButton();
            if (cameraId == null) {
                finishWithFailure();
                return;
            }
            imageReader = ImageReader.newInstance(
                    captureSize.getWidth(),
                    captureSize.getHeight(),
                    android.graphics.ImageFormat.JPEG,
                    1
            );
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    writeImage(bytes);
                    finishingCapture = true;
                    runOnUiThread(() -> {
                        setResult(RESULT_OK);
                        finish();
                    });
                } catch (Exception ignored) {
                    finishWithFailure();
                } finally {
                    if (image != null) image.close();
                }
            }, cameraHandler);
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (Exception ignored) {
            finishWithFailure();
        }
    }

    private void configureCamera(CameraManager manager) throws CameraAccessException {
        hasFlash = false;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                continue;
            }
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) continue;
            Size[] jpegSizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
            if (jpegSizes == null || jpegSizes.length == 0 || previewSizes == null || previewSizes.length == 0) {
                continue;
            }
            cameraId = id;
            Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = flashAvailable != null && flashAvailable;
            captureSize = chooseCaptureSize(jpegSizes);
            previewSize = choosePreviewSize(previewSizes);
            return;
        }
    }

    private Size chooseCaptureSize(Size[] sizes) {
        return Arrays.stream(sizes)
                .filter(size -> size.getWidth() <= 2560 && size.getHeight() <= 2560)
                .max(Comparator.comparingInt(size -> size.getWidth() * size.getHeight()))
                .orElseGet(() -> Arrays.stream(sizes)
                        .max(Comparator.comparingInt(size -> size.getWidth() * size.getHeight()))
                        .orElse(sizes[0]));
    }

    private Size choosePreviewSize(Size[] sizes) {
        return Arrays.stream(sizes)
                .filter(size -> size.getWidth() <= 1920 && size.getHeight() <= 1080)
                .max(Comparator.comparingInt(size -> size.getWidth() * size.getHeight()))
                .orElse(sizes[0]);
    }

    private void createPreviewSession() {
        if (cameraDevice == null || !preview.isAvailable() || imageReader == null) return;
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) return;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);
            previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyPreviewFlashMode(previewRequestBuilder);
            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(
                                        previewRequestBuilder.build(),
                                        null,
                                        cameraHandler);
                            } catch (CameraAccessException ignored) {
                                finishWithFailure();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            finishWithFailure();
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException ignored) {
            finishWithFailure();
        }
    }

    private void captureStillPicture() {
        if (cameraDevice == null || captureSession == null || imageReader == null || finishingCapture) return;
        shutterButton.setEnabled(false);
        try {
            CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyCaptureFlashMode(captureBuilder);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90);
            captureSession.stopRepeating();
            captureSession.capture(
                    captureBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(
                                CameraCaptureSession session,
                                CaptureRequest request,
                                TotalCaptureResult result
                        ) {
                            super.onCaptureCompleted(session, request, result);
                        }
                    },
                    cameraHandler
            );
        } catch (Exception ignored) {
            shutterButton.setEnabled(true);
            finishWithFailure();
        }
    }

    private void writeImage(byte[] bytes) throws IOException {
        try (OutputStream output = getContentResolver().openOutputStream(outputUri)) {
            if (output == null) throw new IOException("Could not open output URI");
            output.write(bytes);
        }
    }

    private void toggleFlash() {
        if (!hasFlash) return;
        flashOn = !flashOn;
        updateFlashButton();
    }

    private void applyPreviewFlashMode(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
    }

    private void applyCaptureFlashMode(CaptureRequest.Builder builder) {
        if (!hasFlash || !flashOn) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            return;
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
        builder.set(
                CaptureRequest.FLASH_MODE,
                CaptureRequest.FLASH_MODE_SINGLE);
    }

    private void updateFlashButton() {
        if (flashButton == null) return;
        flashButton.setEnabled(hasFlash);
        flashButton.setAlpha(hasFlash ? 1f : 0.55f);
        flashButton.setText(hasFlash
                ? flashOn ? "Flash on" : "Flash off"
                : "No flash");
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        previewRequestBuilder = null;
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void finishWithFailure() {
        if (finishingCapture || isFinishing()) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            setResult(RESULT_CANCELED);
            finish();
        } else {
            runOnUiThread(this::finishWithFailure);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
