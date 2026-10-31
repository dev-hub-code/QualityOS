package com.qualityos.common.attachment;

import java.io.IOException;
import java.io.InputStream;

/** Where attachment bytes live. Replaced by an S3 implementation when production deployment is built. */
public interface FileStorage {

    void store(String key, InputStream content) throws IOException;

    InputStream open(String key) throws IOException;
}
