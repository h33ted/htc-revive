package com.htcrevive;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

/**
 * Root setup for Android 2.x: hosts entries for the dead HTC hosts plus an iptables redirect of
 * loopback :80 to our server. These phones have no busybox and toolbox lacks grep/sed/cp, so the
 * new hosts file is built here in Java and the root script only uses cat, mount, chmod and
 * iptables. Every call runs the same "sh <file>" command, so Superuser's per-command "remember"
 * only has to be granted once.
 */
final class LegacyRoot {
    static final int HTTP_PORT = 28080;
    private static final String MARKER = "# htcrevive";
    private static final String HOSTS = "/system/etc/hosts";
    static final String[] HOSTNAMES = {"htc.accuweather.com", "api.htc.go.yahoo.com"};

    private LegacyRoot() {}

    static String apply(Context ctx) throws IOException, InterruptedException {
        File dir = ctx.getFilesDir();
        String current = read(HOSTS);
        int mark = current.indexOf(MARKER);
        String original = mark < 0 ? current : current.substring(0, mark);
        if (mark < 0) write(new File(dir, "hosts.orig"), original);
        StringBuilder hosts = new StringBuilder(original);
        if (hosts.length() > 0 && hosts.charAt(hosts.length() - 1) != '\n') hosts.append('\n');
        hosts.append(MARKER).append('\n');
        for (String h : HOSTNAMES) hosts.append("127.0.0.1 ").append(h).append('\n');
        File newHosts = new File(dir, "hosts.new");
        write(newHosts, hosts.toString());

        String script = remount("rw")
                + "cat " + newHosts.getAbsolutePath() + " > " + HOSTS + "\n"
                + "chmod 644 " + HOSTS + "\n"
                + remount("ro")
                + "while " + rule("-D") + " 2>/dev/null; do :; done\n"
                + rule("-I") + "\n"
                + "echo applied\n";
        return su(ctx, script);
    }

    static String remove(Context ctx) throws IOException, InterruptedException {
        File orig = new File(ctx.getFilesDir(), "hosts.orig");
        String script = (orig.exists()
                ? remount("rw") + "cat " + orig.getAbsolutePath() + " > " + HOSTS + "\n" + remount("ro")
                : "")
                + "while " + rule("-D") + " 2>/dev/null; do :; done\n"
                + "echo removed\n";
        return su(ctx, script);
    }

    private static String rule(String op) {
        String pos = op.equals("-I") ? " 1" : "";
        return "iptables -t nat " + op + " OUTPUT" + pos + " -d 127.0.0.1 -p tcp --dport 80 -j REDIRECT --to-ports " + HTTP_PORT;
    }

    /** 2.x toolbox mount needs the device and type spelled out; read them from /proc/mounts. */
    private static String remount(String mode) throws IOException {
        BufferedReader r = new BufferedReader(new FileReader("/proc/mounts"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(" ");
                if (f.length >= 3 && f[1].equals("/system")) {
                    return "mount -o remount," + mode + " -t " + f[2] + " " + f[0] + " /system\n";
                }
            }
        } finally {
            r.close();
        }
        throw new IOException("/system not found in /proc/mounts");
    }

    private static String su(Context ctx, String script) throws IOException, InterruptedException {
        File f = new File(ctx.getFilesDir(), "revive.sh");
        write(f, script);
        Process p = new ProcessBuilder("su", "-c", "sh " + f.getAbsolutePath()).redirectErrorStream(true).start();
        String output = new String(Net.readAll(p.getInputStream()), "UTF-8").trim();
        int rc = p.waitFor();
        Log.i(MiniHttp.TAG, "revive.sh rc=" + rc + ": " + output);
        if (rc != 0 || output.indexOf("Permission denied") >= 0) throw new IOException("root setup failed (" + rc + "): " + output);
        return output;
    }

    private static String read(String path) throws IOException {
        return new String(Net.readAll(new FileInputStream(path)), "ISO-8859-1");
    }

    private static void write(File f, String s) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes("ISO-8859-1"));
        out.close();
    }
}
