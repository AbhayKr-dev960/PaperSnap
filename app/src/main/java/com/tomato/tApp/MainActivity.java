package com.tomato.tApp;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView welcome = new TextView(this);
        welcome.setText("Welcome to Java App!\n\nBuilt with Java and Android.");
        welcome.setTextSize(24);
        welcome.setTextColor(Color.rgb(35, 45, 65));
        welcome.setGravity(Gravity.CENTER);
        welcome.setPadding(24, 24, 24, 24);

        setContentView(welcome);
    }
}