/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.Fixtures;
import ai.philterd.batch.PhilterBatch;
import ai.philterd.batch.config.ConfigLoader;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.coordination.ClaimResult;
import ai.philterd.batch.coordination.Coordinator;
import ai.philterd.batch.coordination.StoreClock;
import ai.philterd.batch.inventory.Inventory;
import ai.philterd.batch.run.Formats.Success;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the S3 store, the coordination, and a whole run against RustFS, an S3-compatible server that enforces
 * conditional PutObject the way S3 does. MinIO also enforced it when checked on 2026-11-25 (412 on a repeated
 * {@code If-None-Match: *} and on a stale {@code If-Match}), but its images are no longer publicly pullable, so CI
 * cannot use it.
 */
@Testcontainers
class S3ObjectStoreIT {

    private static final String IMAGE = "rustfs/rustfs:1.0.6";
    private static final String ACCESS = "testaccesskey";
    private static final String SECRET = "testsecretkey";

    private static GenericContainer<?> server;
    private static String endpoint;
    private static ObjectStore store;

    @BeforeAll
    static void start() throws Exception {
        server = new GenericContainer<>(IMAGE).withExposedPorts(9000)
                .withEnv("RUSTFS_ACCESS_KEY", ACCESS).withEnv("RUSTFS_SECRET_KEY", SECRET)
                .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(2));
        server.start();
        endpoint = "http://" + server.getHost() + ":" + server.getMappedPort(9000);
        System.setProperty("aws.accessKeyId", ACCESS);
        System.setProperty("aws.secretAccessKey", SECRET);
        final RunConfig.Store config = new RunConfig.Store("s3", "us-east-1", endpoint, true);
        store = ObjectStores.create(config, 16);
        try (software.amazon.awssdk.services.s3.S3Client s3 = software.amazon.awssdk.services.s3.S3Client.builder()
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).endpointOverride(java.net.URI.create(endpoint))
                .forcePathStyle(true).build()) {
            for (final String bucket : List.of(Fixtures.INPUT, Fixtures.OUTPUT, "scratch")) {
                for (int attempt = 0; ; attempt++) {
                    try {
                        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                        break;
                    } catch (final RuntimeException e) {
                        if (attempt > 30) {
                            throw e;
                        }
                        Thread.sleep(500);
                    }
                }
            }
        }
    }

    @AfterAll
    static void stop() {
        if (store != null) {
            store.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void enforcesConditionalWrites() {
        final PutResult first = store.putIfAbsent("scratch", "cond/a", b("one"), "text/plain");
        assertNotNull(first.etag());
        assertNotNull(first.serverTime(), "the store's Date header is the lease clock");
        assertThrows(PreconditionFailedException.class, () -> store.putIfAbsent("scratch", "cond/a", b("two"), "text/plain"));
        assertThrows(PreconditionFailedException.class, () -> store.putIfMatch("scratch", "cond/a", b("two"), "text/plain",
                "\"00000000000000000000000000000000\""));
        assertThrows(PreconditionFailedException.class, () -> store.putIfMatch("scratch", "cond/absent", b("two"),
                "text/plain", first.etag()));
        final PutResult second = store.putIfMatch("scratch", "cond/a", b("two"), "text/plain", first.etag());
        assertThrows(PreconditionFailedException.class, () -> store.putIfMatch("scratch", "cond/a", b("three"),
                "text/plain", first.etag()));
        assertEquals("two", new String(store.get("scratch", "cond/a").orElseThrow().data(), StandardCharsets.UTF_8));
        assertEquals(second.etag(), store.get("scratch", "cond/a").orElseThrow().etag());
        assertTrue(store.get("scratch", "cond/missing").isEmpty());
        assertThrows(PreconditionFailedException.class, () -> store.getIfMatch("scratch", "cond/a", first.etag()));
        assertThrows(ObjectNotFoundException.class, () -> store.getIfMatch("scratch", "cond/missing", first.etag()));
        final Instant serverTime = store.serverTime("scratch");
        assertTrue(Duration.between(Instant.now(), serverTime).abs().toSeconds() < 30);
    }

    @Test
    void readsRangesAndLists() throws Exception {
        final String etag = store.put("scratch", "range/k", b("0123456789"), "text/plain").etag();
        try (InputStream in = store.openRange("scratch", "range/k", etag, 2, 4)) {
            assertEquals("234", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = store.openRange("scratch", "range/k", etag, 8, 100)) {
            assertEquals("89", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThrows(PreconditionFailedException.class, () -> store.openRange("scratch", "range/k", "\"stale\"", 0, 1));
        store.put("scratch", "range/j", b("x"), "text/plain");
        assertEquals(List.of("range/j", "range/k"), store.list("scratch", "range/").stream().map(ObjectInfo::key).toList());
        store.delete("scratch", "range/j");
        store.delete("scratch", "range/never-existed");
        assertEquals(1, store.list("scratch", "range/").size());
    }

    @Test
    void composesSmallAndLargeSources() {
        // Small sources: streamed through this process.
        store.put("scratch", "compose/a", b("alpha,"), "text/plain");
        store.put("scratch", "compose/b", b(""), "text/plain");
        store.put("scratch", "compose/c", b("gamma"), "text/plain");
        store.compose("scratch", "compose/out", List.of("compose/a", "compose/b", "compose/c"), "text/plain");
        assertEquals("alpha,gamma", new String(store.get("scratch", "compose/out").orElseThrow().data(),
                StandardCharsets.UTF_8));
        // One source: a server-side copy.
        store.compose("scratch", "compose/one", List.of("compose/c"), "text/plain");
        assertEquals("gamma", new String(store.get("scratch", "compose/one").orElseThrow().data(), StandardCharsets.UTF_8));
        // Sources over S3's minimum part size: copied server-side as parts.
        final byte[] big = new byte[(int) S3ObjectStore.MIN_PART_BYTES + 10];
        new Random(7).nextBytes(big);
        store.put("scratch", "compose/big1", big, "application/octet-stream");
        store.put("scratch", "compose/big2", big, "application/octet-stream");
        store.put("scratch", "compose/tail", b("end"), "application/octet-stream");
        store.compose("scratch", "compose/bigout", List.of("compose/big1", "compose/big2", "compose/tail"),
                "application/octet-stream");
        final byte[] out = store.get("scratch", "compose/bigout").orElseThrow().data();
        assertEquals(big.length * 2 + 3, out.length);
        assertArrayEquals(big, Arrays.copyOfRange(out, 0, big.length));
        assertArrayEquals(big, Arrays.copyOfRange(out, big.length, big.length * 2));
        assertThrows(ObjectNotFoundException.class, () -> store.compose("scratch", "compose/x",
                List.of("compose/missing"), "text/plain"));
        store.compose("scratch", "compose/empty", List.of(), "text/plain");
        assertEquals(0, store.get("scratch", "compose/empty").orElseThrow().data().length);
    }

    @Test
    void claimantsRacingOnARealStoreGetOneWinnerEachRound() throws Exception {
        final RunConfig config = ConfigLoader.parse("""
                run_id: race-run
                input: {bucket: input-bucket, prefix: race-in/}
                output: {bucket: output-bucket, prefix: race-out/}
                lease: {initial_seconds: 60, renew_seconds: 10}
                """);
        store.put(Fixtures.INPUT, "race-in/a.txt", b("x"), "text/plain");
        new Inventory(store, config, Clock.systemUTC()).run(b(Fixtures.REGEX_POLICY));
        final RunLayout layout = new RunLayout(config);
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 20; round++) {
                store.delete(layout.bucket(), layout.claim("000000"));
                final CyclicBarrier barrier = new CyclicBarrier(8);
                final List<Future<ClaimResult>> futures = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    final Coordinator c = new Coordinator(store, layout, config.lease(), new StoreClock(store,
                            layout.bucket(), Clock.systemUTC(), Duration.ofMinutes(5)), new Random(round * 8L + t));
                    final String holder = "w" + t;
                    futures.add(pool.submit(() -> {
                        barrier.await();
                        return c.claim(holder);
                    }));
                }
                int won = 0;
                for (final Future<ClaimResult> f : futures) {
                    if (f.get() instanceof ClaimResult.Claimed) {
                        won++;
                    }
                }
                assertEquals(1, won, "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void runsAWholeRunAndPassesTheStoreCheck(@TempDir final Path dir) throws Exception {
        for (int i = 0; i < 12; i++) {
            store.put(Fixtures.INPUT, "e2e/folder " + i + "/Example Person " + i + ".txt",
                    b("Contact person" + i + "@example.com about case " + i + ".\n".repeat(1 + i)), "text/plain");
        }
        final StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            big.append("row ").append(i).append(" ssn 123-45-").append(String.format("%04d", 1000 + i)).append('\n');
        }
        store.put(Fixtures.INPUT, "e2e/big.log", b(big.toString()), "text/plain");
        store.put(Fixtures.INPUT, "e2e/skip.docx", b("not handled"), "application/octet-stream");
        final Path policy = dir.resolve("policy.json");
        Files.writeString(policy, Fixtures.REGEX_POLICY);
        final Path config = dir.resolve("run.yaml");
        Files.writeString(config, """
                run_id: e2e-run
                store: {region: us-east-1, endpoint: "%s", path_style: true}
                input: {bucket: input-bucket, prefix: e2e/}
                output: {bucket: output-bucket, prefix: e2e-out/, mode: both}
                policy: %s
                inventory: {shard_target_bytes: 2000, segment_bytes: 3000, max_line_bytes: 200}
                lease: {initial_seconds: 60, renew_seconds: 10}
                worker: {threads: 3, onnx_threads: 1, flush_bytes: 500}
                sample: {size: 4}
                """.formatted(endpoint, policy));

        // The sample first, its report, and approval through a marker object; then run resumes past the gate.
        final String c = config.toString();
        assertEquals(ExitCodes.OK, PhilterBatch.execute("check-store", "-c", c));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("inventory", "-c", c));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("sample", "-c", c, "--worker-pods", "2"));
        final RunLayout layout = new RunLayout("output-bucket", "e2e-out/", "e2e-run");
        final String html = new String(store.get(layout.bucket(), layout.sampleReportHtml()).orElseThrow().data(),
                StandardCharsets.UTF_8);
        assertFalse(html.contains("@example.com") || html.contains("123-45-") || html.contains("Example Person"));
        assertTrue(store.get(layout.bucket(), layout.sampleReport()).isPresent());
        assertEquals(ExitCodes.ERROR, PhilterBatch.execute("work", "-c", c), "waits for approval");
        assertEquals(ExitCodes.OK, PhilterBatch.execute("approve", "-c", c, "sample"));
        assertEquals(ExitCodes.OK, PhilterBatch.execute("run", "-c", c));

        final Success success = Json.read(store.get(layout.bucket(), layout.success()).orElseThrow().data(), Success.class);
        assertEquals(13, success.counts().documents());
        assertEquals(13, success.counts().documentsWritten());
        assertEquals(java.util.Map.of("UNSUPPORTED_TYPE", 1L), success.counts().skippedByReason());
        final StringBuilder output = new StringBuilder();
        for (final ObjectInfo o : store.list(layout.bucket(), layout.partsPrefix())) {
            output.append(new String(store.get(layout.bucket(), o.key()).orElseThrow().data(), StandardCharsets.UTF_8));
        }
        for (final ObjectInfo o : store.list(layout.bucket(), layout.documentsPrefix())) {
            output.append(new String(store.get(layout.bucket(), o.key()).orElseThrow().data(), StandardCharsets.UTF_8));
        }
        final String all = output.toString();
        assertFalse(all.contains("@example.com"));
        assertFalse(all.contains("123-45-"));
        assertFalse(all.contains("Example Person"));
        assertTrue(all.contains("{{{REDACTED-ssn}}}"));
        // The segmented file was reassembled server-side into one document of the same lines.
        final String bigDoc = store.list(layout.bucket(), layout.documentsPrefix()).stream()
                .map(o -> new String(store.get(layout.bucket(), o.key()).orElseThrow().data(), StandardCharsets.UTF_8))
                .filter(s -> s.startsWith("row 0 ")).findFirst().orElseThrow();
        assertEquals(big.toString().replaceAll("123-45-\\d{4}", "{{{REDACTED-ssn}}}"), bigDoc);
        assertTrue(store.list(layout.bucket(), layout.stagingPrefix()).isEmpty());
    }

}
