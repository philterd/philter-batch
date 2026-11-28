/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.keys;

/**
 * A version of the pseudonymization secret: its value, from which every key is derived, and its {@code VersionId}.
 * {@link #toString()} leaves the value out.
 */
public record MasterSecret(byte[] value, String versionId) {

    @Override
    public String toString() {
        return "MasterSecret[versionId=" + versionId + "]";
    }

}
