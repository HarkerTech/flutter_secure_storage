package com.it_nomads.fluttersecurestorage.ciphers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.InvalidAlgorithmParameterException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@SuppressLint("ApplySharedPref")
public class StorageCipher18Implementation implements StorageCipher {

    // Backwards compatibility
    private final int ivSize;
    private static final int keySize = 16;
    private static final String KEY_ALGORITHM = "AES";
    // Backwards compatibility
    private static final String USE_AES_KEY = "essentials_use_symmetric";
    private static final String AES_PREFERENCES_KEY = "VGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK";
    // Backwards compatibility
    private final String SHARED_PREFERENCES_NAME;

    private Key secretKey;
    private final Cipher cipher;
    private final SecureRandom secureRandom;

    public StorageCipher18Implementation(Context context) throws Exception {
        SHARED_PREFERENCES_NAME = context.getPackageName() + ".xamarinessentials";

        secureRandom = new SecureRandom();
        RSACipher18Implementation rsaCipher = new RSACipher18Implementation(context);

        SharedPreferences preferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();

        // Backwards compatibility
        // Attempt to extract existing key
        boolean useAES = preferences.getBoolean(USE_AES_KEY, false);
        if (useAES) {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            secretKey = ks.getKey(SHARED_PREFERENCES_NAME, null);
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            ivSize = 12;
            return;
        }

        String aesKey = preferences.getString(AES_PREFERENCES_KEY, null);
        cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
        ivSize = 16;

        if (aesKey != null) {
            byte[] encrypted;
            try {
                encrypted = Base64.decode(aesKey, Base64.DEFAULT);
                secretKey = rsaCipher.unwrap(encrypted, KEY_ALGORITHM);
                return;
            } catch (Exception e) {
                Log.e("StorageCipher18Impl", "unwrap key failed", e);
                encrypted = new byte[0];
            }
        }

        byte[] key = new byte[keySize];
        secureRandom.nextBytes(key);
        secretKey = new SecretKeySpec(key, KEY_ALGORITHM);

        byte[] encryptedKey = rsaCipher.wrap(secretKey);
        editor.putString(AES_PREFERENCES_KEY, Base64.encodeToString(encryptedKey, Base64.DEFAULT));
        editor.commit();
    }

    @Override
    public byte[] encrypt(byte[] input) throws Exception {
        byte[] iv = new byte[ivSize];
        secureRandom.nextBytes(iv);

        // Backwards compatibility
        try {
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmParameterSpec);
        } catch (InvalidAlgorithmParameterException e) {
            IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec);
        }

        byte[] payload = cipher.doFinal(input);
        byte[] combined = new byte[iv.length + payload.length];

        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(payload, 0, combined, iv.length, payload.length);

        return combined;
    }

    @Override
    public byte[] decrypt(byte[] input) throws Exception {
        byte[] iv = new byte[ivSize];
        System.arraycopy(input, 0, iv, 0, iv.length);
        int payloadSize = input.length - ivSize;
        byte[] payload = new byte[payloadSize];
        System.arraycopy(input, iv.length, payload, 0, payloadSize);

        // Backwards compatibility
        try {
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec);
        } catch (InvalidAlgorithmParameterException e) {
            IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec);
        }

        return cipher.doFinal(payload);
    }
}
