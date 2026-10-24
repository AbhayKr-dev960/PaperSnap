package com.tomato.tApp;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends ComponentActivity {

    private TextView statusText;
    private Uri originalPdfUri;
    private int scannedPageCount = 0;

    private ActivityResultLauncher<IntentSenderRequest> scannerLauncher;
    private ActivityResultLauncher<String> savePdfLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button scanButton = findViewById(R.id.scanButton);
        Button saveButton = findViewById(R.id.savePdfButton);
        Button shareButton = findViewById(R.id.sharePdfButton);
        statusText = findViewById(R.id.statusText);

        savePdfLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/pdf"),
                destination -> {
                    if (destination != null) {
                        copyOriginalPdf(destination);
                    }
                });

        scannerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK
                            || result.getData() == null) {
                        statusText.setText("Scan cancelled.");
                        return;
                    }

                    GmsDocumentScanningResult scanResult = GmsDocumentScanningResult
                            .fromActivityResultIntent(result.getData());

                    if (scanResult == null) {
                        statusText.setText("Could not read scan results.");
                        return;
                    }

                    if (scanResult.getPdf() != null) {
                        originalPdfUri = scanResult.getPdf().getUri();
                        scannedPageCount = scanResult.getPdf().getPageCount();
                    }

                    if (scanResult.getPages() != null
                            && !scanResult.getPages().isEmpty()) {

                        Uri firstPageUri = scanResult.getPages().get(0).getImageUri();

                        Intent editorIntent = new Intent(MainActivity.this,
                                EditorActivity.class);

                        editorIntent.putExtra(
                                "image_uri", firstPageUri.toString());

                        startActivity(editorIntent);

                        statusText.setText(
                                "Scanned " + scannedPageCount
                                        + " page(s). Editor opened.");
                    } else {
                        statusText.setText(
                                "No page images were returned.");
                    }
                });

        scanButton.setOnClickListener(v -> startScanner());

        saveButton.setOnClickListener(v -> {
            if (originalPdfUri == null) {
                showMessage("Scan a document first.");
                return;
            }

            savePdfLauncher.launch("PaperSnap-Original.pdf");
        });

        shareButton.setOnClickListener(v -> shareOriginalPdf());
    }

    private void startScanner() {
        GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(100)
                .setResultFormats(
                        GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                        GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(
                        GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build();

        GmsDocumentScanner scanner = GmsDocumentScanning.getClient(options);

        statusText.setText("Opening scanner...");

        scanner.getStartScanIntent(this)
                .addOnSuccessListener(intentSender -> {
                    IntentSenderRequest request = new IntentSenderRequest.Builder(intentSender)
                            .build();
                    scannerLauncher.launch(request);
                })
                .addOnFailureListener(error -> {
                    statusText.setText("Unable to start scanner.");
                    showMessage(error.getMessage() == null
                            ? "Scanner unavailable."
                            : error.getMessage());
                });
    }

    private void copyOriginalPdf(Uri destination) {
        try (InputStream input = getContentResolver().openInputStream(originalPdfUri);
                OutputStream output = getContentResolver().openOutputStream(destination)) {

            if (input == null || output == null) {
                throw new Exception("Could not open PDF file.");
            }

            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }

            statusText.setText("Original PDF saved.");
            showMessage("Original PDF saved.");

        } catch (Exception e) {
            showMessage("Could not save PDF: " + e.getMessage());
        }
    }

    private void shareOriginalPdf() {
        if (originalPdfUri == null) {
            showMessage("Scan a document first.");
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/pdf");
        shareIntent.putExtra(Intent.EXTRA_STREAM, originalPdfUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(
                shareIntent, "Share original PDF"));
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
