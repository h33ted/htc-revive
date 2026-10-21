package com.htcrevive;

import com.htcrevive.tls.Https;
import com.htcrevive.tls.WolfClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * Upstream fetches. Plain HTTP uses the platform HttpURLConnection; HTTPS goes through the
 * bundled verifying wolfSSL client (WolfClient/Https), since Android 4.2's own stack tops out
 * at TLS 1.0 and can't reach modern servers.
 */
final class Net {
    private Net() {}

    static byte[] get(String url) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(url);
            String scheme = u.getProtocol();
            if (scheme.equals("https")) {
                int port = u.getPort() == -1 ? 443 : u.getPort();
                String path = u.getFile();
                if (path == null || path.length() == 0) path = "/";
                if (!WolfClient.isReady()) throw new IOException("TLS client not initialized");
                Https.Response r = Https.get(u.getHost(), port, path, 15000);
                if (r.status >= 300 && r.status < 400 && r.location != null) {
                    url = new URL(u, r.location).toString();
                    continue;
                }
                if (r.status != 200) throw new IOException("HTTPS " + r.status + " from " + url);
                return r.body;
            }

            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 4.2.2; HTC One) HtcRevive/1.0");
            c.setRequestProperty("Accept-Encoding", "gzip");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(u, c.getHeaderField("Location")).toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code + " from " + url);
                InputStream in = c.getInputStream();
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
                return readAll(in);
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects: " + url);
    }

    static String getString(String url) throws IOException {
        return new String(get(url), "UTF-8");
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
