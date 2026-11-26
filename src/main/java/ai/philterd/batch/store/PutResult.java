/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

import java.time.Instant;

/**
 * The result of a write.
 * @param etag The new object's ETag.
 * @param serverTime The store's clock when it answered, or null if it sent none.
 */
public record PutResult(String etag, Instant serverTime) {
}
