package com.qualityos.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** The secret and times from RFC 6238's test vectors, with their eight-digit answers cut to six. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheReferenceVectors() {
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
    }

    @Test
    void acceptsTheNeighbouringStepsAndNothingFurther() {
        Instant now = Instant.ofEpochSecond(1234567890);
        long step = Totp.step(now);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step), now)).hasValue(step);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, "00592", now)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, null, now)).isEmpty();
    }

    @Test
    void base32RoundTripsAndReadsWhatPeopleType() {
        byte[] secret = Totp.newSecret();
        assertThat(secret).hasSize(20);
        String text = Totp.base32(secret);
        assertThat(text).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.fromBase32(text)).isEqualTo(secret);
        assertThat(Totp.fromBase32(text.toLowerCase().replaceAll("(.{4})", "$1 "))).isEqualTo(secret);
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    }

    @Test
    void theUriNamesTheIssuerAndAccountAndCarriesTheSecret() {
        byte[] secret = Totp.fromBase32("JBSWY3DPEHPK3PXP");
        assertThat(Totp.otpauthUri("ProductionOS", "asha@example.com", secret))
                .isEqualTo("otpauth://totp/ProductionOS:asha%40example.com?secret=JBSWY3DPEHPK3PXP"
                        + "&issuer=ProductionOS&algorithm=SHA1&digits=6&period=30");
    }
}
