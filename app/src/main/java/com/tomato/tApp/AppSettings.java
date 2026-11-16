package com.tomato.tApp;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {

    static final String EDIT_AFTER_SCAN = "edit_after_scan";
    static final String AUTO_CROP = "auto_crop";
    static final String HIGH_QUALITY = "high_quality";

    private AppSettings() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Open the editor (filters, rotate) after every scan. Off by default. */
    static boolean editAfterScan(Context c) {
        return prefs(c).getBoolean(EDIT_AFTER_SCAN, false);
    }

    /** Find the page edges and straighten it automatically. On by default. */
    static boolean autoCrop(Context c) {
        return prefs(c).getBoolean(AUTO_CROP, true);
    }

    static int jpegQuality(Context c) {
        return prefs(c).getBoolean(HIGH_QUALITY, true) ? 95 : 82;
    }
}
