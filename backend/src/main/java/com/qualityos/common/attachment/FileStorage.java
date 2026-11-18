package com.qualityos.common.attachment;

import java.io.IOException;
import java.io.InputStream;

/** Where attachment and report export bytes live: local disk ({@code local}) or S3 ({@code s3}), by profile. */
public interface FileStorage {

    void store(String key, InputStream content) throws IOException;

    /** @throws java.nio.file.NoSuchFileException if nothing is stored under the key */
    InputStream open(String key) throws IOException;

    /** Nothing happens if there is nothing under the key. */
    void delete(String key) throws IOException;
}
