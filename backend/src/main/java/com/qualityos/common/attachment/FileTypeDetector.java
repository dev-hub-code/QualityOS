package com.qualityos.common.attachment;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/** Identifies a file from its leading bytes. The browser's declared type and the extension are not trusted. */
final class FileTypeDetector {

    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "application/pdf", new byte[] {'%', 'P', 'D', 'F', '-'},
            "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
            "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "application/zip", new byte[] {'P', 'K', 0x03, 0x04});

    private FileTypeDetector() {
    }

    /** Office documents are zip files, and are served as plain downloads either way. */
    static Optional<String> detect(byte[] header) {
        return SIGNATURES.entrySet().stream()
                .filter(entry -> startsWith(header, entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length
                && Arrays.equals(Arrays.copyOf(bytes, prefix.length), prefix);
    }
}
