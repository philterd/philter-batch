/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

import ai.philterd.batch.config.RunConfig;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.time.Duration;

/** Builds the configured object store. */
public final class ObjectStores {

    private ObjectStores() {
    }

    /**
     * An S3 store with the SDK's default credentials chain, so EKS Pod Identity, IRSA, instance profiles, and
     * environment variables all work with no settings here.
     */
    public static ObjectStore create(final RunConfig.Store store, final int connections) {
        final S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(ApacheHttpClient.builder().maxConnections(Math.max(50, connections))
                        .connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofMinutes(2)))
                .forcePathStyle(store.pathStyle());
        if (store.region() != null) {
            builder.region(Region.of(store.region()));
        }
        if (store.endpoint() != null) {
            builder.endpointOverride(URI.create(store.endpoint()));
        }
        return new S3ObjectStore(builder.build());
    }

}
