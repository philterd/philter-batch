/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Reads and checks a run's YAML configuration. */
public final class ConfigLoader {

    /** A run ID: letters, digits, dots, underscores, and hyphens, starting with a letter or digit. */
    public static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** An extension in plain_text.extensions. */
    public static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,10}");

    private static final ObjectMapper YAML = JsonMapper.builder(new YAMLFactory())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private ConfigLoader() {
    }

    public static RunConfig load(final Path path) {
        try {
            return parse(Files.readString(path));
        } catch (final IOException e) {
            throw new ConfigException("Could not read the configuration file: " + e.getMessage(), e);
        }
    }

    public static RunConfig parse(final String yaml) {
        final RunConfig config;
        try {
            config = YAML.readValue(yaml, RunConfig.class);
        } catch (final IOException e) {
            throw invalid(e);
        }
        if (config == null) {
            throw new ConfigException("The configuration is empty.");
        }
        validate(config);
        return config;
    }

    /**
     * Settings a deployment sets for each pod, which a run's stored configuration must leave out. {@code policy} is
     * stored beside the configuration instead.
     */
    static final List<String> SET_BY_DEPLOYMENT = List.of("/policy", "/worker/threads", "/worker/onnx_threads",
            "/worker/shutdown_grace_seconds");

    /**
     * A run's configuration as stored in a deployment ({@code config/run.yaml}): the deployment's store and output
     * location fill in what the configuration leaves out, and must equal what it gives.
     */
    public static RunConfig parseStored(final String yaml, final Deployment deployment) {
        final JsonNode tree;
        try {
            tree = YAML.readTree(yaml);
        } catch (final IOException e) {
            throw invalid(e);
        }
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            throw new ConfigException("The configuration is empty.");
        }
        if (!tree.isObject()) {
            throw new ConfigException("The configuration must be a YAML mapping.");
        }
        final ObjectNode node = (ObjectNode) tree;
        for (final String pointer : SET_BY_DEPLOYMENT) {
            if (!node.at(pointer).isMissingNode()) {
                throw new ConfigException(pointer.substring(1).replace('/', '.') + " is set by the deployment, not by "
                        + "the run's configuration: " + (pointer.equals("/policy")
                        ? "store the policy with set-policy." : "remove it (the Helm chart's workers values set it)."));
            }
        }
        if (!node.has("store")) {
            node.set("store", YAML.valueToTree(deployment.store()));
        }
        final JsonNode output = node.get("output");
        final ObjectNode out = output instanceof ObjectNode o ? o : node.putObject("output");
        if (output != null && !output.isObject() && !output.isNull()) {
            throw new ConfigException("output must be a mapping.");
        }
        if (!out.has("bucket")) {
            out.put("bucket", deployment.output().bucket());
        }
        if (!out.has("prefix")) {
            out.put("prefix", deployment.output().prefix());
        }
        final RunConfig config;
        try {
            config = YAML.treeToValue(node, RunConfig.class);
        } catch (final IOException e) {
            throw invalid(e);
        }
        validate(config);
        if (!config.store().equals(deployment.store())) {
            throw new ConfigException("store differs from the deployment's store; leave it out of the configuration.");
        }
        if (!config.output().bucket().equals(deployment.output().bucket())
                || !config.output().prefix().equals(deployment.output().prefix())) {
            throw new ConfigException("output.bucket and output.prefix differ from the deployment's ("
                    + deployment.output().bucket() + ", " + deployment.output().prefix() + "); leave them out.");
        }
        return config;
    }

    /** Reads and checks a deployment file. */
    public static Deployment loadDeployment(final Path path) {
        final Deployment deployment;
        try {
            deployment = YAML.readValue(Files.readString(path), Deployment.class);
        } catch (final IOException e) {
            throw new ConfigException("The deployment file " + path + " is not valid: "
                    + (e instanceof JsonProcessingException j ? j.getOriginalMessage() : e.getMessage()), e);
        }
        if (deployment == null || deployment.output() == null || blank(deployment.output().bucket())) {
            throw new ConfigException("The deployment file " + path + " needs output.bucket.");
        }
        if (!deployment.store().type().equals("s3")) {
            throw new ConfigException("The deployment's store.type must be s3 (the only store in this version).");
        }
        return deployment;
    }

    private static ConfigException invalid(final IOException e) {
        return new ConfigException("The configuration is not valid: "
                + (e instanceof JsonProcessingException j ? j.getOriginalMessage() : e.getMessage()), e);
    }

    public static void validate(final RunConfig c) {
        if (c.runId() == null || !RUN_ID.matcher(c.runId()).matches()) {
            throw new ConfigException("run_id is required: letters, digits, '.', '_', and '-', up to 64 characters.");
        }
        if (!c.store().type().equals("s3")) {
            throw new ConfigException("store.type must be s3 (the only store in this version).");
        }
        if (c.input() == null || blank(c.input().bucket())) {
            throw new ConfigException("input.bucket is required.");
        }
        if (blank(c.output().bucket())) {
            throw new ConfigException("output.bucket is required.");
        }
        if (c.input().bucket().equals(c.output().bucket())
                && (c.input().prefix().startsWith(c.output().prefix()) || c.output().prefix().startsWith(c.input().prefix()))) {
            throw new ConfigException("The input and output prefixes overlap in one bucket, so a run would read its own output.");
        }
        if (c.plainText().extensions().isEmpty()) {
            throw new ConfigException("plain_text.extensions must name at least one extension.");
        }
        for (final String extension : c.plainText().extensions()) {
            if (!EXTENSION.matcher(extension).matches()) {
                throw new ConfigException("plain_text.extensions: '" + extension + "' must be 1 to 10 letters or digits.");
            }
        }
        try {
            final Charset charset = c.plainText().charsetValue();
            if (!charset.canEncode()) {
                throw new ConfigException("plain_text.charset " + charset + " cannot encode, so document output cannot be written.");
            }
        } catch (final IllegalArgumentException e) {
            throw new ConfigException("plain_text.charset is not a supported character set: " + c.plainText().charset());
        }
        final RunConfig.Inventory i = c.inventory();
        positive("inventory.shard_target_bytes", i.shardTargetBytes());
        positive("inventory.max_entries_per_shard", i.maxEntriesPerShard());
        positive("inventory.segment_bytes", i.segmentBytes());
        positive("inventory.max_file_bytes", i.maxFileBytes());
        positive("inventory.max_line_bytes", i.maxLineBytes());
        final RunConfig.Lease l = c.lease();
        positive("lease.initial_seconds", l.initialSeconds());
        positive("lease.renew_seconds", l.renewSeconds());
        positive("lease.poison_attempts", l.poisonAttempts());
        positive("lease.max_clock_skew_seconds", l.maxClockSkewSeconds());
        if (l.expiryGraceSeconds() < 0) {
            throw new ConfigException("lease.expiry_grace_seconds must not be negative.");
        }
        if (l.maxSeconds() < l.initialSeconds()) {
            throw new ConfigException("lease.max_seconds must be at least lease.initial_seconds.");
        }
        if (l.renewSeconds() * 3L > l.initialSeconds()) {
            throw new ConfigException("lease.renew_seconds must be at most a third of lease.initial_seconds, so a "
                    + "holder renews several times before its lease can expire.");
        }
        final RunConfig.Worker w = c.worker();
        positive("worker.threads", w.threads());
        positive("worker.onnx_threads", w.onnxThreads());
        positive("worker.flush_bytes", w.flushBytes());
        positive("worker.flush_seconds", w.flushSeconds());
        positive("worker.file_timeout_seconds", w.fileTimeoutSeconds());
        positive("worker.shutdown_grace_seconds", w.shutdownGraceSeconds());
        final RunConfig.Sample sample = c.sample();
        positive("sample.size", sample.size());
        positive("sample.report_max_chars", sample.reportMaxChars());
        if (sample.pricePerWorkerHour() != null && !(sample.pricePerWorkerHour() >= 0)) {
            throw new ConfigException("sample.price_per_worker_hour must not be negative.");
        }
        if (!sample.currency().matches("[A-Za-z]{1,10}")) {
            throw new ConfigException("sample.currency must be 1 to 10 letters, such as USD.");
        }
    }

    private static void positive(final String name, final Number value) {
        if (value.longValue() <= 0) {
            throw new ConfigException(name + " must be greater than zero.");
        }
    }

    private static boolean blank(final String s) {
        return s == null || s.isBlank();
    }

}
