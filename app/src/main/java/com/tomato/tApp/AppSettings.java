package com.tomato.tApp;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {

    static final String EDIT_AFTER_SCAN = "edit_after_scan";
    static final String AUTO_CROP = "auto_crop";
    static final String HIGH_QUALITY = "high_quality";
    static final String BUILTIN_CAMERA = "builtin_camera";

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

    /** Use PaperSnap's own camera with the live page outline (otherwise the phone's camera app). */
    static boolean builtinCamera(Context c) {
        return prefs(c).getBoolean(BUILTIN_CAMERA, true);
    }

    static final int FLASH_OFF = 0;
    static final int FLASH_ON = 1;
    static final int FLASH_STAY_ON = 2;

    static int flashMode(Context c) {
        return prefs(c).getInt("flash_mode", FLASH_OFF);
    }

    static void setFlashMode(Context c, int mode) {
        prefs(c).edit().putInt("flash_mode", mode).apply();
    }

    static int jpegQuality(Context c) {
        return prefs(c).getBoolean(HIGH_QUALITY, true) ? 95 : 82;
    }
}
