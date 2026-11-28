/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.keys;

import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Reads the secret with {@code GetSecretValue}, with the SDK's default credentials (the pod's IAM role on EKS). The
 * value is the secret's binary value if it has one, else its string's UTF-8 bytes. Messages never carry the value.
 */
public final class SecretsManagerSource implements SecretSource {

    private final RunConfig.Pseudonymization settings;

    public SecretsManagerSource(final RunConfig.Pseudonymization settings) {
        this.settings = settings;
    }

    @Override
    public MasterSecret get(final String secretArn, final String versionId) {
        final SecretsManagerClientBuilder builder = SecretsManagerClient.builder()
                .httpClientBuilder(ApacheHttpClient.builder())
                .region(Region.of(secretArn.split(":", 6)[3]));
        if (settings.endpoint() != null) {
            builder.endpointOverride(URI.create(settings.endpoint()));
        }
        try (SecretsManagerClient client = builder.build()) {
            final GetSecretValueResponse response = client.getSecretValue(GetSecretValueRequest.builder()
                    .secretId(secretArn).versionId(versionId).build());
            final byte[] value = response.secretBinary() != null ? response.secretBinary().asByteArray()
                    : response.secretString() == null ? new byte[0]
                    : response.secretString().getBytes(StandardCharsets.UTF_8);
            return new MasterSecret(value, response.versionId());
        } catch (final SecretsManagerException e) {
            final String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            throw new ConfigException("Could not read the pseudonymization secret " + secretArn
                    + (versionId == null ? "" : " (version " + versionId + ")") + ": " + (code == null ? e.statusCode()
                    : code) + ".", e);
        } catch (final SdkException e) {
            throw new ConfigException("Could not reach Secrets Manager for the pseudonymization secret " + secretArn
                    + ": " + e.getClass().getSimpleName() + ".", e);
        }
    }

}
