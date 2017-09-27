/*
 * Copyright (C) 2017 adorsys GmbH & Co. KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.adorsys.android.securestoragelibrary;

import android.content.res.Configuration;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Base64;
import android.util.Log;
import android.view.View;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.security.auth.x500.X500Principal;

import static android.os.Build.VERSION_CODES.M;
import static de.adorsys.android.securestoragelibrary.SecureStorageException.ExceptionType.CRYPTO_EXCEPTION;
import static de.adorsys.android.securestoragelibrary.SecureStorageException.ExceptionType.INTERNAL_LIBRARY_EXCEPTION;
import static de.adorsys.android.securestoragelibrary.SecureStorageException.ExceptionType.KEYSTORE_EXCEPTION;
import static de.adorsys.android.securestoragelibrary.SecureStorageProvider.context;

final class KeystoreTool {
    private static final String KEY_ALIAS = "adorsysKeyPair";
    private static final String KEY_ENCRYPTION_ALGORITHM = "RSA";
    private static final String KEY_CHARSET = "UTF-8";
    private static final String KEY_KEYSTORE_NAME = "AndroidKeyStore";
    private static final String KEY_CIPHER_JELLYBEAN_PROVIDER = "AndroidOpenSSL";
    private static final String KEY_CIPHER_MARSHMALLOW_PROVIDER = "AndroidKeyStoreBCWorkaround";
    private static final String KEY_TRANSFORMATION_ALGORITHM = "RSA/ECB/PKCS1Padding";
    private static final String KEY_X500PRINCIPAL = "CN=SecureDeviceStorage, O=Adorsys, C=Germany";

    // hidden constructor to disable initialization
    private KeystoreTool() {
    }

    @Nullable
    static String encryptMessage(@NonNull String plainMessage) throws SecureStorageException {
        try {
            Cipher input;
            if (Build.VERSION.SDK_INT >= M) {
                input = Cipher.getInstance(KEY_TRANSFORMATION_ALGORITHM, KEY_CIPHER_MARSHMALLOW_PROVIDER);
            } else {
                input = Cipher.getInstance(KEY_TRANSFORMATION_ALGORITHM, KEY_CIPHER_JELLYBEAN_PROVIDER);
            }
            input.init(Cipher.ENCRYPT_MODE, getPublicKey());

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            CipherOutputStream cipherOutputStream = new CipherOutputStream(
                    outputStream, input);
            cipherOutputStream.write(plainMessage.getBytes(KEY_CHARSET));
            cipherOutputStream.close();

            byte[] values = outputStream.toByteArray();
            return Base64.encodeToString(values, Base64.DEFAULT);

        } catch (Exception e) {
            throw new SecureStorageException(e.getMessage(), e, KEYSTORE_EXCEPTION);
        }
    }

    @NonNull
    static String decryptMessage(@NonNull String encryptedMessage) throws SecureStorageException {
        try {
            Cipher output;
            if (Build.VERSION.SDK_INT >= M) {
                output = Cipher.getInstance(KEY_TRANSFORMATION_ALGORITHM, KEY_CIPHER_MARSHMALLOW_PROVIDER);
            } else {
                output = Cipher.getInstance(KEY_TRANSFORMATION_ALGORITHM, KEY_CIPHER_JELLYBEAN_PROVIDER);
            }
            output.init(Cipher.DECRYPT_MODE, getPrivateKey());

            CipherInputStream cipherInputStream = new CipherInputStream(
                    new ByteArrayInputStream(Base64.decode(encryptedMessage, Base64.DEFAULT)), output);
            List<Byte> values = new ArrayList<>();

            int nextByte;
            while ((nextByte = cipherInputStream.read()) != -1) { //NOPMD
                values.add((byte) nextByte);
            }

            byte[] bytes = new byte[values.size()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = values.get(i);
            }

            return new String(bytes, 0, bytes.length, KEY_CHARSET);

        } catch (Exception e) {
            throw new SecureStorageException(e.getMessage(), e, CRYPTO_EXCEPTION);
        }
    }

    static boolean keyPairExists() throws SecureStorageException {
        try {
            return getKeyStoreInstance().getKey(KEY_ALIAS, null) != null;
        } catch (Exception e) {
            throw new SecureStorageException(e.getMessage(), e, KEYSTORE_EXCEPTION);
        }
    }

    static void generateKeyPair() throws SecureStorageException {
        // Create new key if needed
        if (!keyPairExists()) {
            generateAsymmetricKeyPair();
        } else if (BuildConfig.DEBUG) {
            Log.e(KeystoreTool.class.getName(),
                    context.get().getString(R.string.message_keypair_already_exists));
        }
    }

    static void deleteKeyPair() throws SecureStorageException {
        // Delete Key from Keystore
        if (keyPairExists()) {
            try {
                getKeyStoreInstance().deleteEntry(KEY_ALIAS);
            } catch (KeyStoreException e) {
                throw new SecureStorageException(e.getMessage(), e, KEYSTORE_EXCEPTION);
            }
        } else if (BuildConfig.DEBUG) {
            Log.e(KeystoreTool.class.getName(),
                    context.get().getString(R.string.message_keypair_does_not_exist));
        }
    }

    @Nullable
    private static PublicKey getPublicKey() throws SecureStorageException {
        try {
            if (keyPairExists()) {
                KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry) getKeyStoreInstance().getEntry(KEY_ALIAS, null);
                return privateKeyEntry.getCertificate().getPublicKey();
            } else {
                if (BuildConfig.DEBUG) {
                    Log.e(KeystoreTool.class.getName(), context.get().getString(R.string.message_keypair_does_not_exist));
                }
                throw new SecureStorageException(context.get().getString(R.string.message_keypair_does_not_exist), null, INTERNAL_LIBRARY_EXCEPTION);
            }
        } catch (Exception e) {
            throw new SecureStorageException(e.getMessage(), e, KEYSTORE_EXCEPTION);
        }
    }

    @Nullable
    private static PrivateKey getPrivateKey() throws SecureStorageException {
        try {
            if (keyPairExists()) {
                KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry) getKeyStoreInstance().getEntry(KEY_ALIAS, null);
                return privateKeyEntry.getPrivateKey();
            } else {
                if (BuildConfig.DEBUG) {
                    Log.e(KeystoreTool.class.getName(), context.get().getString(R.string.message_keypair_does_not_exist));
                }
                throw new SecureStorageException(context.get().getString(R.string.message_keypair_does_not_exist), null, INTERNAL_LIBRARY_EXCEPTION);
            }
        } catch (Exception e) {
            throw new SecureStorageException(e.getMessage(), e, KEYSTORE_EXCEPTION);
        }
    }

    private static boolean isRTL() {
        Configuration config = context.get().getResources().getConfiguration();
        return config.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private static void generateAsymmetricKeyPair() throws SecureStorageException {
        try {
            if (isRTL()) {
                Locale.setDefault(Locale.US);
            }

            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 99);

            KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(context.get())
                    .setAlias(KEY_ALIAS)
                    .setSubject(new X500Principal(KEY_X500PRINCIPAL))
                    .setSerialNumber(BigInteger.TEN)
                    .setStartDate(start.getTime())
                    .setEndDate(end.getTime())
                    .build();

            KeyPairGenerator generator
                    = KeyPairGenerator.getInstance(KEY_ENCRYPTION_ALGORITHM, KEY_KEYSTORE_NAME);
            generator.initialize(spec);
            generator.generateKeyPair();
        } catch (Exception e) {
            throw new SecureStorageException(e.getMessage(), e, KEYSTORE_EXCEPTION);
        }
    }

    @NonNull
    private static KeyStore getKeyStoreInstance() throws SecureStorageException {
        try {
            // Get the AndroidKeyStore instance
            KeyStore keyStore = KeyStore.getInstance(KEY_KEYSTORE_NAME);

            // Relict of the JCA API - you have to call load even
            // if you do not have an input stream you want to load or it'll crash
            keyStore.load(null);

            return keyStore;
        } catch (Exception e) {
            throw new SecureStorageException(e.getMessage(), e, KEYSTORE_EXCEPTION);
        }
    }
}