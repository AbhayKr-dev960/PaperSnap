package com.tomato.tApp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public class MainActivity extends PaperActivity {

    private TextView continueRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        continueRow = findViewById(R.id.rowContinue);

        findViewById(R.id.rowScan).setOnClickListener(v -> open(DocumentActivity.ACTION_SCAN));
        findViewById(R.id.rowImport).setOnClickListener(v -> open(DocumentActivity.ACTION_IMPORT));
        continueRow.setOnClickListener(v -> open(null));
        findViewById(R.id.rowSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.rowAbout).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        int n = PageStore.ids(this).size();
        continueRow.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        continueRow.setText("Continue document  ·  " + n + (n == 1 ? " page" : " pages"));
    }

    private void open(String action) {
        Intent i = new Intent(this, DocumentActivity.class);
        if (action != null) i.putExtra(DocumentActivity.EXTRA_ACTION, action);
        startActivity(i);
    }
}
