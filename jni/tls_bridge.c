/*
 * HTC Revive TLS client bridge: a verifying HTTPS client for the app's own outbound fetches
 * (the RSS feeds BlinkFeed is built from), so a 4.2 device can reach TLS 1.2/1.3 servers.
 *
 * This is a plain TLS *client*: it connects out, verifies the server certificate against a
 * bundled Mozilla root store, and checks the hostname. It does not intercept, decrypt, or
 * re-sign any other app's traffic, and it generates no certificates.
 *
 * wolfSSL is bundled under third_party/wolfssl (GPL-3.0). The build settings in
 * user_settings.h are adapted from gohoski/Wolfius (GPL-3.0); this bridge is original and
 * accordingly GPL-3.0-or-later. See LICENSE and CREDITS.
 */
#include <jni.h>
#undef JNIEXPORT
#define JNIEXPORT __attribute__((visibility("default")))
#include <string.h>
#include <wolfssl/ssl.h>
#include <android/log.h>

#define TAG "ReviveTls"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* TLS 1.2 and 1.3 AEAD suites only; no 3DES/RC4/CBC-SHA1. */
static const char* kModernCiphers =
    "TLS13-AES256-GCM-SHA384:"
    "TLS13-AES128-GCM-SHA256:"
    "TLS13-CHACHA20-POLY1305-SHA256:"
    "ECDHE-ECDSA-AES256-GCM-SHA384:"
    "ECDHE-RSA-AES256-GCM-SHA384:"
    "ECDHE-ECDSA-AES128-GCM-SHA256:"
    "ECDHE-RSA-AES128-GCM-SHA256:"
    "ECDHE-ECDSA-CHACHA20-POLY1305:"
    "ECDHE-RSA-CHACHA20-POLY1305:"
    "DHE-RSA-AES256-GCM-SHA384:"
    "DHE-RSA-AES128-GCM-SHA256";

static WOLFSSL_CTX* gCtx = NULL;

#ifdef REVIVE_TLS_DEBUG
static void revive_log_cb(const int level, const char* const msg) {
    __android_log_print(ANDROID_LOG_DEBUG, "ReviveTlsDbg", "%s", msg);
}
#endif

/* Loads the trusted root store and builds the shared client context. 0 on success. */
JNIEXPORT jint JNICALL
Java_com_htcrevive_tls_WolfClient_nativeInit(JNIEnv* env, jclass clazz, jbyteArray rootsPem) {
    jbyte* roots;
    jsize rootsLen;
    int ret;

    if (gCtx != NULL) return 0;
    wolfSSL_Init();
#ifdef REVIVE_TLS_DEBUG
    wolfSSL_SetLoggingCb(revive_log_cb);
    wolfSSL_Debugging_ON();
#endif
    gCtx = wolfSSL_CTX_new(wolfSSLv23_client_method());
    if (gCtx == NULL) {
        LOGE("CTX_new failed");
        return -1;
    }
    /* Negotiate TLS 1.2 at minimum; the old server side of the device is irrelevant here. */
    wolfSSL_CTX_SetMinVersion(gCtx, WOLFSSL_TLSV1_2);
    if (wolfSSL_CTX_set_cipher_list(gCtx, kModernCiphers) != WOLFSSL_SUCCESS) {
        LOGE("set_cipher_list failed");
    }

    roots = (*env)->GetByteArrayElements(env, rootsPem, NULL);
    rootsLen = (*env)->GetArrayLength(env, rootsPem);
    /* IGNORE_ERR: skip any single unparseable root (e.g. an algorithm we didn't enable)
     * instead of aborting the whole bundle and losing every root after it. */
    ret = wolfSSL_CTX_load_verify_buffer_ex(gCtx, (const unsigned char*)roots, rootsLen,
                                            WOLFSSL_FILETYPE_PEM, 0,
                                            WOLFSSL_LOAD_FLAG_IGNORE_ERR);
    (*env)->ReleaseByteArrayElements(env, rootsPem, roots, JNI_ABORT);
    if (ret != WOLFSSL_SUCCESS) {
        LOGE("load_verify_buffer failed: %d", ret);
        wolfSSL_CTX_free(gCtx);
        gCtx = NULL;
        return -2;
    }

    /* The whole point of this bridge: actually verify the peer. */
    wolfSSL_CTX_set_verify(gCtx, WOLFSSL_VERIFY_PEER, NULL);
    LOGI("client context ready, roots loaded");
    return 0;
}

