package ro.talktext;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** API keys encrypted with AES-GCM; the AES key lives in Android Keystore and never leaves it. */
final class Vault {
    static final String OPENAI = "openai", ANTHROPIC = "anthropic";
    private static final String ALIAS = "talktext-api";

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            g.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    static void save(Context c, String name, String value) throws Exception {
        Cipher x = Cipher.getInstance("AES/GCM/NoPadding");
        x.init(Cipher.ENCRYPT_MODE, key());
        String encoded = Base64.encodeToString(x.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(x.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        c.getSharedPreferences("secret", 0).edit().putString(name, encoded).apply();
    }

    static String read(Context c, String name) {
        String s = c.getSharedPreferences("secret", 0).getString(name, "");
        if (s.isEmpty()) return "";
        try {
            String[] p = s.split(":");
            Cipher x = Cipher.getInstance("AES/GCM/NoPadding");
            x.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[0], 0)));
            return new String(x.doFinal(Base64.decode(p[1], 0)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static boolean has(Context c, String name) { return !read(c, name).isEmpty(); }

    static void clear(Context c, String name) {
        c.getSharedPreferences("secret", 0).edit().remove(name).apply();
    }

    private Vault() {}
}
