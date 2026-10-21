package com.htcrevive.tls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

/** Minimal HTTP/1.1 GET over a verified {@link WolfClient} connection. */
public final class Https {
    private Https() {}

    public static final class Response {
        public final int status;
        public final String location;
        public final byte[] body;

        Response(int status, String location, byte[] body) {
            this.status = status;
            this.location = location;
            this.body = body;
        }
    }

    public static Response get(String host, int port, String path, int timeoutMs) throws IOException {
        WolfClient.Conn conn = WolfClient.connect(host, port, timeoutMs);
        try {
            OutputStream out = conn.out;
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "User-Agent: Mozilla/5.0 (Linux; Android 4.2.2; HTC One) HtcRevive/1.0\r\n"
                    + "Accept: */*\r\n"
                    + "Accept-Encoding: gzip\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(req.getBytes("ISO-8859-1"));

            InputStream in = conn.in;
            String statusLine = readLine(in);
            if (statusLine == null) throw new IOException("empty response from " + host);
            int status = parseStatus(statusLine);

            String location = null;
            int contentLength = -1;
            boolean chunked = false, gzip = false;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).trim().toLowerCase();
                String v = line.substring(c + 1).trim();
                if (k.equals("location")) location = v;
                else if (k.equals("content-length")) contentLength = parseInt(v, -1);
                else if (k.equals("transfer-encoding")) chunked = v.toLowerCase().contains("chunked");
                else if (k.equals("content-encoding")) gzip = v.toLowerCase().contains("gzip");
            }

            byte[] body;
            if (status >= 300 && status < 400 && location != null) {
                body = new byte[0]; // redirect; caller refetches
            } else if (chunked) {
                body = readChunked(in);
            } else if (contentLength >= 0) {
                body = readFixed(in, contentLength);
            } else {
                body = readToEnd(in);
            }
            if (gzip && body.length > 0) body = gunzip(body);
            return new Response(status, location, body);
        } finally {
            conn.close();
        }
    }

    private static int parseStatus(String statusLine) throws IOException {
        String[] p = statusLine.split(" ");
        if (p.length < 2) throw new IOException("bad status line: " + statusLine);
        return parseInt(p[1], 0);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 65536) throw new IOException("header line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static byte[] readFixed(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return off == len ? buf : java.util.Arrays.copyOf(buf, off);
    }

    private static byte[] readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String sizeLine;
        while ((sizeLine = readLine(in)) != null) {
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
            int size = Integer.parseInt(sizeLine.trim(), 16);
            if (size == 0) break;
            out.write(readFixed(in, size));
            readLine(in); // trailing CRLF
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] in) throws IOException {
        GZIPInputStream g = new GZIPInputStream(new java.io.ByteArrayInputStream(in));
        return WolfClient.readAll(g);
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }
}