/*
 * Wraps an already-connected socket fd in TLS to `host`, verifying cert and hostname.
 * Returns the WOLFSSL* as a long, or 0 on failure (handshake, cert, or name mismatch).
 */
JNIEXPORT jlong JNICALL
Java_com_htcrevive_tls_WolfClient_nativeConnect(JNIEnv* env, jclass clazz, jint fd, jstring hostStr) {
    const char* host;
    WOLFSSL* ssl;
    int ret;

    if (gCtx == NULL) return 0;
    ssl = wolfSSL_new(gCtx);
    if (ssl == NULL) return 0;
    wolfSSL_set_fd(ssl, fd);

    host = (*env)->GetStringUTFChars(env, hostStr, NULL);
    if (host != NULL) {
        wolfSSL_UseSNI(ssl, WOLFSSL_SNI_HOST_NAME, host, (unsigned short)strlen(host));
        /* Reject a cert whose names don't cover this host. */
        wolfSSL_check_domain_name(ssl, host);
    }

    ret = wolfSSL_connect(ssl);
    if (ret != WOLFSSL_SUCCESS) {
        int err = wolfSSL_get_error(ssl, ret);
        char buf[80];
        wolfSSL_ERR_error_string(err, buf);
        LOGE("handshake/verify failed for %s: %d (%s)", host ? host : "?", err, buf);
#if defined(REVIVE_TLS_DEBUG) && defined(SESSION_CERTS)
        {
            WOLFSSL_X509_CHAIN* chain = wolfSSL_get_peer_chain(ssl);
            int i, n = chain ? wolfSSL_get_chain_count(chain) : 0;
            for (i = 0; i < n; i++) {
                unsigned char pem[4096];
                int pemSz = 0;
                if (wolfSSL_get_chain_cert_pem(chain, i, pem, sizeof(pem) - 1, &pemSz) == WOLFSSL_SUCCESS) {
                    pem[pemSz] = 0;
                    LOGE("peer cert %d/%d:\n%s", i, n, pem);
                }
            }
        }
#endif
        if (host) (*env)->ReleaseStringUTFChars(env, hostStr, host);
        wolfSSL_free(ssl);
        return 0;
    }
    if (host) (*env)->ReleaseStringUTFChars(env, hostStr, host);
    return (jlong)(unsigned long)ssl;
}

JNIEXPORT jint JNICALL
Java_com_htcrevive_tls_WolfClient_nativeRead(JNIEnv* env, jclass clazz, jlong sslPtr,
                                             jbyteArray array, jint off, jint len) {
    WOLFSSL* ssl = (WOLFSSL*)(unsigned long)sslPtr;
    jbyte* buf = (*env)->GetByteArrayElements(env, array, NULL);
    int r;
    if (buf == NULL) return -1;
    r = wolfSSL_read(ssl, buf + off, len);
    (*env)->ReleaseByteArrayElements(env, array, buf, 0);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_htcrevive_tls_WolfClient_nativeWrite(JNIEnv* env, jclass clazz, jlong sslPtr,
                                              jbyteArray array, jint off, jint len) {
    WOLFSSL* ssl = (WOLFSSL*)(unsigned long)sslPtr;
    jbyte* buf = (*env)->GetByteArrayElements(env, array, NULL);
    int r;
    if (buf == NULL) return -1;
    r = wolfSSL_write(ssl, buf + off, len);
    (*env)->ReleaseByteArrayElements(env, array, buf, JNI_ABORT);
    return r;
}

JNIEXPORT void JNICALL
Java_com_htcrevive_tls_WolfClient_nativeFree(JNIEnv* env, jclass clazz, jlong sslPtr) {
    if (sslPtr != 0) {
        WOLFSSL* ssl = (WOLFSSL*)(unsigned long)sslPtr;
        wolfSSL_shutdown(ssl);
        wolfSSL_free(ssl);
    }
}
