package com.qualityos.common.security;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Keeps a small secret, such as an authenticator seed, unreadable in the database: AES-256-GCM under a key the
 * application holds. Losing the key loses every sealed secret, so a real environment sets it and keeps it.
 */
public class SecretBox {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    SecretBox(SecretKey key) {
        this.key = key;
    }

    /** With no key configured, local development gets one for this run only, so a restart ends every enrolment. */
    static SecretBox from(String configuredKey, boolean allowEphemeral) {
        if (StringUtils.hasText(configuredKey)) {
            byte[] bytes = Base64.getDecoder().decode(configuredKey.trim());
            if (bytes.length != KEY_BYTES) {
                throw new IllegalStateException("SECOND_FACTOR_KEY must be " + KEY_BYTES + " bytes, base64 encoded");
            }
            return new SecretBox(new SecretKeySpec(bytes, "AES"));
        }
        if (!allowEphemeral) {
            throw new IllegalStateException("SECOND_FACTOR_KEY must be configured");
        }
        log.warn("No SECOND_FACTOR_KEY is set. Authenticator enrolments will not survive a restart.");
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return new SecretBox(new SecretKeySpec(bytes, "AES"));
    }

    /** The nonce leads the ciphertext, which carries its own tag. */
    public byte[] seal(byte[] plain) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain);
            byte[] out = Arrays.copyOf(iv, iv.length + sealed.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not seal the secret", e);
        }
    }

    public byte[] open(byte[] sealed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (GeneralSecurityException | ArrayIndexOutOfBoundsException e) {
            throw new IllegalStateException("The sealed secret cannot be opened. Was the key changed?", e);
        }
    }
}
