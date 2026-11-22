package com.htcrevive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Settings screen for Android 2.x, in the phone's own theme: a service switch and status, what
 * gets revived, and the request log under the Menu key.
 */
public class LegacyActivity extends Activity {
    private static final int MENU_LOG = 1;

    private final Handler ui = new Handler();
    private TextView statusView;
    private CheckBox serviceSwitch;
    private float dp;

    private final Runnable refresh = new Runnable() {
        public void run() {
            statusView.setText(LegacyService.enabled(LegacyActivity.this) ? LegacyService.status : "Off");
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        if (LegacyService.enabled(this)) startService(new Intent(this, LegacyService.class));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(12), px(12), px(12), px(12));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(64));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Revive HTC services");
        title.setTextSize(20);
        texts.addView(title);
        statusView = new TextView(this);
        statusView.setTextSize(14);
        texts.addView(statusView);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        serviceSwitch = new CheckBox(this);
        serviceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                setServiceEnabled(on);
            }
        });
        row.addView(serviceSwitch);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                serviceSwitch.toggle();
            }
        });
        root.addView(row);

        TextView about = new TextView(this);
        about.setText("Brings back the HTC Weather app and widget (data from Open-Meteo) and the "
                + "Stocks app and widget (Yahoo Finance). Needs root.\n\n"
                + "Press Menu for the request log.");
        about.setTextSize(14);
        about.setPadding(0, px(16), 0, 0);
        root.addView(about);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void setServiceEnabled(boolean on) {
        if (!on && !LegacyService.enabled(this)) return;  // onResume syncing an already-off switch
        LegacyService.setEnabled(this, on);
        if (on) {
            startService(new Intent(this, LegacyService.class));
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    LegacyRoot.remove(LegacyActivity.this);
                } catch (Exception ignored) {
                }
            }
        }).start();
        stopService(new Intent(this, LegacyService.class));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_LOG, 0, "Request log").setIcon(android.R.drawable.ic_menu_info_details);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() != MENU_LOG) return super.onOptionsItemSelected(item);
        String log = RequestLog.dump();
        TextView body = new TextView(this);
        body.setText(log.length() == 0 ? "No requests yet." : log);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(11);
        body.setPadding(px(8), px(8), px(8), px(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Request log").setView(scroll).setPositiveButton("Close", null).show();
        return true;
    }

    private int px(int dips) {
        return (int) (dips * dp + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        serviceSwitch.setChecked(LegacyService.enabled(this));
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }
}
