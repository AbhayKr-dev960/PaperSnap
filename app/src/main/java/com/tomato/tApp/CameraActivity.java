package com.tomato.tApp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PaperSnap's own camera. Shows a live outline of the page it detects, and keeps
 * taking pages until you press Done. Returns the new page ids to the caller.
 */
public class CameraActivity extends PaperActivity {

    static final String RESULT_IDS = "ids";

    private PreviewView previewView;
    private ScanOverlayView overlay;
    private TextView hint;
    private TextView countText;
    private TextView flashButton;
    private View shutter;
    private View shutterFlash;

    private ImageCapture imageCapture;
    private Camera camera;
    private boolean flashOn = false;
    private boolean capturing = false;

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<String> captured = new ArrayList<>();

    private float[] smoothed;       // main thread only
    private volatile float[] liveQuad;
    private int misses = 0;
    private long lastRun = 0;

    private ActivityResultLauncher<String> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewView = findViewById(R.id.previewView);
        overlay = findViewById(R.id.overlay);
        hint = findViewById(R.id.hintText);
        countText = findViewById(R.id.countText);
        flashButton = findViewById(R.id.flashButton);
        shutter = findViewById(R.id.shutterButton);
        shutterFlash = findViewById(R.id.shutterFlash);

        if (savedInstanceState != null) {
            ArrayList<String> saved = savedInstanceState.getStringArrayList("captured");
            if (saved != null) captured.addAll(saved);
        }
        updateCount();

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        startCamera();
                    } else {
                        toast("PaperSnap needs camera access to scan. You can still import images.");
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                });

        shutter.setOnClickListener(v -> capture());
        findViewById(R.id.doneButton).setOnClickListener(v -> finishWithResult());
        flashButton.setOnClickListener(v -> {
            flashOn = !flashOn;
            flashButton.setText(flashOn ? "flash on" : "flash off");
            if (imageCapture != null) {
                imageCapture.setFlashMode(flashOn
                        ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
            }
        });

        // Tap the picture to focus there.
        previewView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP && camera != null) {
                MeteringPoint p = previewView.getMeteringPointFactory()
                        .createPoint(e.getX(), e.getY());
                camera.getCameraControl().startFocusAndMetering(
                        new FocusMeteringAction.Builder(p).build());
                v.performClick();
            }
            return true;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishWithResult();
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            previewView.post(this::startCamera);
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList("captured", captured);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analysisExecutor.shutdown();
    }

    // ---------------------------------------------------------------- camera

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                bind(future.get());
            } catch (Exception e) {
                toast("Could not start the camera: " + e.getMessage());
                setResult(RESULT_CANCELED);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bind(ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashOn ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF)
                .build();

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyze);

        // The view port makes preview, photo and analysis frames show exactly the same area,
        // so the outline on screen lines up with the photo that gets saved.
        UseCaseGroup.Builder group = new UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture)
                .addUseCase(analysis);
        ViewPort viewPort = previewView.getViewPort();
        if (viewPort != null) group.setViewPort(viewPort);

        provider.unbindAll();
        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group.build());
    }

    // ------------------------------------------------------------- detection

    private void analyze(@NonNull ImageProxy image) {
        Mat gray = null;
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastRun < 150) return;
            lastRun = now;

            gray = grayFrom(image);
            final float[] q = Scanner.detectGray(gray);
            ui.post(() -> updateLive(q));
        } catch (Throwable ignored) {
        } finally {
            if (gray != null) gray.release();
            image.close();
        }
    }

    /** Brightness plane of the camera frame, cropped to the visible area and turned upright. */
    private Mat grayFrom(ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int stride = plane.getRowStride();
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);

        Mat full = new Mat(h, w, CvType.CV_8UC1);
        if (stride == w) {
            full.put(0, 0, data);
        } else {
            byte[] row = new byte[w];
            for (int r = 0; r < h; r++) {
                System.arraycopy(data, r * stride, row, 0, w);
                full.put(r, 0, row);
            }
        }

        android.graphics.Rect cr = image.getCropRect();
        Mat cropped = full;
        if (cr != null && cr.width() > 0 && cr.height() > 0
                && (cr.width() != w || cr.height() != h)) {
            cropped = new Mat(full, new org.opencv.core.Rect(cr.left, cr.top, cr.width(), cr.height()));
        }

        Mat out = new Mat();
        switch (image.getImageInfo().getRotationDegrees()) {
            case 90:
                Core.rotate(cropped, out, Core.ROTATE_90_CLOCKWISE);
                break;
            case 180:
                Core.rotate(cropped, out, Core.ROTATE_180);
                break;
            case 270:
                Core.rotate(cropped, out, Core.ROTATE_90_COUNTERCLOCKWISE);
                break;
            default:
                cropped.copyTo(out);
                break;
        }
        if (cropped != full) cropped.release();
        full.release();
        return out;
    }

    private void updateLive(float[] q) {
        if (q != null) {
            misses = 0;
            if (smoothed != null && maxDelta(q, smoothed) < 0.05f) {
                for (int i = 0; i < 8; i++) smoothed[i] = smoothed[i] * 0.4f + q[i] * 0.6f;
            } else {
                smoothed = q.clone();
            }
        } else if (++misses >= 4) {
            smoothed = null;
        }
        liveQuad = smoothed == null ? null : smoothed.clone();
        overlay.setQuad(smoothed);
        hint.setText(smoothed == null ? "Looking for the page…" : "Page found — tap the shutter");
    }

    private static float maxDelta(float[] a, float[] b) {
        float m = 0;
        for (int i = 0; i < 8; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    // --------------------------------------------------------------- capture

    private void capture() {
        if (capturing || imageCapture == null) return;
        capturing = true;
        shutter.setEnabled(false);

        final String id = PageStore.newId();
        final File file = PageStore.orig(this, id);
        final float[] quad = liveQuad; // what the user saw on screen

        imageCapture.takePicture(
                new ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        if (quad != null && AppSettings.autoCrop(CameraActivity.this)) {
                            PageStore.saveQuad(CameraActivity.this, id, quad);
                        }
                        captured.add(id);
                        updateCount();
                        blink();
                        capturing = false;
                        shutter.setEnabled(true);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        file.delete();
                        toast("Could not take the photo: " + e.getMessage());
                        capturing = false;
                        shutter.setEnabled(true);
                    }
                });
    }

    private void blink() {
        shutterFlash.setAlpha(0.8f);
        shutterFlash.animate().alpha(0f).setDuration(220).start();
    }

    private void updateCount() {
        int n = captured.size();
        countText.setText(n == 0 ? "no pages yet" : n + (n == 1 ? " page" : " pages"));
        ((TextView) findViewById(R.id.doneButton)).setText(n == 0 ? "Close" : "Done");
    }

    private void finishWithResult() {
        Intent data = new Intent().putStringArrayListExtra(RESULT_IDS, captured);
        setResult(captured.isEmpty() ? RESULT_CANCELED : RESULT_OK, data);
        finish();
    }
}
