/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.redaction;

import ai.philterd.batch.run.Formats.ModelInfo;
import ai.philterd.batch.run.Hashes;
import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.policy.Identifiers;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.filters.PhEye;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * A Phileas policy, parsed, with each PhEye {@code modelPath} that names a model resolved to that model's directory
 * under the models directory. Every PhEye filter must name a local model: Philter Batch runs models in-process and
 * calls no PhEye service.
 */
public final class LoadedPolicy {

    /** A model name: letters, digits, dots, underscores, and hyphens, starting with a letter or digit. */
    static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final Policy policy;
    private final String sha256;
    private final List<Path> modelDirectories;

    private LoadedPolicy(final Policy policy, final String sha256, final List<Path> modelDirectories) {
        this.policy = policy;
        this.sha256 = sha256;
        this.modelDirectories = List.copyOf(modelDirectories);
    }

    public static LoadedPolicy load(final byte[] json, final Path modelsDirectory) {
        final JsonElement element;
        try {
            element = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        } catch (final JsonParseException e) {
            throw new PolicyException("The policy is not valid JSON.");
        }
        if (!element.isJsonObject() || !element.getAsJsonObject().has("identifiers")) {
            throw new PolicyException("The policy must be a JSON object with an identifiers object.");
        }
        final Policy policy;
        try {
            policy = new Gson().fromJson(element, Policy.class);
        } catch (final JsonParseException | IllegalStateException | NumberFormatException e) {
            throw new PolicyException("The policy is not a valid Phileas policy: " + e.getMessage());
        }
        if (policy.getIgnored() == null) {
            policy.setIgnored(new ArrayList<>());
        }
        final List<Path> models = new ArrayList<>();
        final Identifiers identifiers = policy.getIdentifiers();
        final List<PhEye> phEyes = new ArrayList<>();
        if (identifiers.getPerson() != null) {
            phEyes.add(identifiers.getPerson());
        }
        if (identifiers.getPhEyes() != null) {
            phEyes.addAll(identifiers.getPhEyes());
        }
        for (final PhEye phEye : phEyes) {
            if (phEye.getPhEyeConfiguration() == null || phEye.getPhEyeConfiguration().getModelPath() == null
                    || phEye.getPhEyeConfiguration().getModelPath().isBlank()) {
                throw new PolicyException("Each PhEye filter needs phEyeConfiguration.modelPath naming a model in the "
                        + "models directory. Philter Batch runs models in-process and does not call a PhEye service.");
            }
            final Path model = resolve(phEye.getPhEyeConfiguration().getModelPath(), modelsDirectory);
            phEye.getPhEyeConfiguration().setModelPath(model.toString());
            if (!models.contains(model)) {
                models.add(model);
            }
        }
        return new LoadedPolicy(policy, Hashes.sha256(json), models);
    }

    /** A model name resolves under the models directory; a path is used as given. Either must be a complete model. */
    static Path resolve(final String modelPath, final Path modelsDirectory) {
        final boolean name = !modelPath.contains("/") && !modelPath.contains("\\");
        if (name && !MODEL_NAME.matcher(modelPath).matches()) {
            throw new PolicyException("'" + modelPath + "' is not a valid model name.");
        }
        final Path dir = name ? modelsDirectory.resolve(modelPath) : Path.of(modelPath);
        Path onnx = dir.resolve("onnx").resolve("model.onnx");
        if (!Files.isRegularFile(onnx)) {
            onnx = dir.resolve("model.onnx");
        }
        if (!Files.isRegularFile(dir.resolve("gliner_config.json")) || !Files.isRegularFile(dir.resolve("tokenizer.json"))
                || !Files.isRegularFile(onnx)) {
            throw new PolicyException("The model '" + modelPath + "' is not a complete model in " + modelsDirectory
                    + ": it needs gliner_config.json, tokenizer.json, and model.onnx.");
        }
        try {
            return dir.toRealPath();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Policy policy() {
        return policy;
    }

    public String sha256() {
        return sha256;
    }

    /** The models the policy runs, with the SHA-256 of each ONNX file. */
    public List<ModelInfo> models() {
        final List<ModelInfo> info = new ArrayList<>();
        for (final Path dir : modelDirectories) {
            Path onnx = dir.resolve("onnx").resolve("model.onnx");
            if (!Files.isRegularFile(onnx)) {
                onnx = dir.resolve("model.onnx");
            }
            try {
                info.add(new ModelInfo(dir.getFileName().toString(), Hashes.sha256(onnx)));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return info;
    }

    /**
     * Phileas's configuration for batch use: no incremental redactions or span disambiguation, and policy files
     * (dictionaries, ignore lists) only from the given directories.
     */
    public static PhileasConfiguration phileasConfiguration(final List<String> policyFilesDirectories) {
        final Properties p = new Properties();
        p.put("incremental.redactions.enabled", "false");
        p.put("span.disambiguation.enabled", "false");
        p.put("policy.files.restricted", "true");
        p.put("policy.files.directories", String.join(",", policyFilesDirectories));
        return new PhileasConfiguration(p);
    }

}
