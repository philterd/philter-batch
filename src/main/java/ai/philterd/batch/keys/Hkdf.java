/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.keys;

import javax.crypto.KDF;
import javax.crypto.spec.HKDFParameterSpec;
import java.security.GeneralSecurityException;

/** HKDF (RFC 5869) with HMAC-SHA256, from the JDK. */
final class Hkdf {

    private Hkdf() {
    }

    /**
     * Extracts a pseudorandom key from {@code ikm} and expands it to {@code length} bytes for {@code info}.
     * @param salt The salt, or null for RFC 5869's default (32 zero bytes).
     */
    static byte[] derive(final byte[] ikm, final byte[] salt, final byte[] info, final int length) {
        try {
            HKDFParameterSpec.Builder extract = HKDFParameterSpec.ofExtract().addIKM(ikm);
            if (salt != null) {
                extract = extract.addSalt(salt);
            }
            return KDF.getInstance("HKDF-SHA256").deriveData(extract.thenExpand(info, length));
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("HKDF-SHA256 is not available.", e);
        }
    }

}
