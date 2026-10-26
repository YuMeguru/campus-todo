package dev.campus.todo;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * API Key 的落盘保护：用 Android Keystore 里不可导出的 AES 密钥做 AES/GCM 加密，
 * 密文（iv 长度 + iv + 密文）以 Base64 存在私有 SharedPreferences 里。
 *
 * 不引 androidx.security:security-crypto（已废弃），这二十来行就够，而且不引入依赖。
 * Key 不写进源码、BuildConfig、日志，也不外传。
 */
public final class KeyVault {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "campus_todo_vault_v1";
    private static final String PREFS = "vault";
    private static final String SLOT = "api_key";
    private static final int GCM_TAG_BITS = 128;

    private KeyVault() {
    }

    private static String cachedKey;
    private static boolean cacheLoaded;

    public static void save(Context ctx, String plain) throws Exception {
        if (plain == null || plain.trim().isEmpty()) {
            clear(ctx);
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(plain.trim().getBytes(StandardCharsets.UTF_8));

        byte[] blob = new byte[1 + iv.length + body.length];
        blob[0] = (byte) iv.length;
        System.arraycopy(iv, 0, blob, 1, iv.length);
        System.arraycopy(body, 0, blob, 1 + iv.length, body.length);

        prefs(ctx).edit()
                .putString(SLOT, Base64.getEncoder().encodeToString(blob))
                .apply();

        cachedKey = plain.trim();
        cacheLoaded = true;
    }

    /**
     * 第一次会真的走 Keystore，之后走内存缓存。
     *
     * 这个缓存不是为了省 CPU，是为了不卡：Keystore 是跨进程调用，几十毫秒很常见，
     * 而 refresh() 这类主线程路径每次都会问一次 —— 那就是界面卡顿的来源。
     */
    public static String load(Context ctx) {
        if (cacheLoaded) {
            return cachedKey;
        }
        cachedKey = decrypt(ctx);
        cacheLoaded = true;
        return cachedKey;
    }

    /** 任何一步失败都返回空串：宁可让用户重填，也不要拿半个 Key 去发请求。 */
    private static String decrypt(Context ctx) {
        String stored = prefs(ctx).getString(SLOT, null);
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        try {
            byte[] blob = Base64.getDecoder().decode(stored);
            int ivLen = blob[0] & 0xFF;
            byte[] iv = new byte[ivLen];
            System.arraycopy(blob, 1, iv, 0, ivLen);
            byte[] body = new byte[blob.length - 1 - ivLen];
            System.arraycopy(blob, 1 + ivLen, body, 0, body.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解不开就当没有。这里直接清 prefs，不要再调 clear()，免得又走一遍缓存逻辑。
            prefs(ctx).edit().remove(SLOT).apply();
            return "";
        }
    }

    public static boolean has(Context ctx) {
        return !load(ctx).isEmpty();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(SLOT).apply();
        cachedKey = "";
        cacheLoaded = true;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
