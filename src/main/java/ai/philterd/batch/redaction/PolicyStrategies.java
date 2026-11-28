/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.redaction;

import ai.philterd.batch.run.Formats.PolicyWarning;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a policy's strategies mean for Philter Batch: which need a key from the pseudonymization secret, and which
 * weaken the output in a way the sample's reviewer should see. Read from the policy's JSON, so every filter type,
 * custom ones included, is covered.
 */
public final class PolicyStrategies {

    public static final String FPE_ENCRYPT_REPLACE = "FPE_ENCRYPT_REPLACE";
    public static final String CRYPTO_REPLACE = "CRYPTO_REPLACE";
    public static final String HASH_SHA256_REPLACE = "HASH_SHA256_REPLACE";
    public static final String LAST_4 = "LAST_4";

    /** The strategies that need a key. */
    public static final Set<String> KEYED = Set.of(FPE_ENCRYPT_REPLACE, CRYPTO_REPLACE);

    /** The policy's top-level key blocks and their fields, none of which a policy may set in Philter Batch. */
    static final Map<String, List<String>> KEY_FIELDS = Map.of("fpe", List.of("key", "tweak"),
            "crypto", List.of("key", "iv"));

    private final List<Use> uses;

    /** A strategy, or a fallback strategy, at a place in the policy. */
    record Use(String where, String strategy, boolean salted) {
    }

    private PolicyStrategies(final List<Use> uses) {
        this.uses = List.copyOf(uses);
    }

    public static PolicyStrategies of(final JsonObject policy) {
        final List<Use> uses = new ArrayList<>();
        final JsonElement identifiers = policy.get("identifiers");
        if (identifiers != null) {
            walk(identifiers, "identifiers", uses);
        }
        return new PolicyStrategies(uses);
    }

    private static void walk(final JsonElement element, final String where, final List<Use> uses) {
        if (element.isJsonArray()) {
            final JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                walk(array.get(i), where + "[" + i + "]", uses);
            }
        } else if (element.isJsonObject()) {
            final JsonObject object = element.getAsJsonObject();
            final boolean salted = isTrue(object.get("salt"));
            for (final String field : List.of("strategy", "fallbackStrategy")) {
                final JsonElement value = object.get(field);
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    uses.add(new Use(where, value.getAsString(), salted));
                }
            }
            for (final Map.Entry<String, JsonElement> e : object.entrySet()) {
                walk(e.getValue(), where + "." + e.getKey(), uses);
            }
        }
    }

    private static boolean isTrue(final JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /**
     * Refuses a key in the policy, in any form: a literal, {@code env:NAME}, or a placeholder. Keys come from the
     * pseudonymization secret, and the policy is stored in the bucket.
     */
    public static void refuseKeys(final JsonObject policy) {
        for (final Map.Entry<String, List<String>> block : KEY_FIELDS.entrySet()) {
            final JsonElement value = policy.get(block.getKey());
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (!value.isJsonObject()) {
                throw new PolicyException("The policy's " + block.getKey() + " must be an object, or left out.");
            }
            for (final String field : block.getValue()) {
                final JsonElement f = value.getAsJsonObject().get(field);
                if (f != null && !f.isJsonNull()) {
                    throw new PolicyException("The policy sets " + block.getKey() + "." + field + ". Philter Batch "
                            + "does not take keys in a policy, which is stored in the bucket: remove the "
                            + block.getKey() + " block. FPE_ENCRYPT_REPLACE and CRYPTO_REPLACE are keyed from the "
                            + "deployment's pseudonymization secret (docs/configuration.md).");
                }
            }
        }
    }

    /** Whether a strategy needs a key from the pseudonymization secret. */
    public boolean needsKey() {
        return uses.stream().anyMatch(u -> KEYED.contains(u.strategy()));
    }

    /** The keyed strategies the policy uses, for messages. */
    public Set<String> keyed() {
        final Set<String> keyed = new java.util.TreeSet<>();
        uses.stream().map(Use::strategy).filter(KEYED::contains).forEach(keyed::add);
        return keyed;
    }

    /** The strategies a reviewer should know about. They are not refused. */
    public List<PolicyWarning> warnings() {
        final List<PolicyWarning> warnings = new ArrayList<>();
        for (final Use u : uses) {
            final String message = switch (u.strategy()) {
                case CRYPTO_REPLACE -> "Encrypts each value with a random nonce: anyone with the key can recover "
                        + "the original, the same value gets a different replacement each time, and Philter Batch "
                        + "has no re-identify endpoint.";
                case HASH_SHA256_REPLACE -> u.salted() ? null : "Replaces each value with its SHA-256 hash, "
                        + "unsalted: a guessed value can be confirmed by hashing it.";
                case LAST_4 -> "Keeps the last four characters of each value.";
                default -> null;
            };
            if (message != null) {
                warnings.add(new PolicyWarning(u.where(), u.strategy(), message));
            }
        }
        return warnings;
    }

}
