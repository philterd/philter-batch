/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * An object store in memory, with the same conditional semantics as S3: create-if-absent and write-if-match are
 * atomic, and a failed condition throws {@link PreconditionFailedException}. Its clock is the {@link Clock} it is
 * given, so tests can set the store's time apart from a worker's. For tests and the single-process pilot's dry runs.
 */
public final class InMemoryObjectStore implements ObjectStore {

    private record Entry(byte[] data, String etag, String contentType) {
    }

    private final Map<String, Entry> objects = new TreeMap<>();
    private final Clock clock;

    /** Called with the operation name and the bucket/key before each write; returning true fails the write. */
    private volatile BiPredicate<String, String> fault = (op, key) -> false;

    public InMemoryObjectStore() {
        this(Clock.systemUTC());
    }

    public InMemoryObjectStore(final Clock clock) {
        this.clock = clock;
    }

    /** The content type an object was written with, for tests. */
    public synchronized String contentType(final String bucket, final String key) {
        final Entry e = objects.get(id(bucket, key));
        return e == null ? null : e.contentType();
    }

    /**
     * Makes writes fail, to simulate a crash or an outage at a chosen point. The predicate gets the operation
     * ({@code put}, {@code putIfAbsent}, {@code putIfMatch}, {@code delete}, {@code compose}) and {@code bucket/key}.
     */
    public void setFault(final BiPredicate<String, String> fault) {
        this.fault = fault == null ? (op, key) -> false : fault;
    }

    private void checkFault(final String op, final String bucket, final String key) {
        if (fault.test(op, bucket + "/" + key)) {
            throw new ObjectStoreException("Injected failure: " + op, null);
        }
    }

    private static String id(final String bucket, final String key) {
        return bucket + "\u0000" + key;
    }

    private String etag(final byte[] data) {
        try {
            final MessageDigest md5 = MessageDigest.getInstance("MD5");
            // As S3 does for a single-part upload: the same bytes get the same ETag. A protocol that relies on
            // every write changing the ETag would fail on S3, so the coordination formats carry a revision that
            // changes on every write, and these tests see the same ETags S3 would give.
            md5.update(data);
            return "\"" + HexFormat.of().formatHex(md5.digest()) + "\"";
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized List<ObjectInfo> list(final String bucket, final String prefix) {
        final List<ObjectInfo> result = new ArrayList<>();
        final String from = id(bucket, prefix);
        for (final Map.Entry<String, Entry> e : ((TreeMap<String, Entry>) objects).tailMap(from, true).entrySet()) {
            if (!e.getKey().startsWith(from)) {
                break;
            }
            final String key = e.getKey().substring(bucket.length() + 1);
            result.add(new ObjectInfo(key, e.getValue().data().length, e.getValue().etag()));
        }
        return result;
    }

    @Override
    public synchronized Optional<StoredObject> get(final String bucket, final String key) {
        final Entry e = objects.get(id(bucket, key));
        return e == null ? Optional.empty() : Optional.of(new StoredObject(e.data().clone(), e.etag(), clock.instant()));
    }

    @Override
    public synchronized StoredObject getIfMatch(final String bucket, final String key, final String etag) {
        final Entry e = objects.get(id(bucket, key));
        if (e == null) {
            throw new ObjectNotFoundException("Not found");
        }
        if (!e.etag().equals(etag)) {
            throw new PreconditionFailedException("ETag does not match");
        }
        return new StoredObject(e.data().clone(), e.etag(), clock.instant());
    }

    @Override
    public synchronized InputStream openRange(final String bucket, final String key, final String etag,
                                              final long start, final long endInclusive) {
        final byte[] data = getIfMatch(bucket, key, etag).data();
        final int from = (int) Math.min(start, data.length);
        final int to = (int) Math.min(endInclusive + 1, data.length);
        return new ByteArrayInputStream(data, from, Math.max(0, to - from));
    }

    @Override
    public synchronized PutResult put(final String bucket, final String key, final byte[] data, final String contentType) {
        checkFault("put", bucket, key);
        final Entry e = new Entry(data.clone(), etag(data), contentType);
        objects.put(id(bucket, key), e);
        return new PutResult(e.etag(), clock.instant());
    }

    @Override
    public synchronized PutResult putIfAbsent(final String bucket, final String key, final byte[] data,
                                              final String contentType) {
        checkFault("putIfAbsent", bucket, key);
        if (objects.containsKey(id(bucket, key))) {
            throw new PreconditionFailedException("Object exists");
        }
        final Entry e = new Entry(data.clone(), etag(data), contentType);
        objects.put(id(bucket, key), e);
        return new PutResult(e.etag(), clock.instant());
    }

    @Override
    public synchronized PutResult putIfMatch(final String bucket, final String key, final byte[] data,
                                             final String contentType, final String etag) {
        checkFault("putIfMatch", bucket, key);
        final Entry current = objects.get(id(bucket, key));
        if (current == null || !current.etag().equals(etag)) {
            throw new PreconditionFailedException("ETag does not match");
        }
        final Entry e = new Entry(data.clone(), etag(data), contentType);
        objects.put(id(bucket, key), e);
        return new PutResult(e.etag(), clock.instant());
    }

    @Override
    public synchronized void delete(final String bucket, final String key) {
        checkFault("delete", bucket, key);
        objects.remove(id(bucket, key));
    }

    @Override
    public synchronized void compose(final String bucket, final String targetKey, final List<String> sourceKeys,
                                     final String contentType) {
        checkFault("compose", bucket, targetKey);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final String source : sourceKeys) {
            final Entry e = objects.get(id(bucket, source));
            if (e == null) {
                throw new ObjectNotFoundException("Source not found");
            }
            out.writeBytes(e.data());
        }
        put(bucket, targetKey, out.toByteArray(), contentType);
    }

    @Override
    public Instant serverTime(final String bucket) {
        return clock.instant();
    }

}
