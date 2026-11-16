package com.tomato.tApp;

import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.Switch;

public class SettingsActivity extends PaperActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        bind(R.id.switchCamera, AppSettings.BUILTIN_CAMERA, AppSettings.builtinCamera(this));
        bind(R.id.switchAutoCrop, AppSettings.AUTO_CROP, AppSettings.autoCrop(this));
        bind(R.id.switchEdit, AppSettings.EDIT_AFTER_SCAN, AppSettings.editAfterScan(this));
        bind(R.id.switchQuality, AppSettings.HIGH_QUALITY,
                AppSettings.prefs(this).getBoolean(AppSettings.HIGH_QUALITY, true));
    }

    private void bind(int viewId, String key, boolean value) {
        Switch s = findViewById(viewId);
        s.setChecked(value);
        s.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                AppSettings.prefs(this).edit().putBoolean(key, checked).apply());
    }
}
