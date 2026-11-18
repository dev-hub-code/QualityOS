package com.qualityos.common.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Files in one S3 bucket, shared by every instance. Credentials come from the default chain: the ECS task role in AWS.
 * The bucket encrypts at rest and blocks public access; the application never hands out S3 links, so every download
 * still passes its permission checks.
 */
@Component
@ConditionalOnProperty(name = "qualityos.storage.provider", havingValue = "s3")
class S3FileStorage implements FileStorage, DisposableBean {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    S3FileStorage(
            @Value("${qualityos.storage.s3.bucket:}") String bucket,
            @Value("${qualityos.storage.s3.prefix:}") String prefix,
            @Value("${qualityos.storage.s3.region:}") String region,
            @Value("${qualityos.storage.s3.endpoint:}") String endpoint) {
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("STORAGE_PROVIDER is s3, so STORAGE_S3_BUCKET must be set.");
        }
        this.bucket = bucket.trim();
        String folder = prefix == null ? "" : prefix.trim().replaceAll("^/+|/+$", "");
        this.prefix = folder.isEmpty() ? "" : folder + "/";
        S3ClientBuilder builder = S3Client.builder().httpClientBuilder(ApacheHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(30)));
        if (StringUtils.hasText(region)) {
            builder.region(Region.of(region.trim()));
        }
        if (StringUtils.hasText(endpoint)) {
            // An S3-compatible server, for tests: buckets are addressed by path, not by host name.
            builder.endpointOverride(URI.create(endpoint.trim())).forcePathStyle(true);
        }
        this.s3 = builder.build();
    }

    @Override
    public void store(String key, InputStream content) throws IOException {
        // S3 needs the length up front. A temporary file keeps a 20 MB upload off the heap, and reads the stream to
        // its end, which callers that hash it as it passes rely on.
        Path buffer = Files.createTempFile("qualityos-upload-", ".bin");
        try {
            Files.copy(content, buffer, StandardCopyOption.REPLACE_EXISTING);
            s3.putObject(put -> put.bucket(bucket).key(object(key)), RequestBody.fromFile(buffer));
        } catch (SdkException e) {
            throw new IOException("Could not store the file in S3", e);
        } finally {
            Files.deleteIfExists(buffer);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return s3.getObject(get -> get.bucket(bucket).key(object(key)));
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(key);
        } catch (SdkException e) {
            throw new IOException("Could not read the file from S3", e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            // S3 answers the same whether or not the key existed.
            s3.deleteObject(delete -> delete.bucket(bucket).key(object(key)));
        } catch (SdkException e) {
            throw new IOException("Could not delete the file from S3", e);
        }
    }

    private String object(String key) {
        if (!StringUtils.hasText(key) || key.startsWith("/") || key.contains("..") || key.contains("\\")) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return prefix + key;
    }

    @Override
    public void destroy() {
        s3.close();
    }
}
