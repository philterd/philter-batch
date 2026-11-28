/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.keys;

import ai.philterd.batch.Fixtures;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.run.Formats.KeyPin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunKeysTest {

    private static final HexFormat HEX = HexFormat.of();

    @AfterEach
    void restore() {
        FakeSecrets.uninstall();
    }

    private static RunConfig config(final String arn) {
        return Fixtures.config(arn == null ? "" : "pseudonymization: {secret_arn: " + arn + "}\n");
    }

    @Test
    void hkdfMatchesRfc5869() {
        // RFC 5869, appendix A.1.
        assertArrayEquals(HEX.parseHex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
                Hkdf.derive(HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                        HEX.parseHex("000102030405060708090a0b0c"), HEX.parseHex("f0f1f2f3f4f5f6f7f8f9"), 42));
        // A.3: no salt and no info, so RFC 5869's default salt.
        assertArrayEquals(HEX.parseHex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
                Hkdf.derive(HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), null, new byte[0], 42));
    }

    @Test
    void derivationIsPinned() {
        // Computed independently (Python's hmac module) for the secret bytes 0x00 to 0x1f.
        final byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) i;
        }
        final RunKeys keys = RunKeys.derive(FakeSecrets.ARN, new MasterSecret(secret, "v1"));
        assertEquals("f2fdf37f97375a67a49fc695f9f59016617983b92d4a787b06ced962ad07f258", keys.fpe().getKey());
        assertEquals("3353d7231883fa", keys.fpe().getTweak());
        assertEquals("34ae43257aff7bddba5f5c960459a88fabcb1accb992a903b18c043c92533294", keys.crypto().getKey());
        assertEquals(new KeyPin(FakeSecrets.ARN, "v1",
                "b0f7d6596bea7ee1047a1259476946ddd3fb6c9913498d18d12ccd07f519fb4b"), keys.pin());
        // Nothing of the keys in its string form.
        assertFalse(keys.toString().contains(keys.fpe().getKey()));
        assertFalse(keys.toString().contains(keys.crypto().getKey()));
        assertFalse(new MasterSecret(secret, "v1").toString().contains(HEX.formatHex(secret)));
    }

    @Test
    void aShortSecretIsRefused() {
        final ConfigException e = assertThrows(ConfigException.class, () -> RunKeys.derive(FakeSecrets.ARN,
                new MasterSecret("too short".getBytes(StandardCharsets.UTF_8), "v1")));
        assertTrue(e.getMessage().contains("holds 9 bytes; it needs a random value of at least 32"), e.getMessage());
        assertFalse(e.getMessage().contains("too short"));
    }

    @Test
    void aPinIsCheckedBeforeItsKeysAreUsed() {
        final FakeSecrets secrets = new FakeSecrets().put("v1", "an invented secret value of more than 32 bytes")
                .install();
        final RunConfig config = config(FakeSecrets.ARN);
        final KeyPin pin = RunKeys.derive(FakeSecrets.ARN, secrets.get(FakeSecrets.ARN, "v1")).pin();
        assertEquals(pin, RunKeys.forPin(config, pin).pin());

        // A later version is current; the pin still gives the run's keys.
        secrets.put("v2", "another invented secret value, also over 32 bytes");
        assertEquals(pin, RunKeys.forPin(config, pin).pin());
        assertNotEquals(pin.fingerprint(), RunKeys.derive(FakeSecrets.ARN, secrets.get(FakeSecrets.ARN, null))
                .pin().fingerprint());

        // A version whose value changed, another secret in the configuration, and an unreadable version.
        Secrets.forget();
        secrets.versions.put("v1", "a replaced invented value, still over 32 bytes".getBytes(StandardCharsets.UTF_8));
        assertTrue(assertThrows(ConfigException.class, () -> RunKeys.forPin(config, pin)).getMessage()
                .contains("its fingerprint differs"));
        final String other = "arn:aws:secretsmanager:us-east-1:111122223333:secret:other-ZyXwVu";
        assertTrue(assertThrows(ConfigException.class, () -> RunKeys.forPin(config(other), pin)).getMessage()
                .contains("Its keys cannot change during a run."));
        assertThrows(ConfigException.class, () -> RunKeys.forPin(config(null), pin));
        assertTrue(assertThrows(ConfigException.class, () -> RunKeys.forPin(config,
                new KeyPin(FakeSecrets.ARN, "v9", pin.fingerprint()))).getMessage().contains("version v9"));
    }

    @Test
    void eachVersionIsReadOncePerProcess() {
        final FakeSecrets secrets = new FakeSecrets().put("v1", "an invented secret value of more than 32 bytes")
                .install();
        final RunConfig config = config(FakeSecrets.ARN);
        final KeyPin pin = RunKeys.derive(FakeSecrets.ARN, Secrets.current(config.pseudonymization())).pin();
        for (int i = 0; i < 100; i++) {
            RunKeys.forPin(config, pin);
        }
        assertEquals(1, secrets.reads.get(), "the current version's read is reused for its pinned version");
    }

}
