package com.qualityos.common.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Against a real S3-compatible server in a container, with the AWS SDK speaking the S3 protocol to it. */
class S3FileStorageIT {

    private static final String BUCKET = "qualityos-files";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:4.7.0")
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
            .withExposedPorts(9090)
            .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching(code -> code < 500));

    private static S3FileStorage storage;
    private static S3Client inspect;

    @BeforeAll
    static void start() {
        // The SDK's default chain finds these, as it finds the task role in AWS.
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        S3.start();
        String endpoint = "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
        storage = new S3FileStorage(BUCKET, "uat/", "ap-south-1", endpoint);
        inspect = S3Client.builder().endpointOverride(URI.create(endpoint)).forcePathStyle(true)
                .region(Region.AP_SOUTH_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @AfterAll
    static void stop() {
        storage.destroy();
        inspect.close();
        S3.stop();
    }

    @Test
    void storesReadsAndDeletesUnderThePrefix() throws IOException {
        byte[] file = new byte[3 * 1024 * 1024 + 17];
        new Random(7).nextBytes(file);

        storage.store("tenant-a/report.pdf", new ByteArrayInputStream(file));

        try (InputStream read = storage.open("tenant-a/report.pdf")) {
            assertThat(read.readAllBytes()).isEqualTo(file);
        }
        assertThat(inspect.headObject(head -> head.bucket(BUCKET).key("uat/tenant-a/report.pdf")).contentLength())
                .isEqualTo(file.length);

        storage.delete("tenant-a/report.pdf");
        assertThatThrownBy(() -> storage.open("tenant-a/report.pdf")).isInstanceOf(NoSuchFileException.class);
        // Deleting again is quiet, as on local disk.
        storage.delete("tenant-a/report.pdf");
    }

    @Test
    void readsTheWholeStreamSoAHashTakenOnTheWayIsComplete() throws Exception {
        byte[] file = "evidence".repeat(10_000).getBytes();
        DigestInputStream hashing = new DigestInputStream(new ByteArrayInputStream(file),
                MessageDigest.getInstance("SHA-256"));

        storage.store("tenant-a/evidence.png", hashing);

        assertThat(HexFormat.of().formatHex(hashing.getMessageDigest().digest()))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file)));
    }

    @Test
    void replacingAKeyKeepsOnlyTheNewContent() throws IOException {
        storage.store("tenant-a/export.xlsx", new ByteArrayInputStream("old".getBytes()));
        storage.store("tenant-a/export.xlsx", new ByteArrayInputStream("new".getBytes()));

        try (InputStream read = storage.open("tenant-a/export.xlsx")) {
            assertThat(new String(read.readAllBytes())).isEqualTo("new");
        }
    }

    @Test
    void refusesKeysThatCouldEscapeThePrefix() {
        for (String key : new String[] {"../other/x", "/abs", "a\\b", ""}) {
            assertThatThrownBy(() -> storage.store(key, new ByteArrayInputStream(new byte[1])))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anUnreachableBucketIsAnIoErrorNotACrash() {
        S3FileStorage missing = new S3FileStorage("no-such-bucket", "", "ap-south-1",
                "http://" + S3.getHost() + ":" + S3.getMappedPort(9090));
        assertThatThrownBy(() -> missing.store("k", new ByteArrayInputStream(new byte[1])))
                .isInstanceOf(IOException.class);
        missing.destroy();
    }

    @Test
    void aPrefixIsOneFolderHoweverItIsWritten() throws IOException {
        String endpoint = "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
        for (String prefix : new String[] {"sit", "/sit/", "sit//"}) {
            S3FileStorage folder = new S3FileStorage(BUCKET, prefix, "ap-south-1", endpoint);
            folder.store("k.txt", new ByteArrayInputStream("x".getBytes()));
            folder.destroy();
        }
        assertThat(inspect.listObjectsV2(list -> list.bucket(BUCKET).prefix("sit")).contents())
                .extracting(object -> object.key()).containsExactly("sit/k.txt");
    }

    @Test
    void willNotStartWithoutABucket() {
        assertThatThrownBy(() -> new S3FileStorage(" ", "", "ap-south-1", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STORAGE_S3_BUCKET");
    }
}
