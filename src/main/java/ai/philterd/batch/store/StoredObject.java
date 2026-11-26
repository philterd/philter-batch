/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

import java.time.Instant;

/**
 * An object read from the store.
 * @param data Its content.
 * @param etag Its ETag.
 * @param serverTime The store's clock when it answered (its {@code Date} header), or null if it sent none.
 */
public record StoredObject(byte[] data, String etag, Instant serverTime) {
}
