package com.htcrevive;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("revive", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", false);
    }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("enabled", on).commit();
    }

    /**
     * Loopback ports our servers listen on (iptables sends 80/443 for the revived hosts there).
     * Kept clear of the usual 8080/8443 dev ports, of Wolfius's 15000-25000 source-port band, and
     * of Linux's ephemeral range (32768+).
     */
    static final int DEFAULT_HTTP_PORT = 28080, DEFAULT_HTTPS_PORT = 28443;

    static int httpPort(Context c) {
        return sp(c).getInt("httpPort", DEFAULT_HTTP_PORT);
    }

    static int httpsPort(Context c) {
        return sp(c).getInt("httpsPort", DEFAULT_HTTPS_PORT);
    }

    static void setPorts(Context c, int http, int https) {
        sp(c).edit().putInt("httpPort", http).putInt("httpsPort", https).commit();
    }

    /** Ports the last successful apply.sh redirected to; 8080/8443 before ports were configurable. */
    static int appliedHttpPort(Context c) {
        return sp(c).getInt("appliedHttpPort", 8080);
    }

    static int appliedHttpsPort(Context c) {
        return sp(c).getInt("appliedHttpsPort", 8443);
    }

    static void setAppliedPorts(Context c, int http, int https) {
        sp(c).edit().putInt("appliedHttpPort", http).putInt("appliedHttpsPort", https).commit();
    }

    /** Newline separated RSS/Atom feed URLs, one BlinkFeed topic each: "Name|url". */
    static String feeds(Context c) {
        return sp(c).getString("feeds", FeedHandler.DEFAULT_FEEDS);
    }

    static void setFeeds(Context c, String feeds) {
        sp(c).edit().putString("feeds", feeds).commit();
    }

    /** The feeds as {name, url} pairs. */
    static java.util.List<String[]> sources(Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        for (String line : feeds(c).split("\n")) {
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            out.add(new String[]{line.substring(0, bar).trim(), line.substring(bar + 1).trim()});
        }
        return out;
    }

    static void setSources(Context c, java.util.List<String[]> sources) {
        StringBuilder sb = new StringBuilder();
        for (String[] s : sources) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s[0].replace('|', '/').replace('\n', ' ')).append('|').append(s[1].trim());
        }
        setFeeds(c, sb.toString());
    }
}
