package com.htcrevive;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/** Hosts the local AccuWeather/Prism stand-ins and keeps the root redirects in place. */
public class ReviveService extends Service {
    static volatile String status = "stopped";

    private MiniHttp http, https;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, notification());
        if (http == null) {
            try {
                com.htcrevive.tls.WolfClient.init(this);
                startServers();
                status = "servers listening on 127.0.0.1:" + Prefs.httpPort(ReviveService.this) + "/" + Prefs.httpsPort(ReviveService.this);
            } catch (Exception e) {
                Log.e(MiniHttp.TAG, "server start failed", e);
                status = "server start failed: " + e;
                return START_STICKY;
            }
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    RootSetup.apply(ReviveService.this);
                    status = "active, redirects applied (127.0.0.1:" + Prefs.httpPort(ReviveService.this) + "/" + Prefs.httpsPort(ReviveService.this) + ")";
                } catch (Exception e) {
                    Log.e(MiniHttp.TAG, "root setup failed", e);
                    status = "root setup failed: " + e.getMessage();
                }
            }
        }, "root-setup").start();
        return START_STICKY;
    }

    private void startServers() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        final WeatherHandler weather = new WeatherHandler(this);
        final FeedHandler feed = new FeedHandler(this);
        final StockHandler stocks = new StockHandler();
        MiniHttp.Handler router = new MiniHttp.Handler() {
            public MiniHttp.Response handle(MiniHttp.Request req) throws Exception {
                if (req.host.endsWith("accu-weather.com")) return weather.handle(req);
                if (req.host.equals("api.htc.go.yahoo.com")) return stocks.handle(req);
                if (req.host.startsWith("geo-prism.")) return feed.api.handle(req);
                if (req.host.startsWith("img-prism.")) return feed.images.handle(req);
                if (req.host.startsWith("log-prism.")) {
                    return MiniHttp.Response.text(200, "application/json", "{\"res\":{}}");
                }
                return MiniHttp.Response.notFound();
            }
        };

        ServerSocket plain = new ServerSocket(Prefs.httpPort(this), 50, lo);
        plain.setReuseAddress(true);
        http = new MiniHttp(plain, router);
        http.start("revive-http");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream p12 = getAssets().open("server.p12");
        ks.load(p12, "htcrevive".toCharArray());
        p12.close();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "htcrevive".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        SSLServerSocket secure = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(Prefs.httpsPort(this), 50, lo);
        secure.setEnabledProtocols(secure.getSupportedProtocols());
        https = new MiniHttp(secure, router);
        https.start("revive-https");
    }

    @SuppressWarnings("deprecation")
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("HTC Revive")
                .setContentText("Serving weather, BlinkFeed and stocks locally")
                .setContentIntent(open)
                .setOngoing(true)
                .getNotification();
    }

    @Override
    public void onDestroy() {
        if (http != null) http.stop();
        if (https != null) https.stop();
        http = https = null;
        status = "stopped";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
