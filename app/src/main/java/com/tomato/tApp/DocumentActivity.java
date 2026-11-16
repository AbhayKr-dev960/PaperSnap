package com.tomato.tApp;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

/** The current document: a list of pages you can add to, preview, reorder, edit and export as one PDF. */
public class DocumentActivity extends PaperActivity {

    static final String EXTRA_ACTION = "action";
    static final String ACTION_SCAN = "scan";
    static final String ACTION_IMPORT = "import";

    private RecyclerView list;
    private TextView status;
    private TextView empty;
    private PageAdapter adapter;

    private List<String> ids = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private final ExecutorService previewPool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<>(48);
    private final LruCache<String, Bitmap> previewCache = new LruCache<>(6);
    private final ArrayDeque<String> editQueue = new ArrayDeque<>();
    private String pendingId;

    private ActivityResultLauncher<Intent> cameraLauncher;
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
        adapter = new PageAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        attachDragToReorder();

        if (savedInstanceState != null) pendingId = savedInstanceState.getString("pendingId");

        // PaperSnap's own camera (live page outline). Returns the pages it captured.
        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    Intent data = r.getData();
                    if (r.getResultCode() != RESULT_OK || data == null) return;
                    ArrayList<String> got = data.getStringArrayListExtra(CameraActivity.RESULT_IDS);
                    if (got != null && !got.isEmpty()) process(got, null);
                });

        // Fallback: the phone's own camera app (if turned on in Settings).
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
        findViewById(R.id.previewCard).setOnClickListener(v -> openPreview(0));
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
        previewPool.shutdownNow();
    }

    // ------------------------------------------------------------------ pages

    private void reload() {
        ids = PageStore.ids(this);
        adapter.notifyDataSetChanged();
        int n = ids.size();
        empty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.clearButton).setVisibility(n == 0 ? View.INVISIBLE : View.VISIBLE);
        findViewById(R.id.saveButton).setEnabled(n > 0);
        findViewById(R.id.shareButton).setEnabled(n > 0);

        findViewById(R.id.reorderHint).setVisibility(n > 1 ? View.VISIBLE : View.GONE);

        View card = findViewById(R.id.previewCard);
        card.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        if (n > 0) {
            ((TextView) findViewById(R.id.previewSub)).setText(
                    n + (n == 1 ? " page" : " pages") + "  ·  tap to open");
            loadThumb(PageStore.current(this, ids.get(0)), findViewById(R.id.previewThumb));
        }
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void loadThumb(File f, ImageView iv) {
        final String key = f.getAbsolutePath() + f.lastModified();
        iv.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
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

    private void scanPage() {
        if (AppSettings.builtinCamera(this)) {
            cameraLauncher.launch(new Intent(this, CameraActivity.class));
            return;
        }
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
                list.post(() -> list.scrollToPosition(Math.max(0, adapter.getItemCount() - 1)));
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

    /** The ⋮ button on each page. */
    private void showMenu(View anchor, int pos) {
        if (pos < 0 || pos >= ids.size()) return;
        final String id = ids.get(pos);
        PopupMenu menu = new PopupMenu(this, anchor);
        Menu m = menu.getMenu();
        m.add(0, 1, 0, "Move up").setEnabled(pos > 0);
        m.add(0, 2, 1, "Move down").setEnabled(pos < ids.size() - 1);
        m.add(0, 3, 2, "Adjust crop");
        m.add(0, 4, 3, "Edit — filters & rotate");
        m.add(0, 5, 4, "Delete");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    PageStore.move(this, pos, pos - 1);
                    reload();
                    break;
                case 2:
                    PageStore.move(this, pos, pos + 1);
                    reload();
                    break;
                case 3:
                    cropLauncher.launch(new Intent(this, CropActivity.class).putExtra("id", id));
                    break;
                case 4:
                    editLauncher.launch(new Intent(this, EditorActivity.class).putExtra("id", id));
                    break;
                case 5:
                    PageStore.remove(this, id);
                    reload();
                    break;
            }
            return true;
        });
        menu.show();
    }

    /** Hold a page, then drag it up or down to reorder. */
    private void attachDragToReorder() {
        ItemTouchHelper helper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                    @Override
                    public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from,
                                          RecyclerView.ViewHolder to) {
                        int a = from.getBindingAdapterPosition();
                        int b = to.getBindingAdapterPosition();
                        if (a < 0 || b < 0 || a >= ids.size() || b >= ids.size()) return false;
                        String moved = ids.remove(a);
                        ids.add(b, moved);
                        adapter.notifyItemMoved(a, b);
                        return true;
                    }

                    @Override
                    public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
                    }

                    @Override
                    public void onSelectedChanged(RecyclerView.ViewHolder vh, int state) {
                        super.onSelectedChanged(vh, state);
                        if (state == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                            vh.itemView.setAlpha(0.85f);
                            vh.itemView.setScaleX(1.03f);
                            vh.itemView.setScaleY(1.03f);
                            vh.itemView.setElevation(12f);
                            vh.itemView.setBackgroundColor(getResources().getColor(R.color.paper));
                        }
                    }

                    @Override
                    public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
                        super.clearView(rv, vh);
                        vh.itemView.setAlpha(1f);
                        vh.itemView.setScaleX(1f);
                        vh.itemView.setScaleY(1f);
                        vh.itemView.setElevation(0f);
                        vh.itemView.setBackgroundResource(R.drawable.bg_rule);
                        PageStore.setOrder(DocumentActivity.this, ids);
                        rv.post(() -> adapter.notifyDataSetChanged()); // refresh "Page n" labels
                    }
                });
        helper.attachToRecyclerView(list);
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("Remove all pages from this document?")
                .setPositiveButton("Remove all", (d, w) -> {
                    PageStore.clear(this);
                    cache.evictAll();
                    previewCache.evictAll();
                    reload();
                    setStatus("");
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    // ---------------------------------------------------------------- preview

    /** Full-screen popup showing every page exactly as it will appear in the PDF. */
    @SuppressWarnings("deprecation")
    private void openPreview(int start) {
        if (ids.isEmpty()) return;
        final List<File> files = pageFiles();

        Dialog d = new Dialog(this, R.style.PreviewDialog);
        d.setContentView(R.layout.dialog_preview);
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }

        View root = d.findViewById(R.id.previewRoot);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        int n = files.size();
        ((TextView) d.findViewById(R.id.previewTitle))
                .setText("Preview  ·  " + n + (n == 1 ? " page" : " pages"));
        ListView pv = d.findViewById(R.id.previewList);
        pv.setAdapter(new PreviewAdapter(files));
        pv.setSelection(Math.max(0, Math.min(start, n - 1)));
        d.findViewById(R.id.closeButton).setOnClickListener(v -> d.dismiss());
        d.show();
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

    // ---------------------------------------------------------------- adapters

    private class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView title;
            final View preview;
            final View more;

            Holder(View v) {
                super(v);
                thumb = v.findViewById(R.id.pageThumb);
                title = v.findViewById(R.id.pageTitle);
                preview = v.findViewById(R.id.previewButton);
                more = v.findViewById(R.id.moreButton);
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(getLayoutInflater().inflate(R.layout.item_page, parent, false));
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            h.title.setText("Page " + (position + 1));
            loadThumb(PageStore.current(DocumentActivity.this, ids.get(position)), h.thumb);
            View.OnClickListener open = v -> openPreview(h.getBindingAdapterPosition());
            h.itemView.setOnClickListener(open);
            h.preview.setOnClickListener(open);
            h.more.setOnClickListener(v -> showMenu(v, h.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return ids.size();
        }
    }

    private class PreviewAdapter extends BaseAdapter {
        private final List<File> files;

        PreviewAdapter(List<File> files) {
            this.files = files;
        }

        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public Object getItem(int position) {
            return files.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_preview, parent, false);
            ImageView iv = v.findViewById(R.id.previewImage);
            ((TextView) v.findViewById(R.id.previewCaption))
                    .setText((position + 1) + " / " + files.size());

            File f = files.get(position);

            // Reserve the right height up front so the list doesn't jump while pages load.
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
            ViewGroup.LayoutParams lp = iv.getLayoutParams();
            lp.height = o.outWidth > 0 ? Math.round(w * (float) o.outHeight / o.outWidth) : w;
            iv.setLayoutParams(lp);

            final String key = f.getAbsolutePath() + f.lastModified();
            iv.setTag(key);
            Bitmap cached = previewCache.get(key);
            if (cached != null) {
                iv.setImageBitmap(cached);
            } else {
                iv.setImageDrawable(null);
                previewPool.execute(() -> {
                    Bitmap t = ImageUtil.decodeFile(f, 1400);
                    if (t == null) return;
                    previewCache.put(key, t);
                    ui.post(() -> {
                        if (key.equals(iv.getTag())) iv.setImageBitmap(t);
                    });
                });
            }
            return v;
        }
    }
}
