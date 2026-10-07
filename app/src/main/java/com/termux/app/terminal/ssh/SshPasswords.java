package com.termux.app.terminal.ssh;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The SSH passwords saved in the app, encrypted with a key in the Android Keystore that can't be
 * exported, and stored in the app private preferences. Other apps can't read them, and they are
 * lost when the app data is cleared, since the key is.
 *
 * Note that any program running in Termux runs as the same user as the app, so this does not
 * protect them from those.
 */
public final class SshPasswords {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "termux_ssh_passwords";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private static final String PREFERENCES_NAME = "terminal_ssh_passwords";

    private static final String LOG_TAG = "SshPasswords";

    private SshPasswords() {}

    private static SharedPreferences getPreferences(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public static boolean has(@NonNull Context context, @NonNull String hostName) {
        return getPreferences(context).contains(hostName);
    }

    /** Save the password of a host, returning whether it was saved. */
    public static boolean save(@NonNull Context context, @NonNull String hostName, @NonNull char[] password) {
        byte[] plain = toBytes(password);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(plain);
            byte[] stored = new byte[1 + iv.length + encrypted.length];
            stored[0] = (byte) iv.length;
            System.arraycopy(iv, 0, stored, 1, iv.length);
            System.arraycopy(encrypted, 0, stored, 1 + iv.length, encrypted.length);
            getPreferences(context).edit().putString(hostName, Base64.encodeToString(stored, Base64.NO_WRAP)).apply();
            return true;
        } catch (GeneralSecurityException | java.io.IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save the password of \"" + hostName + "\"", e);
            return false;
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** Get the password of a host as UTF-8 bytes, which the caller should clear, or {@code null} if none is saved. */
    @Nullable
    public static byte[] load(@NonNull Context context, @NonNull String hostName) {
        String value = getPreferences(context).getString(hostName, null);
        if (value == null) return null;
        try {
            byte[] stored = Base64.decode(value, Base64.NO_WRAP);
            int ivLength = stored[0];
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, stored, 1, ivLength));
            return cipher.doFinal(stored, 1 + ivLength, stored.length - 1 - ivLength);
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load the password of \"" + hostName + "\"", e);
            return null;
        }
    }

    public static void delete(@NonNull Context context, @NonNull String hostName) {
        getPreferences(context).edit().remove(hostName).apply();
    }

    /** Move a saved password when a host is renamed. */
    public static void rename(@NonNull Context context, @NonNull String oldName, @NonNull String newName) {
        if (oldName.equals(newName)) return;
        SharedPreferences preferences = getPreferences(context);
        String value = preferences.getString(oldName, null);
        if (value == null) return;
        preferences.edit().remove(oldName).putString(newName, value).apply();
    }

    @NonNull
    private static SecretKey getOrCreateKey() throws GeneralSecurityException, java.io.IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry)
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        keyGenerator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return keyGenerator.generateKey();
    }

    @NonNull
    private static byte[] toBytes(@NonNull char[] chars) {
        java.nio.ByteBuffer buffer = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        byte[] bytes = Arrays.copyOfRange(buffer.array(), buffer.position(), buffer.limit());
        Arrays.fill(buffer.array(), (byte) 0);
        return bytes;
    }

}
