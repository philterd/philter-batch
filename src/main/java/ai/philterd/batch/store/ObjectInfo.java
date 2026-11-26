/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

/**
 * An object in a listing.
 * @param key The object's key.
 * @param size Its size in bytes.
 * @param etag Its ETag, with the quotes the store returns.
 */
public record ObjectInfo(String key, long size, String etag) {
}
