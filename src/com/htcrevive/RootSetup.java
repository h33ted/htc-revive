package com.htcrevive;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Redirects the dead HTC/AccuWeather hosts to this app: hosts entries, our CA in the user
 * trust store, and iptables redirects of loopback 80/443. The stock S-ON kernel discards /system
 * edits on reboot, so the service re-applies everything at every boot.
 */
final class RootSetup {
    private static final String USER_CA_DIR = "/data/misc/keychain/cacerts-added";

    static final String[] HOSTS = {
            "htc2.accu-weather.com", "htctablet.accu-weather.com",
            "geo-prism.htcsense.com", "img-prism.htcsense.com", "log-prism.htcsense.com",
            "api.htc.go.yahoo.com"};

    private RootSetup() {}

    /** Returns the combined script output; throws if su is unavailable or the script fails. */
    static String apply(Context ctx) throws IOException, InterruptedException {
        File dir = ctx.getFilesDir();
        copyAsset(ctx, "ca.pem", new File(dir, "ca.pem"));
        String hash = readAsset(ctx, "ca.hash").trim();
        StringBuilder hosts = new StringBuilder();
        for (String h : HOSTS) hosts.append("127.0.0.1 ").append(h).append("\\n");
        String f = dir.getAbsolutePath();
        int http = Prefs.httpPort(ctx), https = Prefs.httpsPort(ctx);
        // Superuser gives each su call its own mount namespace, so bind mounts would stay
        // invisible to other apps; edit /system in place instead.
        String script = "set -e\n"
                + "F=" + f + "\n"
                + "mount -o remount,rw /system\n"
                // Rewrite our block from the saved original each time, so hosts added in an
                // update reach a hosts file that already carries an older block.
                + "if grep -q htcrevive /system/etc/hosts; then\n"
                + "  if [ -f $F/hosts.orig ]; then cat $F/hosts.orig > /system/etc/hosts; fi\n"
                + "else\n"
                + "  cat /system/etc/hosts > $F/hosts.orig\n"
                + "fi\n"
                + "if ! grep -q htcrevive /system/etc/hosts; then\n"
                + "  printf '\\n# htcrevive\\n" + hosts + "' >> /system/etc/hosts\n"
                + "fi\n"
                + "mount -o remount,ro /system || true\n"
                // HTC's write protection refuses new files on /system (I/O error), so the CA goes
                // into the user-added store, which every app trusts on 4.x.
                + "D=" + USER_CA_DIR + "\n"
                + "[ -d $D ] || mkdir $D\n"
                + "chown system.system $D; chmod 755 $D\n"
                + "cat $F/ca.pem > $D/" + hash + ".0\n"
                + "chown system.system $D/" + hash + ".0; chmod 644 $D/" + hash + ".0\n"
                // Clear our rules for the current ports and for whatever was applied before a
                // port change, so no stale redirect points at a port we no longer serve.
                + "while " + rule("-D", 80, Prefs.appliedHttpPort(ctx)) + " 2>/dev/null; do :; done\n"
                + "while " + rule("-D", 443, Prefs.appliedHttpsPort(ctx)) + " 2>/dev/null; do :; done\n"
                + "while " + rule("-D", 80, http) + " 2>/dev/null; do :; done\n"
                + "while " + rule("-D", 443, https) + " 2>/dev/null; do :; done\n"
                + "while " + bypass("-D") + " 2>/dev/null; do :; done\n"
                + rule("-I", 80, http) + "\n"
                + rule("-I", 443, https) + "\n"
                // Our own upstream HTTPS must reach the real servers: a system-wide TLS proxy
                // such as Wolfius DNATs every :443 connection and re-signs it, which our
                // verifying client rightly rejects. Exempt this app's uid, ahead of such rules.
                + bypass("-I") + "\n"
                + "ndc resolver flushdefaultif >/dev/null 2>&1 || true\n"
                + "echo applied\n";
        String out = su(ctx, "apply.sh", script);
        Prefs.setAppliedPorts(ctx, http, https);
        return out;
    }

    static String remove(Context ctx) throws IOException, InterruptedException {
        String hash = readAsset(ctx, "ca.hash").trim();
        String f = ctx.getFilesDir().getAbsolutePath();
        String script = "mount -o remount,rw /system\n"
                + "[ -f " + f + "/hosts.orig ] && grep -q htcrevive /system/etc/hosts && cat " + f + "/hosts.orig > /system/etc/hosts\n"
                + "mount -o remount,ro /system\n"
                + "rm -f " + USER_CA_DIR + "/" + hash + ".0\n"
                + rule("-D", 80, Prefs.appliedHttpPort(ctx)) + " 2>/dev/null\n"
                + rule("-D", 443, Prefs.appliedHttpsPort(ctx)) + " 2>/dev/null\n"
                + bypass("-D") + " 2>/dev/null\n"
                + "ndc resolver flushdefaultif >/dev/null 2>&1\n"
                + "echo removed\n";
        return su(ctx, "remove.sh", script);
    }

    private static String rule(String op, int from, int to) {
        String pos = op.equals("-I") ? " 1" : "";
        return "iptables -t nat " + op + " OUTPUT" + pos + " -d 127.0.0.1 -p tcp --dport " + from
                + " -j REDIRECT --to-ports " + to;
    }

    private static String bypass(String op) {
        String pos = op.equals("-I") ? " 1" : "";
        return "iptables -t nat " + op + " OUTPUT" + pos + " -m owner --uid-owner " + android.os.Process.myUid()
                + " -p tcp ! -d 127.0.0.1 --dport 443 -j RETURN";
    }

    private static String su(Context ctx, String name, String script) throws IOException, InterruptedException {
        File f = new File(ctx.getFilesDir(), name);
        FileOutputStream out = new FileOutputStream(f);
        out.write(script.getBytes("UTF-8"));
        out.close();
        Process p = new ProcessBuilder("su", "-c", "sh " + f.getAbsolutePath()).redirectErrorStream(true).start();
        String output = new String(Net.readAll(p.getInputStream()), "UTF-8").trim();
        int rc = p.waitFor();
        Log.i(MiniHttp.TAG, name + " rc=" + rc + ": " + output);
        if (rc != 0) throw new IOException(name + " failed (" + rc + "): " + output);
        return output;
    }

    private static void copyAsset(Context ctx, String asset, File to) throws IOException {
        InputStream in = ctx.getAssets().open(asset);
        FileOutputStream out = new FileOutputStream(to);
        out.write(Net.readAll(in));
        out.close();
    }

    private static String readAsset(Context ctx, String asset) throws IOException {
        return new String(Net.readAll(ctx.getAssets().open(asset)), "UTF-8");
    }
}
