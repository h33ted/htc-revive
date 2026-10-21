package com.htcrevive.tls;

import android.content.Context;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A verifying TLS 1.2/1.3 client, backed by wolfSSL (librevivetls.so), for HTC Revive's own
 * outbound HTTPS. It connects out and checks the server's certificate and hostname against the
 * bundled Mozilla root store; it intercepts nothing. The native FD-extraction trick and the
 * socket plumbing follow gohoski/Wolfius (GPL-3.0).
 */
public final class WolfClient {
    private static final String TAG = "ReviveTls";
    private static boolean ready = false;

    static {
        System.loadLibrary("revivetls");
    }

    private static native int nativeInit(byte[] rootsPem);
    private static native long nativeConnect(int fd, String host);
    private static native int nativeRead(long ssl, byte[] b, int off, int len);
    private static native int nativeWrite(long ssl, byte[] b, int off, int len);
    private static native void nativeFree(long ssl);

    private WolfClient() {}

    public static synchronized void init(Context ctx) throws IOException {
        if (ready) return;
        InputStream in = ctx.getAssets().open("roots.pem");
        byte[] roots = readAll(in);
        int rc = nativeInit(roots);
        if (rc != 0) throw new IOException("wolfSSL init failed: " + rc);
        ready = true;
    }

    public static boolean isReady() {
        return ready;
    }

    /** A TLS connection to host:port, verified. Close it when done. */
    public static Conn connect(String host, int port, int timeoutMs) throws IOException {
        Socket sock = new Socket();
        sock.setTcpNoDelay(true);
        sock.connect(new InetSocketAddress(host, port), timeoutMs);
        sock.setSoTimeout(timeoutMs);
        int fd = nativeFd(sock);
        long ssl = nativeConnect(fd, host);
        if (ssl == 0) {
            try { sock.close(); } catch (IOException ignored) {}
            throw new IOException("TLS handshake/verification failed for " + host);
        }
        return new Conn(sock, ssl);
    }

    public static final class Conn {
        private final Socket socket;
        private final long ssl;
        private boolean closed = false;

        Conn(Socket socket, long ssl) {
            this.socket = socket;
            this.ssl = ssl;
        }

        public final InputStream in = new InputStream() {
            private final byte[] one = new byte[1];

            @Override
            public int read() throws IOException {
                int n = read(one, 0, 1);
                return n <= 0 ? -1 : (one[0] & 0xFF);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                int n = nativeRead(ssl, b, off, len);
                return n <= 0 ? -1 : n;
            }
        };

        public final OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                while (len > 0) {
                    int n = nativeWrite(ssl, b, off, len);
                    if (n <= 0) throw new IOException("TLS write failed: " + n);
                    off += n;
                    len -= n;
                }
            }
        };

        public void close() {
            if (closed) return;
            closed = true;
            nativeFree(ssl);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ---- native socket fd, via reflection (same approach as Wolfius) -------------------------

    private static Field fImpl, fFd, fDesc;

    private static int nativeFd(Socket s) throws IOException {
        try {
            if (fImpl == null) {
                fImpl = Socket.class.getDeclaredField("impl");
                fImpl.setAccessible(true);
                Class<?> impl = Class.forName("java.net.SocketImpl");
                try {
                    fFd = impl.getDeclaredField("fd");
                } catch (NoSuchFieldException e) {
                    fFd = impl.getSuperclass().getDeclaredField("fd");
                }
                fFd.setAccessible(true);
                try {
                    fDesc = FileDescriptor.class.getDeclaredField("descriptor");
                } catch (NoSuchFieldException e) {
                    fDesc = FileDescriptor.class.getDeclaredField("fd");
                }
                fDesc.setAccessible(true);
            }
            Object impl = fImpl.get(s);
            FileDescriptor fd = (FileDescriptor) fFd.get(impl);
            return fDesc.getInt(fd);
        } catch (Exception e) {
            throw new IOException("could not extract native fd: " + e);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
