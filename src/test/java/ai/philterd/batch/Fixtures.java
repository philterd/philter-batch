/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch;

import ai.philterd.batch.config.ConfigLoader;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.redaction.TextRedactor;
import ai.philterd.batch.store.ObjectStore;

import ai.philterd.batch.run.Formats.Replacement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared test data. Every name and address here is invented; addresses use example.com. */
public final class Fixtures {

    public static final String INPUT = "input-bucket";
    public static final String OUTPUT = "output-bucket";

    /** A policy that redacts email addresses and SSNs with Phileas's pattern filters. */
    public static final String REGEX_POLICY = """
            {"name":"test","identifiers":{
              "emailAddress":{"emailAddressFilterStrategies":[{"strategy":"REDACT","redactionFormat":"{{{REDACTED-%t}}}"}]},
              "ssn":{"ssnFilterStrategies":[{"strategy":"REDACT","redactionFormat":"{{{REDACTED-%t}}}"}]}}}
            """;

    private Fixtures() {
    }

    /** A configuration for in-memory tests, with extra YAML appended. */
    public static RunConfig config(final String extra) {
        return ConfigLoader.parse("""
                run_id: test-run
                input:
                  bucket: input-bucket
                  prefix: in/
                output:
                  bucket: output-bucket
                  prefix: out/
                lease:
                  initial_seconds: 60
                  renew_seconds: 10
                  max_clock_skew_seconds: 30
                worker:
                  threads: 2
                  onnx_threads: 1
                sample:
                  enabled: false
                """ + extra);
    }

    public static void put(final ObjectStore store, final String key, final String text) {
        store.put(INPUT, key, text.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    /**
     * Replaces each {@code secret-<word>} token with {@code [REDACTED]}, counting them as {@code secret}. Inputs
     * containing {@code LEFTOVER} report a leftover finding; inputs containing {@code EXPLODE} throw. Counts calls
     * per input text.
     */
    public static final class FakeRedactor implements TextRedactor {
        private static final Pattern SECRET = Pattern.compile("secret-\\w+");
        public final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        public volatile long delayMillis;

        @Override
        public Redaction redact(final String text) throws Exception {
            calls.computeIfAbsent(text, t -> new AtomicInteger()).incrementAndGet();
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            if (text.contains("EXPLODE")) {
                throw new IllegalStateException("cannot redact " + text);
            }
            final Matcher m = SECRET.matcher(text);
            final StringBuilder out = new StringBuilder();
            final List<Replacement> replacements = new ArrayList<>();
            int n = 0;
            while (m.find()) {
                m.appendReplacement(out, "");
                replacements.add(new Replacement(out.length(), out.length() + "[REDACTED]".length(), "secret"));
                out.append("[REDACTED]");
                n++;
            }
            m.appendTail(out);
            // OMEGA becomes one character, so later offsets would move: only texts without it have replacements.
            final String redacted = out.toString().replace("OMEGA", "\u03a9");
            final Map<String, Integer> counts = new TreeMap<>();
            if (n > 0) {
                counts.put("secret", n);
            }
            final Map<String, Integer> leftover = text.contains("LEFTOVER") ? Map.of("email-address", 1) : Map.of();
            return new Redaction(redacted, counts, true, leftover,
                    text.contains("OMEGA") ? null : replacements);
        }

        public int totalCalls() {
            return calls.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

}
