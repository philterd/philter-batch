/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.keys;

import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.redaction.LoadedPolicy;
import ai.philterd.batch.run.Formats.KeyPin;
import ai.philterd.batch.run.Formats.RunSettings;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.ObjectStore;
import ai.philterd.phileas.policy.Crypto;
import ai.philterd.phileas.policy.FPE;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

/**
 * A run's keys, derived from one version of the pseudonymization secret with HKDF-SHA256 (RFC 5869), each with its
 * own {@code info} label and no salt, so the same secret version always gives the same keys:
 * <ul>
 *   <li>{@code philter-batch/v1/fpe-key}: 32 bytes, the FF3-1 key ({@code FPE_ENCRYPT_REPLACE}).</li>
 *   <li>{@code philter-batch/v1/fpe-tweak}: 7 bytes, the FF3-1 tweak.</li>
 *   <li>{@code philter-batch/v1/crypto-key}: 32 bytes, the AES-256 key ({@code CRYPTO_REPLACE}).</li>
 *   <li>{@code philter-batch/v1/fingerprint}: 32 bytes, recorded in {@code run.json} to check the key with.</li>
 * </ul>
 * Phileas takes keys as hex. {@link #toString()} leaves every key out.
 */
public final class RunKeys {

    /** The shortest secret value accepted. */
    public static final int MIN_SECRET_BYTES = 32;

    static final String LABEL = "philter-batch/v1/";

    private final String fpeKey;
    private final String fpeTweak;
    private final String cryptoKey;
    private final KeyPin pin;

    private RunKeys(final String fpeKey, final String fpeTweak, final String cryptoKey, final KeyPin pin) {
        this.fpeKey = fpeKey;
        this.fpeTweak = fpeTweak;
        this.cryptoKey = cryptoKey;
        this.pin = pin;
    }

    /** The keys of a secret version. */
    public static RunKeys derive(final String secretArn, final MasterSecret secret) {
        if (secret.value().length < MIN_SECRET_BYTES) {
            throw new ConfigException("The pseudonymization secret " + secretArn + " (version " + secret.versionId()
                    + ") holds " + secret.value().length + " bytes; it needs a random value of at least "
                    + MIN_SECRET_BYTES + ".");
        }
        final HexFormat hex = HexFormat.of();
        return new RunKeys(hex.formatHex(derive(secret.value(), "fpe-key", 32)),
                hex.formatHex(derive(secret.value(), "fpe-tweak", 7)),
                hex.formatHex(derive(secret.value(), "crypto-key", 32)),
                new KeyPin(secretArn, secret.versionId(), hex.formatHex(derive(secret.value(), "fingerprint", 32))));
    }

    static byte[] derive(final byte[] secret, final String label, final int length) {
        return Hkdf.derive(secret, null, (LABEL + label).getBytes(StandardCharsets.US_ASCII), length);
    }

    /** The secret version these keys come from, and their fingerprint. */
    public KeyPin pin() {
        return pin;
    }

    /** The policy's {@code fpe} block. */
    public FPE fpe() {
        return new FPE(fpeKey, fpeTweak);
    }

    /** The policy's {@code crypto} block. Phileas's AES-GCM uses a random nonce per value, not an IV. */
    public Crypto crypto() {
        return new Crypto(cryptoKey, null);
    }

    @Override
    public String toString() {
        return "RunKeys[" + pin + "]";
    }

    /** Refuses a policy that needs a key when the run has no pseudonymization secret. */
    public static void requireSecret(final RunConfig config, final LoadedPolicy policy) {
        if (policy.strategies().needsKey() && !config.pseudonymization().configured()) {
            throw new ConfigException("The policy uses " + String.join(" and ", policy.strategies().keyed())
                    + (policy.strategies().keyed().size() == 1 ? ", which needs" : ", which need")
                    + " a key, and the deployment has no pseudonymization secret (pseudonymization."
                    + "secret_arn; see docs/configuration.md).");
        }
    }

    /**
     * The keys for a run's inventory: the version already pinned in {@code run.json} when the run was inventoried
     * before, else the secret's current version, which the inventory then pins. Null when the policy needs no key.
     */
    public static RunKeys forInventory(final ObjectStore store, final RunConfig config, final LoadedPolicy policy) {
        if (!policy.strategies().needsKey()) {
            return null;
        }
        requireSecret(config, policy);
        final RunLayout layout = new RunLayout(config);
        final Optional<KeyPin> pinned = store.get(layout.bucket(), layout.settings())
                .map(o -> Json.read(o.data(), RunSettings.class).key());
        if (pinned.isPresent()) {
            return forPin(config, pinned.get());
        }
        final String arn = config.pseudonymization().secretArn();
        return derive(arn, Secrets.get(config.pseudonymization(), arn, null));
    }

    /**
     * The keys of a run's pinned secret version, checked against the pinned fingerprint. Refuses, before anything
     * is redacted, a deployment that now names another secret, a version that cannot be read, and a value that does
     * not give the pinned fingerprint.
     */
    public static RunKeys forPin(final RunConfig config, final KeyPin pin) {
        if (!pin.secretArn().equals(config.pseudonymization().secretArn())) {
            throw new ConfigException("Run " + config.runId() + " was started with the pseudonymization secret "
                    + pin.secretArn() + ", and its configuration now names "
                    + (config.pseudonymization().configured() ? config.pseudonymization().secretArn() : "none")
                    + ". Its keys cannot change during a run.");
        }
        final MasterSecret secret = Secrets.get(config.pseudonymization(), pin.secretArn(), pin.versionId());
        if (!pin.versionId().equals(secret.versionId())) {
            throw new ConfigException("Secrets Manager returned version " + secret.versionId() + " of the "
                    + "pseudonymization secret, not version " + pin.versionId() + ", which run " + config.runId()
                    + " was started with.");
        }
        final RunKeys keys = derive(pin.secretArn(), secret);
        if (!keys.pin().fingerprint().equals(pin.fingerprint())) {
            throw new ConfigException("Version " + pin.versionId() + " of the pseudonymization secret does not give "
                    + "the key run " + config.runId() + " was started with (its fingerprint differs). Stopping "
                    + "before anything is redacted.");
        }
        return keys;
    }

}
