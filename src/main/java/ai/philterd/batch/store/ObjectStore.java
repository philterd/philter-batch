/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The object storage Philter Batch reads from and writes to. All coordination between workers goes through the
 * conditional writes here ({@link #putIfAbsent} and {@link #putIfMatch}), so an implementation must make them
 * atomic: of two concurrent conditional writes on one key, at most one succeeds.
 *
 * <p>There is deliberately no conditional delete: MinIO accepted a {@code DeleteObject} with a non-matching
 * {@code If-Match} and deleted the object (checked 2026-11-25), so the protocol does not depend on one.</p>
 */
public interface ObjectStore extends AutoCloseable {

    /** Every object under the prefix, in key order. */
    List<ObjectInfo> list(String bucket, String prefix);

    /** The object, or empty if it does not exist. */
    Optional<StoredObject> get(String bucket, String key);

    /**
     * The object, only if its ETag still matches.
     * @throws PreconditionFailedException When it has changed.
     * @throws ObjectNotFoundException When it no longer exists.
     */
    StoredObject getIfMatch(String bucket, String key, String etag);

    /**
     * A stream over bytes {@code start} to {@code endInclusive} of the object, only if its ETag still matches. The
     * range is clipped to the object's size.
     * @throws PreconditionFailedException When it has changed.
     * @throws ObjectNotFoundException When it no longer exists.
     */
    InputStream openRange(String bucket, String key, String etag, long start, long endInclusive);

    /** Writes the object, replacing any object at the key. */
    PutResult put(String bucket, String key, byte[] data, String contentType);

    /**
     * Writes the object only if no object exists at the key ({@code If-None-Match: *}).
     * @throws PreconditionFailedException When one exists.
     */
    PutResult putIfAbsent(String bucket, String key, byte[] data, String contentType);

    /**
     * Replaces the object only if its ETag matches ({@code If-Match}).
     * @throws PreconditionFailedException When it does not, or the object does not exist.
     */
    PutResult putIfMatch(String bucket, String key, byte[] data, String contentType, String etag);

    /** Deletes the object. Deleting a missing object is not an error. */
    void delete(String bucket, String key);

    /**
     * Writes the concatenation of the source objects, in order, to the target key, replacing any object there.
     * The sources are left in place. Stores do this server-side where they can.
     */
    void compose(String bucket, String targetKey, List<String> sourceKeys, String contentType);

    /** The store's clock, read from a request to the bucket. */
    Instant serverTime(String bucket);

    @Override
    default void close() {
    }

}
