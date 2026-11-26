/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.run;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** SHA-256 and HMAC-SHA256 helpers. */
public final class Hashes {

    private Hashes() {
    }

    public static String sha256(final byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    public static String sha256(final Path file) throws IOException {
        final MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            final byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * A document's opaque ID: the first 128 bits of HMAC-SHA256 over its bucket, key, and ETag, keyed with the
     * run's secret. Keyed, so the ID cannot be checked against a guessed file name without the secret.
     */
    public static String documentId(final byte[] secret, final String bucket, final String key, final String etag) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            final byte[] out = mac.doFinal((bucket + '\u0000' + key + '\u0000' + etag).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out, 0, 16);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

}
