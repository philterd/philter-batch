/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.keys;

import ai.philterd.batch.config.ConfigException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for Secrets Manager: versions of one secret, the last put being current. Counts reads, as
 * {@code GetSecretValue} requests.
 */
public final class FakeSecrets implements SecretSource {

    public static final String ARN = "arn:aws:secretsmanager:us-east-1:111122223333:secret:philter-batch-test-AbCdEf";

    public final Map<String, byte[]> versions = new LinkedHashMap<>();
    public final AtomicInteger reads = new AtomicInteger();
    private String current;

    /** Adds a version and makes it current. */
    public FakeSecrets put(final String versionId, final String value) {
        versions.put(versionId, value.getBytes(StandardCharsets.UTF_8));
        current = versionId;
        return this;
    }

    @Override
    public synchronized MasterSecret get(final String secretArn, final String versionId) {
        reads.incrementAndGet();
        if (!ARN.equals(secretArn)) {
            throw new ConfigException("Could not read the pseudonymization secret " + secretArn
                    + ": ResourceNotFoundException.");
        }
        final String v = versionId == null ? current : versionId;
        final byte[] value = versions.get(v);
        if (value == null) {
            throw new ConfigException("Could not read the pseudonymization secret " + secretArn + " (version " + v
                    + "): ResourceNotFoundException.");
        }
        return new MasterSecret(value.clone(), v);
    }

    /** Installs this as the process's source, starting with nothing read. */
    public FakeSecrets install() {
        Secrets.factory = settings -> this;
        Secrets.forget();
        return this;
    }

    public static void uninstall() {
        Secrets.factory = SecretsManagerSource::new;
        Secrets.forget();
    }

}
