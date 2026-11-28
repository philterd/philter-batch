/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.keys;

/** Where the pseudonymization secret is read from: AWS Secrets Manager, or a fake in tests. */
public interface SecretSource {

    /**
     * Reads a version of a secret.
     * @param secretArn The secret.
     * @param versionId The version, or null for the current one ({@code AWSCURRENT}).
     */
    MasterSecret get(String secretArn, String versionId);

}
