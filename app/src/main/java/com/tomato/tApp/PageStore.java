package com.tomato.tApp;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Keeps the pages of the current document on disk so they survive the app being closed.
 *
 * Every page has up to three files:
 *   orig  - the untouched photo (kept so the crop can be adjusted later)
 *   base  - the photo after cropping / straightening
 *   final - base + the optional filters from the editor
 */
final class PageStore {

    private static final String PREFS = "pages";
    private static final String KEY_IDS = "ids";
    private static final String KEY_QUAD = "quad_";

    private PageStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "pages");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File orig(Context c, String id) {
        return new File(dir(c), id + "_orig.jpg");
    }

    static File base(Context c, String id) {
        return new File(dir(c), id + "_base.jpg");
    }

    static File fin(Context c, String id) {
        return new File(dir(c), id + "_final.jpg");
    }

    /** The image that goes into the PDF. */
    static File current(Context c, String id) {
        File f = fin(c, id);
        return f.exists() ? f : base(c, id);
    }

    static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static synchronized List<String> ids(Context c) {
        String s = prefs(c).getString(KEY_IDS, "");
        List<String> list = new ArrayList<>();
        if (!TextUtils.isEmpty(s)) list.addAll(Arrays.asList(s.split(",")));
        return list;
    }

    private static void put(Context c, List<String> list) {
        prefs(c).edit().putString(KEY_IDS, TextUtils.join(",", list)).apply();
    }

    static synchronized void add(Context c, String id) {
        List<String> list = ids(c);
        list.add(id);
        put(c, list);
    }

    static synchronized void move(Context c, int from, int to) {
        List<String> list = ids(c);
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size()) return;
        String id = list.remove(from);
        list.add(to, id);
        put(c, list);
    }

    static synchronized void setOrder(Context c, List<String> order) {
        put(c, new ArrayList<>(order));
    }

    static synchronized void remove(Context c, String id) {
        List<String> list = ids(c);
        list.remove(id);
        put(c, list);
        deleteFiles(c, id);
    }

    static synchronized void clear(Context c) {
        for (String id : ids(c)) deleteFiles(c, id);
        put(c, new ArrayList<>());
    }

    static void deleteFiles(Context c, String id) {
        orig(c, id).delete();
        base(c, id).delete();
        fin(c, id).delete();
        prefs(c).edit().remove(KEY_QUAD + id).apply();
    }

    /** Crop corners as fractions of the image (0..1): tl, tr, br, bl -> x0,y0,x1,y1,... */
    static void saveQuad(Context c, String id, float[] q) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) sb.append(',');
            sb.append(q[i]);
        }
        prefs(c).edit().putString(KEY_QUAD + id, sb.toString()).apply();
    }

    static float[] getQuad(Context c, String id) {
        String s = prefs(c).getString(KEY_QUAD + id, null);
        if (s == null) return null;
        try {
            String[] p = s.split(",");
            if (p.length != 8) return null;
            float[] q = new float[8];
            for (int i = 0; i < 8; i++) q[i] = Float.parseFloat(p[i]);
            return q;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
