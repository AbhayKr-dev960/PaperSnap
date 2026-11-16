package com.tomato.tApp;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The current document: a list of pages you can add to, reorder, edit and export as one PDF. */
public class DocumentActivity extends PaperActivity {

    static final String EXTRA_ACTION = "action";
    static final String ACTION_SCAN = "scan";
    static final String ACTION_IMPORT = "import";

    private ListView list;
    private TextView status;
    private TextView empty;
    private TextView count;
    private PageAdapter adapter;

    private List<String> ids = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<>(48);
    private final ArrayDeque<String> editQueue = new ArrayDeque<>();
    private String pendingId;

    private ActivityResultLauncher<Uri> captureLauncher;
    private ActivityResultLauncher<String> importLauncher;
    private ActivityResultLauncher<String> savePdfLauncher;
    private ActivityResultLauncher<Intent> editLauncher;
    private ActivityResultLauncher<Intent> cropLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_document);

        list = findViewById(R.id.pageList);
        status = findViewById(R.id.statusText);
        empty = findViewById(R.id.emptyText);
        count = findViewById(R.id.countText);
        adapter = new PageAdapter();
        list.setAdapter(adapter);

        if (savedInstanceState != null) pendingId = savedInstanceState.getString("pendingId");

        // Opens the phone's own camera app; the photo comes back at full resolution.
        captureLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(), ok -> {
                    String id = pendingId;
                    pendingId = null;
                    if (id == null) return;
                    if (ok) {
                        process(Collections.singletonList(id), null);
                    } else {
                        PageStore.deleteFiles(this, id);
                    }
                });

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.GetMultipleContents(), uris -> {
                    if (uris == null || uris.isEmpty()) return;
                    List<String> newIds = new ArrayList<>();
                    for (int i = 0; i < uris.size(); i++) newIds.add(PageStore.newId());
                    process(newIds, uris);
                });

        savePdfLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/pdf"), uri -> {
                    if (uri != null) savePdfTo(uri);
                });

        editLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    reload();
                    if (r.getResultCode() == RESULT_OK) {
                        nextEdit();
                    } else {
                        editQueue.clear();
                    }
                });

        cropLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> reload());

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.clearButton).setOnClickListener(v -> confirmClear());
        findViewById(R.id.scanButton).setOnClickListener(v -> scanPage());
        findViewById(R.id.importButton).setOnClickListener(v -> importLauncher.launch("image/*"));
        findViewById(R.id.saveButton).setOnClickListener(v -> {
            if (ids.isEmpty()) {
                toast("Add a page first.");
                return;
            }
            String name = "PaperSnap-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                    .format(new Date()) + ".pdf";
            savePdfLauncher.launch(name);
        });
        findViewById(R.id.shareButton).setOnClickListener(v -> sharePdf());

        list.setOnItemClickListener((parent, view, position, itemId) -> showOptions(position));

        reload();

        if (savedInstanceState == null) {
            String action = getIntent().getStringExtra(EXTRA_ACTION);
            if (ACTION_SCAN.equals(action)) {
                scanPage();
            } else if (ACTION_IMPORT.equals(action)) {
                importLauncher.launch("image/*");
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("pendingId", pendingId);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
        thumbs.shutdownNow();
    }

    // ------------------------------------------------------------------ pages

    private void reload() {
        ids = PageStore.ids(this);
        adapter.notifyDataSetChanged();
        int n = ids.size();
        count.setText(n == 0 ? "" : n + (n == 1 ? " page" : " pages"));
        empty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.clearButton).setVisibility(n == 0 ? View.INVISIBLE : View.VISIBLE);
        findViewById(R.id.saveButton).setEnabled(n > 0);
        findViewById(R.id.shareButton).setEnabled(n > 0);
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void scanPage() {
        try {
            String id = PageStore.newId();
            File f = PageStore.orig(this, id);
            f.createNewFile();
            pendingId = id;
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            captureLauncher.launch(uri);
        } catch (Exception e) {
            pendingId = null;
            toast("Could not open the camera: " + e.getMessage());
        }
    }

    /** Copies imports (if any), crops, and adds the new pages. Runs off the main thread. */
    private void process(List<String> newIds, List<Uri> sources) {
        setStatus("Working…");
        final boolean editAfter = AppSettings.editAfterScan(this);
        final int total = newIds.size();

        worker.execute(() -> {
            List<String> done = new ArrayList<>();
            int failed = 0;
            for (int i = 0; i < total; i++) {
                final int step = i + 1;
                if (total > 1) ui.post(() -> setStatus("Page " + step + " of " + total + "…"));
                String id = newIds.get(i);
                try {
                    if (sources != null) copyToOrig(sources.get(i), PageStore.orig(this, id));
                    Scanner.processNew(this, id);
                    PageStore.add(this, id);
                    done.add(id);
                } catch (Throwable t) {
                    failed++;
                    PageStore.deleteFiles(this, id);
                }
            }
            final int ok = done.size();
            final int bad = failed;
            ui.post(() -> {
                if (isDestroyed()) return;
                reload();
                String msg = ok == 0 ? "" : "Added " + ok + (ok == 1 ? " page." : " pages.");
                if (bad > 0) msg += (msg.isEmpty() ? "" : " ") + bad + " could not be read.";
                setStatus(msg);
                list.post(() -> list.setSelection(adapter.getCount() - 1));
                if (editAfter && ok > 0) {
                    editQueue.addAll(done);
                    nextEdit();
                }
            });
        });
    }

    private void copyToOrig(Uri from, File to) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new Exception("Cannot open image");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        }
    }

    private void nextEdit() {
        String id = editQueue.poll();
        if (id == null) return;
        editLauncher.launch(new Intent(this, EditorActivity.class).putExtra("id", id));
    }

    private void showOptions(int pos) {
        if (pos < 0 || pos >= ids.size()) return;
        final String id = ids.get(pos);
        String[] items = {"Adjust crop", "Edit — filters & rotate", "Move up", "Move down", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle("Page " + (pos + 1))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            cropLauncher.launch(new Intent(this, CropActivity.class).putExtra("id", id));
                            break;
                        case 1:
                            editLauncher.launch(new Intent(this, EditorActivity.class).putExtra("id", id));
                            break;
                        case 2:
                            PageStore.move(this, pos, pos - 1);
                            reload();
                            break;
                        case 3:
                            PageStore.move(this, pos, pos + 1);
                            reload();
                            break;
                        case 4:
                            PageStore.remove(this, id);
                            reload();
                            break;
                    }
                })
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("Remove all pages from this document?")
                .setPositiveButton("Remove all", (d, w) -> {
                    PageStore.clear(this);
                    cache.evictAll();
                    reload();
                    setStatus("");
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    // -------------------------------------------------------------------- pdf

    private List<File> pageFiles() {
        List<File> files = new ArrayList<>();
        for (String id : ids) files.add(PageStore.current(this, id));
        return files;
    }

    private void savePdfTo(Uri uri) {
        final List<File> files = pageFiles();
        setStatus("Saving PDF…");
        worker.execute(() -> {
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new Exception("Cannot open destination");
                PdfWriter.write(files, os);
                ui.post(() -> setStatus("Saved " + files.size()
                        + (files.size() == 1 ? " page" : " pages") + " as PDF."));
            } catch (Exception e) {
                ui.post(() -> {
                    setStatus("");
                    toast("Could not save PDF: " + e.getMessage());
                });
            }
        });
    }

    private void sharePdf() {
        if (ids.isEmpty()) {
            toast("Add a page first.");
            return;
        }
        final List<File> files = pageFiles();
        setStatus("Preparing PDF…");
        worker.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "share");
                dir.mkdirs();
                File out = new File(dir, "PaperSnap.pdf");
                try (OutputStream os = new FileOutputStream(out)) {
                    PdfWriter.write(files, os);
                }
                ui.post(() -> {
                    setStatus("");
                    Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
                    Intent send = new Intent(Intent.ACTION_SEND)
                            .setType("application/pdf")
                            .putExtra(Intent.EXTRA_STREAM, u)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share PDF"));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    setStatus("");
                    toast("Could not create PDF: " + e.getMessage());
                });
            }
        });
    }

    // ---------------------------------------------------------------- adapter

    private class PageAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return ids.size();
        }

        @Override
        public Object getItem(int position) {
            return ids.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_page, parent, false);
            String id = ids.get(position);
            File f = PageStore.current(DocumentActivity.this, id);

            ((TextView) v.findViewById(R.id.pageTitle)).setText("Page " + (position + 1));

            ImageView iv = v.findViewById(R.id.pageThumb);
            final String key = f.getAbsolutePath() + f.lastModified();
            iv.setTag(key);
            Bitmap cached = cache.get(key);
            if (cached != null) {
                iv.setImageBitmap(cached);
            } else {
                iv.setImageDrawable(null);
                thumbs.execute(() -> {
                    Bitmap t = ImageUtil.decodeFile(f, 240);
                    if (t == null) return;
                    cache.put(key, t);
                    ui.post(() -> {
                        if (key.equals(iv.getTag())) iv.setImageBitmap(t);
                    });
                });
            }
            return v;
        }
    }
}
