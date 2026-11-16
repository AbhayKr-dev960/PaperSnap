package com.tomato.tApp;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lets you drag the four corners of a page. Starts from the automatically detected edges. */
public class CropActivity extends PaperActivity {

    private static final float[] DEFAULT_QUAD = {0.08f, 0.08f, 0.92f, 0.08f, 0.92f, 0.92f, 0.08f, 0.92f};
    private static final float[] FULL_QUAD = {0.01f, 0.01f, 0.99f, 0.01f, 0.99f, 0.99f, 0.01f, 0.99f};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private CropView cropView;
    private Bitmap preview;
    private String id;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        id = getIntent().getStringExtra("id");
        if (id == null) {
            finish();
            return;
        }
        cropView = findViewById(R.id.cropView);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.resetButton).setOnClickListener(v -> cropView.setQuad(FULL_QUAD));
        findViewById(R.id.autoButton).setOnClickListener(v -> autoDetect(true));
        findViewById(R.id.doneButton).setOnClickListener(v -> apply());

        worker.execute(() -> {
            Bitmap b = ImageUtil.decodeFile(PageStore.orig(this, id), 1600);
            float[] q = PageStore.getQuad(this, id);
            if (b != null && q == null) q = Scanner.detect(b);
            final float[] quad = q != null ? q : DEFAULT_QUAD;
            ui.post(() -> {
                if (isDestroyed()) return;
                if (b == null) {
                    toast("Could not load the original photo.");
                    finish();
                    return;
                }
                preview = b;
                cropView.setImage(b, quad);
            });
        });
    }

    private void autoDetect(boolean notify) {
        if (preview == null) return;
        worker.execute(() -> {
            float[] q = Scanner.detect(preview);
            ui.post(() -> {
                if (isDestroyed()) return;
                if (q != null) {
                    cropView.setQuad(q);
                } else if (notify) {
                    toast("Couldn't find the page edges. Drag the corners by hand.");
                }
            });
        });
    }

    private void apply() {
        if (preview == null) return;
        final float[] quad = cropView.getQuad();
        findViewById(R.id.doneButton).setEnabled(false);
        worker.execute(() -> {
            try {
                Bitmap full = ImageUtil.decodeFile(PageStore.orig(this, id), 4000);
                if (full == null) throw new Exception("Could not read photo");
                Bitmap out = Scanner.warp(full, quad);
                ImageUtil.saveJpeg(out, PageStore.base(this, id), AppSettings.jpegQuality(this));
                PageStore.fin(this, id).delete(); // base changed, old filters no longer apply
                PageStore.saveQuad(this, id, quad);
                if (out != full) out.recycle();
                full.recycle();
                ui.post(() -> {
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    findViewById(R.id.doneButton).setEnabled(true);
                    toast("Could not crop: " + t.getMessage());
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }
}
