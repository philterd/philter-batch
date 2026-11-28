/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.redaction;

import ai.philterd.batch.Fixtures;
import ai.philterd.phileas.policy.FPE;
import ai.philterd.phileas.policy.InvalidPolicyEnvironmentException;
import ai.philterd.phileas.policy.PolicyEnvironment;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyStrategiesTest {

    private static PolicyStrategies of(final String json) {
        return PolicyStrategies.of(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void loadingAPolicyRestrictsThePolicyEnvironmentToNone() {
        LoadedPolicy.load(Fixtures.REGEX_POLICY.getBytes(StandardCharsets.UTF_8), Path.of("/tmp"));
        assertFalse(PolicyEnvironment.isAllowed("HOME"));
        assertFalse(PolicyEnvironment.isAllowed("PATH"));
        assertThrows(InvalidPolicyEnvironmentException.class, () -> new FPE("env:HOME", null).getKey());
    }

    @Test
    void findsKeyedAndRiskyStrategiesAnywhereInTheIdentifiers() {
        final PolicyStrategies strategies = of("""
                {"identifiers":{
                  "ssn":{"ssnFilterStrategies":[{"strategy":"REDACT"},{"strategy":"HASH_SHA256_REPLACE","salt":true}]},
                  "phEyes":[{"phEyeFilterStrategies":[{"strategy":"MASK","fallbackStrategy":"CRYPTO_REPLACE"}]}],
                  "dictionaries":[{"customDictionaryFilterStrategies":[{"strategy":"FPE_ENCRYPT_REPLACE"}]}]}}
                """);
        assertTrue(strategies.needsKey());
        assertEquals(java.util.Set.of("CRYPTO_REPLACE", "FPE_ENCRYPT_REPLACE"), strategies.keyed());
        // A salted hash is not warned about; a fallback strategy is.
        assertEquals(List.of("CRYPTO_REPLACE identifiers.phEyes[0].phEyeFilterStrategies[0]"),
                strategies.warnings().stream().map(w -> w.strategy() + " " + w.where()).toList());

        final PolicyStrategies plain = of(Fixtures.REGEX_POLICY);
        assertFalse(plain.needsKey());
        assertTrue(plain.warnings().isEmpty());
        // Empty key blocks are allowed: they hold no key.
        PolicyStrategies.refuseKeys(JsonParser.parseString("{\"fpe\":{},\"crypto\":null,\"identifiers\":{}}")
                .getAsJsonObject());
        assertThrows(PolicyException.class, () -> PolicyStrategies.refuseKeys(JsonParser.parseString(
                "{\"crypto\":\"x\",\"identifiers\":{}}").getAsJsonObject()));
    }

}
