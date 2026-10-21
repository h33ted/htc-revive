package com.htcrevive;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.htc.widget.ActionBarExt;
import com.htc.widget.ActionBarText;
import com.htc.widget.HtcAlertDialog;
import com.htc.widget.HtcEditText;
import com.htc.widget.HtcListItemSeparator;

import java.util.List;

/**
 * Sense-styled settings screen: HtcDeviceDefault theme and HTC action bar from the system's
 * HTCCommonctrl framework, a service switch, and the BlinkFeed sources as a Name / Feed URL table
 * (+ in the action bar adds one, double-tapping a row removes it). The request log lives in the
 * overflow menu.
 */
public class MainActivity extends Activity {
    private static final int MENU_ADD = 1, MENU_LOG = 2, MENU_PORTS = 3, MENU_DEFAULTS = 4;
    private static final long DOUBLE_TAP_MS = 400;
    /** Text colours for Sense's light content background (common_app_bkg). */
    private static final int TEXT_PRIMARY = 0xFF252525, TEXT_SECONDARY = 0xFF707070;

    private final Handler ui = new Handler();
    private ActionBarText title;
    private TextView statusView;
    private CheckBox serviceSwitch;
    private LinearLayout sourceRows;
    private float dp;

    private final Runnable refresh = new Runnable() {
        public void run() {
            boolean on = Prefs.enabled(MainActivity.this);
            String status = on ? ReviveService.status : "Off";
            statusView.setText(status);
            title.setSecondaryText(on ? "Weather, BlinkFeed and Stocks" : "Off");
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        int theme = getResources().getIdentifier("HtcDeviceDefault", "style", "com.htc");
        if (theme != 0) setTheme(theme);
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        // Like HTC's own apps (e.g. Stocks), use the light Sense content background.
        int bkg = getResources().getIdentifier("common_app_bkg", "drawable", "com.htc");
        if (bkg != 0) getWindow().setBackgroundDrawableResource(bkg);
        // After an app update the service isn't running yet; bring it back if it was on.
        if (Prefs.enabled(this)) startService(new Intent(this, ReviveService.class));

        ActionBarExt bar = new ActionBarExt(this, getActionBar());
        title = new ActionBarText(this);
        title.setPrimaryText("HTC Revive");
        bar.getCustomContainer().addCenterView(title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(separator("Service"));
        root.addView(serviceRow());

        root.addView(separator("BlinkFeed sources"));
        root.addView(sourceHeader());
        sourceRows = new LinearLayout(this);
        sourceRows.setOrientation(LinearLayout.VERTICAL);
        root.addView(sourceRows);
        TextView hint = text("Tap + to add a source. Double-tap a source to remove it. After changes, "
                + "re-pick topics in BlinkFeed's settings.", 13, false);
        hint.setPadding(px(16), px(12), px(16), px(16));
        hint.setTextColor(TEXT_SECONDARY);
        root.addView(hint);
        showSources();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ---- service ----------------------------------------------------------------------------

    private View serviceRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(72));
        row.setPadding(px(16), px(8), px(8), px(8));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text("Revive HTC services", 18, false));
        statusView = text("", 13, false);
        statusView.setTextColor(TEXT_SECONDARY);
        texts.addView(statusView);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        serviceSwitch = new CheckBox(this);
        serviceSwitch.setChecked(Prefs.enabled(this));
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
        return row;
    }

