/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.keys;

import ai.philterd.batch.config.RunConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The process's reads of the pseudonymization secret. Each version is read at most once per process and held in
 * memory, so Secrets Manager requests grow with process starts, not with entries or with the steps one process runs.
 */
public final class Secrets {

    /** Builds the source; tests replace it. */
    public static Function<RunConfig.Pseudonymization, SecretSource> factory = SecretsManagerSource::new;

    private static final Map<String, MasterSecret> READ = new ConcurrentHashMap<>();

    private Secrets() {
    }

    /** A version of the secret, or the current one when {@code versionId} is null (which is read every time). */
    static MasterSecret get(final RunConfig.Pseudonymization settings, final String secretArn, final String versionId) {
        if (versionId != null) {
            final MasterSecret held = READ.get(secretArn + "\n" + versionId);
            if (held != null) {
                return held;
            }
        }
        final MasterSecret secret = factory.apply(settings).get(secretArn, versionId);
        if (secret.versionId() != null) {
            READ.put(secretArn + "\n" + secret.versionId(), secret);
        }
        return secret;
    }

    /** Forgets what was read. For tests, which simulate several processes in one. */
    public static void forget() {
        READ.clear();
    }

    /** The secret's current version. */
    public static MasterSecret current(final RunConfig.Pseudonymization settings) {
        return get(settings, settings.secretArn(), null);
    }

}
