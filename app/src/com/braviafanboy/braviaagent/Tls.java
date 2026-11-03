package com.braviafanboy.braviaagent;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Calendar;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.security.auth.x500.X500Principal;

/**
 * The agent's TLS identity: an EC P-256 key pair with a self-signed certificate,
 * generated once in the Android keystore (private key never leaves the TV,
 * survives app updates, is lost with an uninstall or factory reset, which
 * also loses the pairing token, so the integration simply re-pairs). Clients
 * pin the certificate's SHA-256 fingerprint, published in /v1/info.
 */
public final class Tls {
    private static final String TAG = "BraviaAgent";
    // v3: EC P-256. Conscrypt signs TLS handshakes with a raw NONEwithECDSA
    // operation, so the key must allow DIGEST_NONE; keystore RSA keys fail with
    // "Incompatible padding mode" in that path.
    private static final String ALIAS = "bravia-agent-tls-v3";
    private static final String[] OLD_ALIASES = {"bravia-agent-tls", "bravia-agent-tls-v2"};
    private static final int VALID_YEARS = 25;

    private final KeyStore keyStore;
    private final String fingerprint;

    private Tls(KeyStore keyStore, String fingerprint) {
        this.keyStore = keyStore;
        this.fingerprint = fingerprint;
    }

    /** SHA-256 of the DER certificate, lowercase hex. */
    public String fingerprint() {
        return fingerprint;
    }

    public static Tls load(Context context) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        for (String old : OLD_ALIASES) {
            if (ks.containsAlias(old)) {
                try { ks.deleteEntry(old); } catch (Exception ignored) { }
            }
        }
        if (!ks.containsAlias(ALIAS)) {
            Calendar start = Calendar.getInstance();
            start.add(Calendar.DAY_OF_MONTH, -1);
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, VALID_YEARS);
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            kpg.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(new X500Principal("CN=HA TV Agent"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(start.getTime())
                .setCertificateNotAfter(end.getTime())
                .build());
            kpg.generateKeyPair();
            Log.i(TAG, "generated TLS key pair");
        }
        Certificate cert = ks.getCertificate(ALIAS);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return new Tls(ks, sb.toString());
    }

    public SSLServerSocketFactory serverSocketFactory() throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, null);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx.getServerSocketFactory();
    }
}
