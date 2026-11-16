package com.tomato.tApp;

import android.graphics.Bitmap;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional editor: filters, brightness, contrast, rotate. Only shown if enabled in Settings. */
public class EditorActivity extends PaperActivity {

    private static final String[] MODE_NAMES = {"Original", "Gray", "Black & white", "Enhance"};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String id;
    private ImageView preview;
    private TextView modeLabel;
    private SeekBar brightnessBar;
    private SeekBar contrastBar;
    private Bitmap src;
    private Bitmap shown;

    private int mode = ImageUtil.MODE_ORIGINAL;
    private float brightness = 0f;
    private float contrast = 1f;
    private int rotation = 0;

    private ActivityResultLauncher<Intent> cropLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        id = getIntent().getStringExtra("id");
        if (id == null) {
            finish();
            return;
        }

        preview = findViewById(R.id.imagePreview);
        modeLabel = findViewById(R.id.modeLabel);
        brightnessBar = findViewById(R.id.brightnessBar);
        contrastBar = findViewById(R.id.contrastBar);

        cropLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    if (r.getResultCode() == RESULT_OK) {
                        rotation = 0;
                        loadBase();
                    }
                });

        loadBase();

        brightnessBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            void changed(int progress) {
                brightness = progress - 100;
                updateFilter();
            }
        });
        contrastBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            void changed(int progress) {
                contrast = progress / 100f;
                updateFilter();
            }
        });

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.originalButton).setOnClickListener(v -> setMode(ImageUtil.MODE_ORIGINAL));
        findViewById(R.id.grayButton).setOnClickListener(v -> setMode(ImageUtil.MODE_GRAY));
        findViewById(R.id.bwButton).setOnClickListener(v -> setMode(ImageUtil.MODE_BW));
        findViewById(R.id.enhanceButton).setOnClickListener(v -> setMode(ImageUtil.MODE_ENHANCE));
        findViewById(R.id.rotateButton).setOnClickListener(v -> {
            rotation = (rotation + 90) % 360;
            rebuildPreview();
        });
        findViewById(R.id.cropButton).setOnClickListener(v ->
                cropLauncher.launch(new Intent(this, CropActivity.class).putExtra("id", id)));
        findViewById(R.id.resetButton).setOnClickListener(v -> {
            mode = ImageUtil.MODE_ORIGINAL;
            brightness = 0f;
            contrast = 1f;
            rotation = 0;
            brightnessBar.setProgress(100);
            contrastBar.setProgress(100);
            rebuildPreview();
        });
        findViewById(R.id.saveButton).setOnClickListener(v -> save());
    }

    private void loadBase() {
        Bitmap b = ImageUtil.decodeFile(PageStore.base(this, id), 1600);
        if (b == null) {
            toast("Could not load this page.");
            finish();
            return;
        }
        src = b;
        rebuildPreview();
    }

    private void rebuildPreview() {
        if (src == null) return;
        if (rotation % 360 == 0) {
            shown = src;
        } else {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            shown = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        }
        preview.setImageBitmap(shown);
        updateFilter();
    }

    private void setMode(int m) {
        mode = m;
        updateFilter();
    }

    private void updateFilter() {
        preview.setColorFilter(new ColorMatrixColorFilter(
                ImageUtil.matrix(mode, brightness, contrast)));
        modeLabel.setText(MODE_NAMES[mode]);
    }

    private void save() {
        final boolean untouched = mode == ImageUtil.MODE_ORIGINAL
                && brightness == 0f && contrast == 1f && rotation % 360 == 0;
        findViewById(R.id.saveButton).setEnabled(false);
        worker.execute(() -> {
            try {
                if (untouched) {
                    PageStore.fin(this, id).delete();
                } else {
                    Bitmap full = ImageUtil.decodeFile(PageStore.base(this, id), 4096);
                    if (full == null) throw new Exception("Could not read page");
                    Bitmap out = ImageUtil.applyAdjust(full, mode, brightness, contrast, rotation);
                    ImageUtil.saveJpeg(out, PageStore.fin(this, id), AppSettings.jpegQuality(this));
                    out.recycle();
                    full.recycle();
                }
                ui.post(() -> {
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    findViewById(R.id.saveButton).setEnabled(true);
                    toast("Could not save: " + t.getMessage());
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        abstract void changed(int progress);

        @Override
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            changed(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar bar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
        }
    }
}
