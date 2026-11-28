/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.config;

import ai.philterd.batch.run.DocumentType;
import com.fasterxml.jackson.annotation.JsonValue;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A run's configuration, read from YAML. Every setting but the run ID, the input and output locations, and the
 * policy has a default. See {@code docs/configuration.md}.
 */
public record RunConfig(
        String runId,
        Store store,
        Location input,
        Output output,
        String policy,
        String modelsDirectory,
        List<String> policyFilesDirectories,
        PlainText plainText,
        InputTypes inputTypes,
        Inventory inventory,
        Lease lease,
        Worker worker,
        Sample sample,
        ReleaseGate releaseGate,
        Quarantine quarantine,
        Pseudonymization pseudonymization) {

    public RunConfig {
        store = store == null ? new Store(null, null, null, null) : store;
        output = output == null ? new Output(null, null, null) : output;
        modelsDirectory = modelsDirectory == null ? "/opt/philter-batch/models" : modelsDirectory;
        policyFilesDirectories = policyFilesDirectories == null ? List.of() : List.copyOf(policyFilesDirectories);
        plainText = plainText == null ? new PlainText(null, null) : plainText;
        inputTypes = inputTypes == null ? new InputTypes(null, null, null) : inputTypes;
        inventory = inventory == null ? new Inventory(null, null, null, null, null) : inventory;
        lease = lease == null ? new Lease(null, null, null, null, null, null) : lease;
        worker = worker == null ? new Worker(null, null, null, null, null, null) : worker;
        sample = sample == null ? new Sample(null, null, null, null, null, null) : sample;
        releaseGate = releaseGate == null ? new ReleaseGate(null) : releaseGate;
        quarantine = quarantine == null ? new Quarantine(null) : quarantine;
        pseudonymization = pseudonymization == null ? new Pseudonymization(null, null) : pseudonymization;
    }

    /** The extensions of a type: {@code plain_text.extensions} for plain text, else its configured or default ones. */
    public List<String> extensions(final DocumentType type) {
        return type == DocumentType.TEXT ? plainText.extensions()
                : inputTypes.extensions().getOrDefault(type.json(), type.defaultExtensions());
    }

    /** This configuration with the worker's settings replaced, for the per-pod settings a deployment sets. */
    public RunConfig withWorker(final Worker replacement) {
        return new RunConfig(runId, store, input, output, policy, modelsDirectory, policyFilesDirectories, plainText,
                inputTypes, inventory, lease, replacement, sample, releaseGate, quarantine, pseudonymization);
    }

    /** This configuration with the store and output location replaced. */
    public RunConfig withLocation(final Store newStore, final Output newOutput) {
        return new RunConfig(runId, newStore, input, newOutput, policy, modelsDirectory, policyFilesDirectories,
                plainText, inputTypes, inventory, lease, worker, sample, releaseGate, quarantine, pseudonymization);
    }

    /**
     * The sample gate: a sample of the input is redacted first, and the rest waits until a person approves it.
     * @param enabled Whether there is a sample and a gate. On by default.
     * @param size The most entries in the sample.
     * @param seed The seed of the random choice; by default derived from the run ID, so a rerun picks the same.
     * @param reportMaxChars The most characters of each sampled entry's text shown in the HTML report.
     * @param pricePerWorkerHour The price of one worker pod for an hour; when set, the report estimates the cost.
     * @param currency The currency of the price, as shown in the report.
     */
    public record Sample(Boolean enabled, Integer size, Long seed, Integer reportMaxChars, Double pricePerWorkerHour,
                         String currency) {
        public Sample {
            enabled = enabled == null || enabled;
            size = size == null ? 50 : size;
            reportMaxChars = reportMaxChars == null ? 20_000 : reportMaxChars;
            currency = currency == null ? "USD" : currency;
        }
    }

    /**
     * The secret that keys {@code FPE_ENCRYPT_REPLACE} and {@code CRYPTO_REPLACE}: an AWS Secrets Manager secret
     * holding one random value of at least 32 bytes, from which the keys are derived. A policy never holds a key.
     * @param secretArn The secret's ARN. Unset: a policy using those strategies is refused.
     * @param endpoint A Secrets Manager endpoint instead of AWS's, for testing.
     */
    public record Pseudonymization(String secretArn, String endpoint) {

        public boolean configured() {
            return secretArn != null && !secretArn.isBlank();
        }

        /** The region in the ARN. */
        public String region() {
            return secretArn.split(":", 6)[3];
        }
    }

    /**
     * The release gate: after finalize, the run waits until a person approves its output.
     * @param enabled On by default.
     */
    public record ReleaseGate(Boolean enabled) {
        public ReleaseGate {
            enabled = enabled == null || enabled;
        }
    }

    /**
     * The quarantine limit: when more of a run's entries than this are quarantined, the release gate refuses a plain
     * approval, and without a release gate the run ends with exit code 6.
     * @param maxPercent Quarantined entries as a percentage of all entries, from 0 to 100. 5 by default.
     */
    public record Quarantine(Double maxPercent) {
        public Quarantine {
            maxPercent = maxPercent == null ? 5.0 : maxPercent;
        }

        /** Whether {@code quarantined} of {@code entries} is above the limit. */
        public boolean exceeded(final long quarantined, final long entries) {
            return entries > 0 && percent(quarantined, entries) > maxPercent;
        }

        public static double percent(final long quarantined, final long entries) {
            return entries == 0 ? 0 : 100.0 * quarantined / entries;
        }
    }

    /**
     * The object store.
     * @param type {@code s3}, the default and, for now, the only store.
     * @param region The AWS region; the SDK's default when unset.
     * @param endpoint An S3-compatible endpoint, such as MinIO's, instead of AWS's.
     * @param pathStyle Path-style addressing, which S3-compatible stores usually need.
     */
    public record Store(String type, String region, String endpoint, Boolean pathStyle) {
        public Store {
            type = type == null ? "s3" : type.toLowerCase(Locale.ROOT);
            pathStyle = pathStyle != null && pathStyle;
        }
    }

    /** A bucket and a key prefix in it. The prefix may be empty. */
    public record Location(String bucket, String prefix) {
        public Location {
            prefix = prefix == null ? "" : prefix;
        }
    }

    /** What a run writes. */
    public enum OutputMode {
        /** Redacted text as JSONL records. */
        TEXT,
        /** A redacted file of the same type as each input. */
        DOCUMENT,
        /** Both, from one detection pass. */
        BOTH;

        public boolean text() {
            return this != DOCUMENT;
        }

        public boolean document() {
            return this != TEXT;
        }

        @JsonValue
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Where and what a run writes. Everything goes under {@code <prefix>runs/<run_id>/}. */
    public record Output(String bucket, String prefix, OutputMode mode) {
        public Output {
            prefix = prefix == null ? "" : prefix;
            mode = mode == null ? OutputMode.TEXT : mode;
        }
    }

    /**
     * Which input objects are plain text, and how they are decoded.
     * @param extensions File extensions, without the dot, compared ignoring case. Every other object is skipped.
     * @param charset The input's character set. A file that does not decode in it is quarantined.
     */
    public record PlainText(List<String> extensions, String charset) {
        public static final List<String> DEFAULT_EXTENSIONS = List.of("txt", "md", "log");

        public PlainText {
            extensions = extensions == null ? DEFAULT_EXTENSIONS
                    : extensions.stream().map(e -> e.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "")).toList();
            charset = charset == null ? "UTF-8" : charset;
        }

        public Charset charsetValue() {
            return Charset.forName(charset);
        }
    }

    /**
     * The input types a run redacts, beyond plain text. See {@code docs/configuration.md}.
     * @param handled The types redacted, by name ({@code text}, {@code csv}, {@code pdf}, {@code docx}, {@code xlsx},
     *                {@code rtf}, {@code email}). Plain text only by default.
     * @param extensions Extensions per type other than {@code text}, replacing that type's defaults. Plain text's
     *                   are {@code plain_text.extensions}.
     * @param maxDocumentBytes Files of a type other than plain text larger than this are skipped. They are read
     *                         whole, unlike plain text, which is split into segments.
     */
    public record InputTypes(List<String> handled, Map<String, List<String>> extensions, Long maxDocumentBytes) {
        public InputTypes {
            handled = handled == null ? List.of(DocumentType.TEXT.json())
                    : handled.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
            final Map<String, List<String>> normalized = new TreeMap<>();
            if (extensions != null) {
                extensions.forEach((type, list) -> normalized.put(type.toLowerCase(Locale.ROOT), list == null
                        ? List.of() : list.stream().map(e -> e.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "")).toList()));
            }
            extensions = Collections.unmodifiableMap(normalized);
            maxDocumentBytes = maxDocumentBytes == null ? 256L * 1024 * 1024 : maxDocumentBytes;
        }

        /** The handled types, in a fixed order. Names that are not types are left out; validation refuses them. */
        public Set<DocumentType> types() {
            final Set<DocumentType> types = EnumSet.noneOf(DocumentType.class);
            handled.forEach(name -> DocumentType.of(name).ifPresent(types::add));
            return types;
        }

        /** Whether any handled type is not plain text. */
        public boolean beyondPlainText() {
            return types().stream().anyMatch(t -> t != DocumentType.TEXT);
        }
    }

    /**
     * How the input is divided into shards.
     * @param shardTargetBytes The input bytes per shard.
     * @param maxEntriesPerShard The most files or segments per shard, which bounds a shard's progress object.
     * @param segmentBytes Files larger than this are split into segments of about this size, at line breaks.
     * @param maxFileBytes Files larger than this are skipped.
     * @param maxLineBytes The longest line a segment may end with; a longer one quarantines the segment.
     */
    public record Inventory(Long shardTargetBytes, Integer maxEntriesPerShard, Long segmentBytes, Long maxFileBytes,
                            Long maxLineBytes) {
        public Inventory {
            shardTargetBytes = shardTargetBytes == null ? 128L * 1024 * 1024 : shardTargetBytes;
            maxEntriesPerShard = maxEntriesPerShard == null ? 10_000 : maxEntriesPerShard;
            segmentBytes = segmentBytes == null ? 16L * 1024 * 1024 : segmentBytes;
            maxFileBytes = maxFileBytes == null ? 50L * 1024 * 1024 * 1024 : maxFileBytes;
            maxLineBytes = maxLineBytes == null ? 16L * 1024 * 1024 : maxLineBytes;
        }
    }

    /**
     * Shard leases, timed by the store's clock.
     * @param initialSeconds The first lease on a shard. Each takeover after an expiry doubles it.
     * @param maxSeconds The longest lease.
     * @param renewSeconds How often a holder renews.
     * @param expiryGraceSeconds How long past its expiry a claim must be before it is taken over, to absorb the
     *                           one-second resolution of the {@code Date} header.
     * @param maxClockSkewSeconds The largest difference between this process's clock and the store's that is
     *                            accepted. Leases use the store's clock either way; a larger difference is
     *                            refused as a sign of a misconfigured node.
     * @param poisonAttempts A shard whose cursor does not move across this many expired leases has the file at
     *                       its cursor quarantined.
     */
    public record Lease(Integer initialSeconds, Integer maxSeconds, Integer renewSeconds, Integer expiryGraceSeconds,
                        Integer maxClockSkewSeconds, Integer poisonAttempts) {
        public Lease {
            initialSeconds = initialSeconds == null ? 600 : initialSeconds;
            maxSeconds = maxSeconds == null ? 86_400 : maxSeconds;
            renewSeconds = renewSeconds == null ? 30 : renewSeconds;
            expiryGraceSeconds = expiryGraceSeconds == null ? 5 : expiryGraceSeconds;
            maxClockSkewSeconds = maxClockSkewSeconds == null ? 300 : maxClockSkewSeconds;
            poisonAttempts = poisonAttempts == null ? 3 : poisonAttempts;
        }
    }

    /**
     * The worker.
     * @param threads Shards processed at once in one process.
     * @param onnxThreads ONNX Runtime's intra-op threads per model call. Defaults to the processors divided by
     *                    {@code threads}, so the threads do not oversubscribe the CPUs.
     * @param flushBytes Buffered JSONL output that triggers an upload of a part and a progress write.
     * @param flushSeconds The longest time between progress writes while a shard is processed.
     * @param fileTimeoutSeconds The longest redaction of one file or segment; a longer one is quarantined.
     * @param shutdownGraceSeconds How long, after SIGTERM, the worker waits for the current files to finish.
     */
    public record Worker(Integer threads, Integer onnxThreads, Long flushBytes, Integer flushSeconds,
                         Integer fileTimeoutSeconds, Integer shutdownGraceSeconds) {
        public Worker {
            threads = threads == null ? Math.max(1, Runtime.getRuntime().availableProcessors() / 2) : threads;
            onnxThreads = onnxThreads == null ? Math.max(1, Runtime.getRuntime().availableProcessors() / threads)
                    : onnxThreads;
            flushBytes = flushBytes == null ? 8L * 1024 * 1024 : flushBytes;
            flushSeconds = flushSeconds == null ? 60 : flushSeconds;
            fileTimeoutSeconds = fileTimeoutSeconds == null ? 600 : fileTimeoutSeconds;
            shutdownGraceSeconds = shutdownGraceSeconds == null ? 25 : shutdownGraceSeconds;
        }
    }

}
