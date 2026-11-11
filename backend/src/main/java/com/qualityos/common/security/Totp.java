package com.qualityos.common.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238) as authenticator apps make them: HMAC-SHA1, six digits, thirty seconds.
 * A code is checked against the current step and one either side, for clocks that drift.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final Duration STEP = Duration.ofSeconds(30);

    private static final int SECRET_BYTES = 20;
    private static final int DRIFT_STEPS = 1;
    private static final String ALGORITHM = "HmacSHA1";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP.getSeconds());
    }

    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % (int) Math.pow(10, DIGITS));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }

    /** The step the code matches, or empty. The caller records the step, so that the same code is not taken twice. */
    public static OptionalLong matchingStep(byte[] secret, String code, Instant now) {
        if (code == null || code.length() != DIGITS) {
            return OptionalLong.empty();
        }
        byte[] presented = code.getBytes(StandardCharsets.US_ASCII);
        long current = step(now);
        for (long step = current - DRIFT_STEPS; step <= current + DRIFT_STEPS; step++) {
            if (MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII), presented)) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /** What an authenticator app scans. The secret appears in it, so it is shown once, at enrolment. */
    public static String otpauthUri(String issuer, String account, byte[] secret) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(account)
                + "?secret=" + base32(secret) + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP.getSeconds();
    }

    /** Base32 without padding, the form authenticator apps take by hand. */
    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(BASE32.charAt((buffer >> bits) & 0x1f));
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    public static byte[] fromBase32(String text) {
        String clean = text.replace(" ", "").replace("=", "").toUpperCase();
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out[index++] = (byte) ((buffer >> bits) & 0xff);
            }
        }
        return out;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
