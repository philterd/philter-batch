/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.run;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The versions of Philter Batch and the Phileas libraries on the classpath, for done markers and {@code _SUCCESS}. */
public final class Versions {

    private Versions() {
    }

    public static String philterBatch() {
        return read("/philter-batch.properties");
    }

    public static String phileas() {
        return read("/META-INF/maven/ai.philterd/phileas/pom.properties");
    }

    public static String pheyeOnnx() {
        return read("/META-INF/maven/ai.philterd/phileas-pheye-onnx/pom.properties");
    }

    private static String read(final String resource) {
        try (InputStream in = Versions.class.getResourceAsStream(resource)) {
            if (in == null) {
                return "unknown";
            }
            final Properties p = new Properties();
            p.load(in);
            return p.getProperty("version", "unknown");
        } catch (final IOException e) {
            return "unknown";
        }
    }

}
