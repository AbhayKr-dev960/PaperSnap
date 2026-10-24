
package com.tomato.tApp;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.InputStream;
import java.io.OutputStream;

public class EditorActivity extends ComponentActivity {

    private ImageView imagePreview;
    private TextView modeLabel;
    private SeekBar brightnessBar;
    private SeekBar contrastBar;

    private Bitmap originalBitmap;
    private Bitmap editedBitmap;

    private String selectedMode = "Original";
    private float brightness = 0f;
    private float contrast = 1f;
    private int rotation = 0;

    private ActivityResultLauncher<String> createPdfLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        imagePreview = findViewById(R.id.imagePreview);
        modeLabel = findViewById(R.id.modeLabel);
        brightnessBar = findViewById(R.id.brightnessBar);
        contrastBar = findViewById(R.id.contrastBar);

        createPdfLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/pdf"),
                uri -> {
                    if (uri != null) {
                        exportPdf(uri);
                    }
                });

        String imageUriString = getIntent().getStringExtra("image_uri");

        if (imageUriString == null) {
            Toast.makeText(this, "No scanned page was received",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            Uri imageUri = Uri.parse(imageUriString);
            InputStream input = getContentResolver()
                    .openInputStream(imageUri);

            if (input == null) {
                throw new Exception("Unable to open scanned image");
            }

            originalBitmap = BitmapFactory.decodeStream(input);
            input.close();

            if (originalBitmap == null) {
                throw new Exception("Unable to decode scanned image");
            }

            editedBitmap = originalBitmap;
            renderImage();

        } catch (Exception e) {
            Toast.makeText(this, "Could not load scanned page",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        brightnessBar.setMax(200);
        brightnessBar.setProgress(100);

        contrastBar.setMax(200);
        contrastBar.setProgress(100);

        brightnessBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar bar, int progress, boolean fromUser) {
                        brightness = progress - 100;
                        renderImage();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                    }
                });

        contrastBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar bar, int progress, boolean fromUser) {
                        contrast = progress / 100f;
                        renderImage();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                    }
                });

        findViewById(R.id.originalButton).setOnClickListener(v -> {
            selectedMode = "Original";
            renderImage();
        });

        findViewById(R.id.grayButton).setOnClickListener(v -> {
            selectedMode = "Grayscale";
            renderImage();
        });

        findViewById(R.id.bwButton).setOnClickListener(v -> {
            selectedMode = "B&W";
            renderImage();
        });

        findViewById(R.id.enhanceButton).setOnClickListener(v -> {
            selectedMode = "Enhanced";
            renderImage();
        });

        findViewById(R.id.rotateButton).setOnClickListener(v -> {
            rotation = (rotation + 90) % 360;
            renderImage();
        });

        findViewById(R.id.resetButton).setOnClickListener(v -> resetEditor());

        findViewById(R.id.exportButton).setOnClickListener(v -> createPdfLauncher.launch("PaperSnap-Edited.pdf"));
    }

    private void renderImage() {
        if (originalBitmap == null)
            return;

        ColorMatrix matrix = new ColorMatrix();

        if (selectedMode.equals("Grayscale")) {
            matrix.setSaturation(0f);
        } else if (selectedMode.equals("B&W")) {
            matrix.setSaturation(0f);
            matrix.set(new float[] {
                    1.7f, 0, 0, 0, -90,
                    1.7f, 0, 0, 0, -90,
                    1.7f, 0, 0, 0, -90,
                    0, 0, 0, 1, 0
            });
        } else if (selectedMode.equals("Enhanced")) {
            matrix.setSaturation(0f);
            matrix.set(new float[] {
                    1.35f, 0, 0, 0, -25,
                    0, 1.35f, 0, 0, -25,
                    0, 0, 1.35f, 0, -25,
                    0, 0, 0, 1, 0
            });
        }

        float scale = contrast;
        float translate = (1f - scale) * 128f + brightness;

        ColorMatrix adjustments = new ColorMatrix(new float[] {
                scale, 0, 0, 0, translate,
                0, scale, 0, 0, translate,
                0, 0, scale, 0, translate,
                0, 0, 0, 1, 0
        });

        matrix.postConcat(adjustments);

        Bitmap output = Bitmap.createBitmap(
                originalBitmap.getWidth(),
                originalBitmap.getHeight(),
                Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(originalBitmap, 0, 0, paint);

        if (rotation != 0) {
            android.graphics.Matrix rotateMatrix = new android.graphics.Matrix();
            rotateMatrix.postRotate(rotation);

            Bitmap rotated = Bitmap.createBitmap(
                    output, 0, 0, output.getWidth(), output.getHeight(),
                    rotateMatrix, true);

            output.recycle();
            output = rotated;
        }

        if (editedBitmap != null && editedBitmap != originalBitmap) {
            editedBitmap.recycle();
        }

        editedBitmap = output;
        imagePreview.setImageBitmap(editedBitmap);
        modeLabel.setText(selectedMode);
    }

    private void resetEditor() {
        selectedMode = "Original";
        brightness = 0f;
        contrast = 1f;
        rotation = 0;

        brightnessBar.setProgress(100);
        contrastBar.setProgress(100);
        renderImage();
    }

    private void exportPdf(Uri destination) {
        if (editedBitmap == null)
            return;

        PdfDocument document = new PdfDocument();

        try {
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                    editedBitmap.getWidth(),
                    editedBitmap.getHeight(),
                    1).create();

            PdfDocument.Page page = document.startPage(pageInfo);
            page.getCanvas().drawColor(Color.WHITE);
            page.getCanvas().drawBitmap(editedBitmap, 0, 0, null);
            document.finishPage(page);

            OutputStream output = getContentResolver().openOutputStream(destination);

            if (output == null) {
                throw new Exception("Could not open destination");
            }

            document.writeTo(output);
            output.close();

            Toast.makeText(this, "Edited PDF saved",
                    Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this, "PDF export failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        } finally {
            document.close();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (editedBitmap != null && editedBitmap != originalBitmap) {
            editedBitmap.recycle();
        }

        if (originalBitmap != null && !originalBitmap.isRecycled()) {
            originalBitmap.recycle();
        }
    }
}
