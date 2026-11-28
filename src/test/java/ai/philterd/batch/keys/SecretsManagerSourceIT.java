/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.keys;

import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.ConfigLoader;
import ai.philterd.batch.config.RunConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecretsManagerSource} against moto's Secrets Manager: versions, binary and string values, and errors. The
 * secret values are invented.
 */
@Testcontainers
class SecretsManagerSourceIT {

    private static final String IMAGE = "motoserver/moto:5.1.18";
    private static final String FIRST = "an invented first secret value, over 32 bytes long";
    private static final String SECOND = "an invented second secret value, over 32 bytes long";

    private static GenericContainer<?> server;
    private static String endpoint;
    private static SecretsManagerClient client;

    @BeforeAll
    static void start() {
        server = new GenericContainer<>(IMAGE).withExposedPorts(5000)
                .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(2));
        server.start();
        endpoint = "http://" + server.getHost() + ":" + server.getMappedPort(5000);
        System.setProperty("aws.accessKeyId", "testaccesskey");
        System.setProperty("aws.secretAccessKey", "testsecretkey");
        client = SecretsManagerClient.builder().region(Region.US_EAST_1).endpointOverride(URI.create(endpoint)).build();
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void readsTheCurrentOrAPinnedVersion() {
        final var created = client.createSecret(r -> r.name("philter-batch-it").secretString(FIRST));
        final String arn = created.arn();
        assertTrue(ConfigLoader.SECRET_ARN.matcher(arn).matches(), arn);
        final String second = client.putSecretValue(r -> r.secretId(arn).secretString(SECOND)).versionId();

        final SecretsManagerSource source = new SecretsManagerSource(new RunConfig.Pseudonymization(arn, endpoint));
        final MasterSecret current = source.get(arn, null);
        assertEquals(second, current.versionId());
        assertArrayEquals(SECOND.getBytes(StandardCharsets.UTF_8), current.value());
        final MasterSecret first = source.get(arn, created.versionId());
        assertEquals(created.versionId(), first.versionId());
        assertArrayEquals(FIRST.getBytes(StandardCharsets.UTF_8), first.value());

        // A pin survives the rotation: the first version's keys are still the first version's.
        final RunKeys pinned = RunKeys.derive(arn, first);
        assertEquals(pinned.pin().fingerprint(), RunKeys.derive(arn, source.get(arn, created.versionId())).pin()
                .fingerprint());
    }

    @Test
    void readsABinaryValue() {
        final byte[] value = new byte[48];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (i * 7);
        }
        final String arn = client.createSecret(r -> r.name("philter-batch-binary-it")
                .secretBinary(SdkBytes.fromByteArray(value))).arn();
        assertArrayEquals(value, new SecretsManagerSource(new RunConfig.Pseudonymization(arn, endpoint))
                .get(arn, null).value());
    }

    @Test
    void reportsAMissingSecretOrVersionWithoutAValue() {
        final String arn = client.createSecret(r -> r.name("philter-batch-errors-it").secretString(FIRST)).arn();
        final SecretsManagerSource source = new SecretsManagerSource(new RunConfig.Pseudonymization(arn, endpoint));
        final ConfigException version = assertThrows(ConfigException.class,
                () -> source.get(arn, "00000000-0000-4000-8000-000000000000"));
        assertTrue(version.getMessage().contains("(version 00000000-0000-4000-8000-000000000000)"), version.getMessage());
        assertFalse(version.getMessage().contains(FIRST));
        final String missing = arn.replace("philter-batch-errors-it", "philter-batch-missing");
        assertTrue(assertThrows(ConfigException.class, () -> source.get(missing, null)).getMessage()
                .contains("ResourceNotFoundException"));
    }

}