    private void setServiceEnabled(boolean on) {
        Prefs.setEnabled(this, on);
        if (on) {
            startService(new Intent(this, ReviveService.class));
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    RootSetup.remove(MainActivity.this);
                } catch (Exception ignored) {
                }
            }
        }).start();
        stopService(new Intent(this, ReviveService.class));
    }

    // ---- sources table ----------------------------------------------------------------------

    private View sourceHeader() {
        TextView name = text("NAME", 12, true), url = text("FEED URL", 12, true);
        name.setTextColor(TEXT_SECONDARY);
        url.setTextColor(TEXT_SECONDARY);
        return twoColumns(name, url, px(36));
    }

    private void showSources() {
        sourceRows.removeAllViews();
        List<String[]> sources = Prefs.sources(this);
        for (int i = 0; i < sources.size(); i++) {
            final int index = i;
            String[] s = sources.get(i);
            TextView name = text(s[0], 16, false);
            TextView url = text(s[1], 13, false);
            url.setSingleLine(true);
            url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            url.setTextColor(TEXT_SECONDARY);
            View row = twoColumns(name, url, px(60));
            row.setBackgroundResource(selectableBackground());
            row.setPadding(px(16), 0, px(16), 0);  // setBackgroundResource resets padding
            row.setOnClickListener(new View.OnClickListener() {
                private long lastTap;

                public void onClick(View v) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastTap < DOUBLE_TAP_MS) {
                        removeSource(index);
                    } else {
                        lastTap = now;
                    }
                }
            });
            sourceRows.addView(divider());
            sourceRows.addView(row);
        }
        sourceRows.addView(divider());
    }

    private void removeSource(int index) {
        List<String[]> sources = Prefs.sources(this);
        if (index >= sources.size()) return;
        String[] gone = sources.remove(index);
        Prefs.setSources(this, sources);
        showSources();
        Toast.makeText(this, "Removed " + gone[0], Toast.LENGTH_SHORT).show();
    }

    private void addSourceDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(px(16), px(8), px(16), 0);
        final HtcEditText name = new HtcEditText(this);
        name.setHint("Name, e.g. BBC Sport");
        name.setSingleLine(true);
        final HtcEditText url = new HtcEditText(this);
        url.setHint("RSS or Atom feed URL");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(name);
        form.addView(url);

        new HtcAlertDialog.Builder(this)
                .setTitle("Add source")
                .setView(form)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String n = name.getText().toString().trim(), u = url.getText().toString().trim();
                        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                        if (n.length() == 0 || u.length() <= "https://".length()) {
                            Toast.makeText(MainActivity.this, "Enter a name and a feed URL", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        List<String[]> sources = Prefs.sources(MainActivity.this);
                        sources.add(new String[]{n, u});
                        Prefs.setSources(MainActivity.this, sources);
                        showSources();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- menu -------------------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem add = menu.add(0, MENU_ADD, 0, "Add source");
        int plus = getResources().getIdentifier("icon_btn_add_dark", "drawable", "com.htc");
        add.setIcon(plus != 0 ? plus : android.R.drawable.ic_menu_add);
        add.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_LOG, 1, "Request log");
        menu.add(0, MENU_PORTS, 2, "Ports");
        menu.add(0, MENU_DEFAULTS, 3, "Restore default sources");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ADD:
                addSourceDialog();
                return true;
            case MENU_LOG:
                showLog();
                return true;
            case MENU_PORTS:
                portsDialog();
                return true;
            case MENU_DEFAULTS:
                Prefs.setFeeds(this, FeedHandler.DEFAULT_FEEDS);
                showSources();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void portsDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(px(16), px(8), px(16), 0);
        TextView note = text("Local ports the revived HTC traffic is redirected to. Change them if "
                + "another app already uses these.", 14, false);
        note.setTextColor(TEXT_SECONDARY);
        form.addView(note);
        final HtcEditText http = portField("HTTP port", Prefs.httpPort(this));
        final HtcEditText https = portField("HTTPS port", Prefs.httpsPort(this));
        form.addView(http);
        form.addView(https);

        new HtcAlertDialog.Builder(this)
                .setTitle("Ports")
                .setView(form)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int h = parsePort(http), s = parsePort(https);
                        if (h < 0 || s < 0 || h == s) {
                            Toast.makeText(MainActivity.this, "Use two different ports between 1024 and 65535",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        setPorts(h, s);
                    }
                })
                .setNegativeButton("Defaults", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        setPorts(Prefs.DEFAULT_HTTP_PORT, Prefs.DEFAULT_HTTPS_PORT);
                    }
                })
                .show();
    }

    private HtcEditText portField(String hint, int value) {
        HtcEditText e = new HtcEditText(this);
        e.setHint(hint);
        e.setText(String.valueOf(value));
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private static int parsePort(TextView field) {
        try {
            int p = Integer.parseInt(field.getText().toString().trim());
            return p >= 1024 && p <= 65535 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Saves the ports and restarts the service so it listens and redirects on the new ones. */
    private void setPorts(int http, int https) {
        if (http == Prefs.httpPort(this) && https == Prefs.httpsPort(this)) return;
        Prefs.setPorts(this, http, https);
        if (Prefs.enabled(this)) {
            stopService(new Intent(this, ReviveService.class));
            startService(new Intent(this, ReviveService.class));
        }
        Toast.makeText(this, "Now using " + http + " / " + https, Toast.LENGTH_SHORT).show();
    }

    private void showLog() {
        String log = RequestLog.dump();
        TextView body = text(log.length() == 0 ? "No requests yet." : log, 11, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(px(16), px(8), px(16), px(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new HtcAlertDialog.Builder(this)
                .setTitle("Request log")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    // ---- view helpers -----------------------------------------------------------------------

    private View separator(String label) {
        HtcListItemSeparator sep = new HtcListItemSeparator(this);
        sep.setText(0, label);
        return sep;
    }

    private View twoColumns(TextView left, TextView right, int minHeight) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(minHeight);
        row.setPadding(px(16), 0, px(16), 0);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        right.setPadding(px(12), 0, 0, 0);
        row.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3));
        return row;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x22000000);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, px(1) / 2)));
        return v;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(TEXT_PRIMARY);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int selectableBackground() {
        TypedValue v = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, v, true);
        return v.resourceId;
    }

    private int px(int dips) {
        return (int) (dips * dp + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        serviceSwitch.setChecked(Prefs.enabled(this));
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }
}
