package com.htcrevive;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * Android 2.x service: serves the old AccuWeather widget API and the Yahoo finance API on
 * 127.0.0.1 and keeps the root redirects in place. Everything the old HTC apps call is plain
 * HTTP, so unlike the Sense 5 build there is no local HTTPS server or CA.
 */
public class LegacyService extends Service {
    static volatile String status = "Off";

    private MiniHttp http;

    static boolean enabled(Context c) {
        return c.getSharedPreferences("revive", MODE_PRIVATE).getBoolean("enabled", false);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("revive", MODE_PRIVATE).edit().putBoolean("enabled", on).commit();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, notification());
        if (http == null) {
            // HttpURLConnection's keep-alive pool is unreliable before Android 2.3.
            System.setProperty("http.keepAlive", "false");
            try {
                com.htcrevive.tls.WolfClient.init(this);
            } catch (Throwable e) {
                // Weather still works without it; only the HTTPS stock lookups need TLS.
                Log.e(MiniHttp.TAG, "TLS client unavailable", e);
            }
            try {
                startServer();
                status = "Serving on 127.0.0.1:" + LegacyRoot.HTTP_PORT;
            } catch (Exception e) {
                Log.e(MiniHttp.TAG, "server start failed", e);
                status = "Server failed: " + e.getMessage();
                return START_STICKY;
            }
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    LegacyRoot.apply(LegacyService.this);
                    status = "Active (127.0.0.1:" + LegacyRoot.HTTP_PORT + ")";
                } catch (Exception e) {
                    Log.e(MiniHttp.TAG, "root setup failed", e);
                    status = "Root setup failed: " + e.getMessage();
                }
            }
        }, "root-setup").start();
        return START_STICKY;
    }

    private void startServer() throws Exception {
        final OldWeatherHandler weather = new OldWeatherHandler(this);
        final StockHandler stocks = new StockHandler();
        MiniHttp.Handler router = new MiniHttp.Handler() {
            public MiniHttp.Response handle(MiniHttp.Request req) throws Exception {
                if (req.host.equals("htc.accuweather.com")) return weather.handle(req);
                if (req.host.equals("api.htc.go.yahoo.com")) return stocks.handle(req);
                return MiniHttp.Response.notFound();
            }
        };
        ServerSocket plain = new ServerSocket(LegacyRoot.HTTP_PORT, 50, InetAddress.getByName("127.0.0.1"));
        plain.setReuseAddress(true);
        http = new MiniHttp(plain, router);
        http.start("revive-http");
    }

    @SuppressWarnings("deprecation")
    private Notification notification() {
        Notification n = new Notification(android.R.drawable.ic_menu_compass, null, System.currentTimeMillis());
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, LegacyActivity.class), 0);
        n.setLatestEventInfo(this, "HTC Revive", "Serving weather and stocks locally", open);
        n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        return n;
    }

    @Override
    public void onDestroy() {
        if (http != null) http.stop();
        http = null;
        status = "Off";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
