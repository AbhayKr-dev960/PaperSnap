package com.tomato.tApp;

import android.view.View;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

/** Base screen: keeps content clear of the status/navigation bars (edge-to-edge on Android 15+). */
public abstract class PaperActivity extends ComponentActivity {

    @Override
    @SuppressWarnings("deprecation")
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    protected void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
