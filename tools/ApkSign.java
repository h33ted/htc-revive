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
                .setMinSdkVersion(17)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(false)
                .build()
                .sign();
    }
}
