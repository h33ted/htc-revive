import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Signs with apksig so the v1 signature has no CMS signed attributes (Android < 4.3 rejects them). */
public class ApkSign {
    public static void main(String[] a) throws Exception {
        String keystore = a[0], pass = a[1], alias = a[2], in = a[3], out = a[4];
        // apksig picks digest algorithms from minSdk: below 18 it uses SHA-1, which Android 2.x needs.
        int minSdk = a.length > 5 ? Integer.parseInt(a[5]) : 17;
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream f = new FileInputStream(keystore)) {
            ks.load(f, pass.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, pass.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(alias, key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(new File(in))
                .setOutputApk(new File(out))
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(false)
                .build()
                .sign();
    }
}
