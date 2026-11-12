package com.qualityos.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void sealsAndOpensWithDifferentCiphertextEachTime() {
        SecretBox box = SecretBox.from(KEY, false);
        byte[] secret = "twenty-byte-secret!!".getBytes(StandardCharsets.US_ASCII);
        byte[] first = box.seal(secret);
        byte[] second = box.seal(secret);
        assertThat(first).isNotEqualTo(second);
        assertThat(box.open(first)).isEqualTo(secret);
        assertThat(box.open(second)).isEqualTo(secret);
    }

    @Test
    void aChangedByteOrAnotherKeyIsRefused() {
        SecretBox box = SecretBox.from(KEY, false);
        byte[] sealed = box.seal("secret".getBytes(StandardCharsets.US_ASCII));
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> box.open(sealed)).isInstanceOf(IllegalStateException.class);

        byte[] other = new byte[32];
        other[0] = 1;
        SecretBox otherBox = SecretBox.from(Base64.getEncoder().encodeToString(other), false);
        assertThatThrownBy(() -> otherBox.open(box.seal("secret".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingKeyIsOnlyAcceptedWhereEphemeralKeysAre() {
        assertThatThrownBy(() -> SecretBox.from("", false)).hasMessageContaining("SECOND_FACTOR_KEY");
        assertThatThrownBy(() -> SecretBox.from(Base64.getEncoder().encodeToString(new byte[16]), false))
                .hasMessageContaining("32 bytes");
        SecretBox ephemeral = SecretBox.from(null, true);
        assertThat(ephemeral.open(ephemeral.seal(new byte[] {1, 2, 3}))).containsExactly(1, 2, 3);
    }
}
