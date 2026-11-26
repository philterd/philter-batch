/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.store;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Amazon S3, or an S3-compatible store that enforces {@code If-None-Match} and {@code If-Match} on PutObject.
 * Credentials come from the client it is given (the SDK's default chain in normal use).
 */
public final class S3ObjectStore implements ObjectStore {

    /** S3's smallest part, other than the last, in a multipart upload. */
    static final long MIN_PART_BYTES = 5L * 1024 * 1024;

    /** The largest range copied in one UploadPartCopy here; S3 allows 5 GiB. */
    static final long MAX_COPY_PART_BYTES = 1024L * 1024 * 1024;

    /** S3's largest CopyObject source. */
    static final long MAX_COPY_OBJECT_BYTES = 5L * 1024 * 1024 * 1024;

    /** The part size when a composition has to stream its sources through this process. */
    static final int STREAM_PART_BYTES = 16 * 1024 * 1024;

    private final S3Client s3;

    public S3ObjectStore(final S3Client s3) {
        this.s3 = s3;
    }

    @Override
    public List<ObjectInfo> list(final String bucket, final String prefix) {
        try {
            final List<ObjectInfo> result = new ArrayList<>();
            for (final S3Object o : s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket)
                    .prefix(prefix).build()).contents()) {
                result.add(new ObjectInfo(o.key(), o.size(), o.eTag()));
            }
            return result;
        } catch (final SdkException e) {
            throw translate("ListObjectsV2", e);
        }
    }

    @Override
    public Optional<StoredObject> get(final String bucket, final String key) {
        try {
            final ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key).build());
            return Optional.of(new StoredObject(bytes.asByteArray(), bytes.response().eTag(), date(bytes.response())));
        } catch (final AwsServiceException e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw translate("GetObject", e);
        } catch (final SdkException e) {
            throw translate("GetObject", e);
        }
    }

    @Override
    public StoredObject getIfMatch(final String bucket, final String key, final String etag) {
        try {
            final ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key).ifMatch(etag).build());
            return new StoredObject(bytes.asByteArray(), bytes.response().eTag(), date(bytes.response()));
        } catch (final SdkException e) {
            throw translate("GetObject", e);
        }
    }

    @Override
    public InputStream openRange(final String bucket, final String key, final String etag, final long start,
                                 final long endInclusive) {
        try {
            final ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder().bucket(bucket)
                    .key(key).ifMatch(etag).range("bytes=" + start + "-" + endInclusive).build());
            return in;
        } catch (final AwsServiceException e) {
            // A range starting at or past the end of the object.
            if (e.statusCode() == 416) {
                return InputStream.nullInputStream();
            }
            throw translate("GetObject", e);
        } catch (final SdkException e) {
            throw translate("GetObject", e);
        }
    }

    @Override
    public PutResult put(final String bucket, final String key, final byte[] data, final String contentType) {
        return doPut(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(), data);
    }

    @Override
    public PutResult putIfAbsent(final String bucket, final String key, final byte[] data, final String contentType) {
        return doPut(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).ifNoneMatch("*")
                .build(), data);
    }

    @Override
    public PutResult putIfMatch(final String bucket, final String key, final byte[] data, final String contentType,
                                final String etag) {
        return doPut(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).ifMatch(etag)
                .build(), data);
    }

    private PutResult doPut(final PutObjectRequest request, final byte[] data) {
        try {
            final PutObjectResponse response = s3.putObject(request, RequestBody.fromBytes(data));
            return new PutResult(response.eTag(), date(response));
        } catch (final AwsServiceException e) {
            // 404: an If-Match against a missing object. 409: S3's ConditionalRequestConflict, when a
            // concurrent conditional write to the key is in progress.
            if (e.statusCode() == 404 && request.ifMatch() != null || e.statusCode() == 409) {
                throw new PreconditionFailedException("PutObject: " + e.statusCode() + " " + errorCode(e));
            }
            throw translate("PutObject", e);
        } catch (final SdkException e) {
            throw translate("PutObject", e);
        }
    }

    @Override
    public void delete(final String bucket, final String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (final SdkException e) {
            throw translate("DeleteObject", e);
        }
    }

    @Override
    public void compose(final String bucket, final String targetKey, final List<String> sourceKeys,
                        final String contentType) {
        try {
            final List<String> sources = new ArrayList<>();
            final List<Long> sizes = new ArrayList<>();
            for (final String source : sourceKeys) {
                final long size = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(source).build())
                        .contentLength();
                if (size > 0) {
                    sources.add(source);
                    sizes.add(size);
                }
            }
            if (sources.isEmpty()) {
                put(bucket, targetKey, new byte[0], contentType);
            } else if (sources.size() == 1 && sizes.getFirst() <= MAX_COPY_OBJECT_BYTES) {
                s3.copyObject(CopyObjectRequest.builder().sourceBucket(bucket).sourceKey(sources.getFirst())
                        .destinationBucket(bucket).destinationKey(targetKey).contentType(contentType)
                        .metadataDirective("REPLACE").build());
            } else if (serverSideCopyPossible(sizes)) {
                multipart(bucket, targetKey, contentType, (uploadId, parts) ->
                        copyParts(bucket, targetKey, uploadId, sources, sizes, parts));
            } else {
                multipart(bucket, targetKey, contentType, (uploadId, parts) ->
                        streamParts(bucket, targetKey, uploadId, sources, parts));
            }
        } catch (final AwsServiceException e) {
            if (e.statusCode() == 404) {
                throw new ObjectNotFoundException("Compose: a source does not exist");
            }
            throw translate("Compose", e);
        } catch (final SdkException e) {
            throw translate("Compose", e);
        }
    }

    /** Every source but the last must be at least S3's minimum part size to be copied as parts. */
    static boolean serverSideCopyPossible(final List<Long> sizes) {
        for (int i = 0; i < sizes.size() - 1; i++) {
            if (sizes.get(i) < MIN_PART_BYTES) {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits a source into ranges of at most {@link #MAX_COPY_PART_BYTES}, of equal size so that none but the
     * last is under the minimum part size. Each range is {@code [start, endInclusive]}.
     */
    static List<long[]> copyRanges(final long size) {
        final long count = Math.max(1, (size + MAX_COPY_PART_BYTES - 1) / MAX_COPY_PART_BYTES);
        final long chunk = (size + count - 1) / count;
        final List<long[]> ranges = new ArrayList<>();
        for (long start = 0; start < size; start += chunk) {
            ranges.add(new long[] {start, Math.min(size, start + chunk) - 1});
        }
        return ranges;
    }

    private interface PartWriter {
        void write(String uploadId, List<CompletedPart> parts) throws IOException;
    }

    private void multipart(final String bucket, final String key, final String contentType, final PartWriter writer) {
        final String uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(bucket)
                .key(key).contentType(contentType).build()).uploadId();
        try {
            final List<CompletedPart> parts = new ArrayList<>();
            writer.write(uploadId, parts);
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(bucket).key(key)
                    .uploadId(uploadId).multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
        } catch (final IOException | RuntimeException e) {
            try {
                s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(key)
                        .uploadId(uploadId).build());
            } catch (final SdkException abort) {
                e.addSuppressed(abort);
            }
            if (e instanceof IOException io) {
                throw new UncheckedIOException(io);
            }
            throw (RuntimeException) e;
        }
    }

    private void copyParts(final String bucket, final String key, final String uploadId, final List<String> sources,
                           final List<Long> sizes, final List<CompletedPart> parts) {
        for (int i = 0; i < sources.size(); i++) {
            for (final long[] range : copyRanges(sizes.get(i))) {
                final int number = parts.size() + 1;
                final String etag = s3.uploadPartCopy(UploadPartCopyRequest.builder().sourceBucket(bucket)
                        .sourceKey(sources.get(i)).destinationBucket(bucket).destinationKey(key).uploadId(uploadId)
                        .partNumber(number).copySourceRange("bytes=" + range[0] + "-" + range[1]).build())
                        .copyPartResult().eTag();
                parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
            }
        }
    }

    private void streamParts(final String bucket, final String key, final String uploadId, final List<String> sources,
                             final List<CompletedPart> parts) throws IOException {
        final byte[] buffer = new byte[STREAM_PART_BYTES];
        int filled = 0;
        for (final String source : sources) {
            try (InputStream in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(source).build())) {
                int read;
                while ((read = in.read(buffer, filled, buffer.length - filled)) != -1) {
                    filled += read;
                    if (filled == buffer.length) {
                        uploadPart(bucket, key, uploadId, buffer, filled, parts);
                        filled = 0;
                    }
                }
            }
        }
        if (filled > 0 || parts.isEmpty()) {
            uploadPart(bucket, key, uploadId, buffer, filled, parts);
        }
    }

    private void uploadPart(final String bucket, final String key, final String uploadId, final byte[] buffer,
                            final int length, final List<CompletedPart> parts) {
        final int number = parts.size() + 1;
        final String etag = s3.uploadPart(UploadPartRequest.builder().bucket(bucket).key(key).uploadId(uploadId)
                .partNumber(number).build(), RequestBody.fromBytes(java.util.Arrays.copyOf(buffer, length))).eTag();
        parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
    }

    @Override
    public Instant serverTime(final String bucket) {
        try {
            final Instant date = date(s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()));
            if (date == null) {
                throw new ObjectStoreException("The store sent no Date header, so it cannot be the lease clock.", null);
            }
            return date;
        } catch (final SdkException e) {
            throw translate("HeadBucket", e);
        }
    }

    @Override
    public void close() {
        s3.close();
    }

    /** The response's {@code Date} header, or null. */
    static Instant date(final SdkResponse response) {
        return response.sdkHttpResponse().firstMatchingHeader("Date").map(S3ObjectStore::parseDate).orElse(null);
    }

    static Instant parseDate(final String header) {
        try {
            return ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static String errorCode(final AwsServiceException e) {
        return e.awsErrorDetails() == null || e.awsErrorDetails().errorCode() == null ? ""
                : e.awsErrorDetails().errorCode();
    }

    /** Maps an SDK failure to this package's exceptions, keeping only the status and error code. */
    private static RuntimeException translate(final String operation, final SdkException e) {
        if (e instanceof AwsServiceException s) {
            if (s.statusCode() == 412) {
                return new PreconditionFailedException(operation + ": 412 " + errorCode(s));
            }
            if (s.statusCode() == 404) {
                return new ObjectNotFoundException(operation + ": 404 " + errorCode(s));
            }
            return new ObjectStoreException(operation + " failed: " + s.statusCode() + " " + errorCode(s), null);
        }
        return new ObjectStoreException(operation + " failed: " + e.getClass().getSimpleName(), null);
    }

}
