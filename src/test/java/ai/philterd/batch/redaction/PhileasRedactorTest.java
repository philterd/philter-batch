/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.redaction;

import ai.philterd.batch.Fixtures;
import ai.philterd.phileas.PhileasConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhileasRedactorTest {

    private static final PhileasConfiguration CONFIGURATION = LoadedPolicy.phileasConfiguration(List.of());

    private static LoadedPolicy policy(final String json) {
        return LoadedPolicy.load(json.getBytes(StandardCharsets.UTF_8), Path.of("/nonexistent"));
    }

    @Test
    void redacts() throws Exception {
        final PhileasRedactor redactor = new PhileasRedactor(policy(Fixtures.REGEX_POLICY), CONFIGURATION);
        final TextRedactor.Redaction r = redactor.redact(
                "Write to jane.roe@example.com or j.doe@example.org. SSN 123-45-6789 on file.");
        assertEquals("Write to {{{REDACTED-email-address}}} or {{{REDACTED-email-address}}}. "
                + "SSN {{{REDACTED-ssn}}} on file.", r.text());
        assertEquals(Map.of("email-address", 2, "ssn", 1), r.piiCounts());
    }

    @Test
    void placesEachReplacementInTheRedactedTextByType() throws Exception {
        final PhileasRedactor redactor = new PhileasRedactor(policy(Fixtures.REGEX_POLICY), CONFIGURATION);
        final TextRedactor.Redaction r = redactor.redact(
                "SSN 123-45-6789, then mail jane.roe@example.com and j.doe@example.org.");
        assertEquals(3, r.replacements().size());
        assertEquals(List.of("ssn", "email-address", "email-address"),
                r.replacements().stream().map(ai.philterd.batch.run.Formats.Replacement::type).toList());
        for (final ai.philterd.batch.run.Formats.Replacement x : r.replacements()) {
            assertEquals("{{{REDACTED-" + x.type() + "}}}", r.text().substring(x.start(), x.end()));
        }
        final TextRedactor.Redaction none = redactor.redact("Nothing to see.");
        assertEquals(List.of(), none.replacements());
    }

    @Test
    void replacementsThatDoNotMatchAreNotPlaced() {
        // A range that does not hold its span's replacement is refused, not guessed.
        final ai.philterd.phileas.model.filtering.Span span = ai.philterd.phileas.model.filtering.Span.make(0, 4,
                ai.philterd.phileas.model.filtering.FilterType.SSN, "context", 1.0, "1234", "XXXX", null, false, true,
                new String[0], 0);
        assertEquals(1, PhileasRedactor.replacements("XXXX rest", List.of(span),
                List.of(new ai.philterd.phileas.model.filtering.ReplacedRange(0, 4))).size());
        assertEquals(null, PhileasRedactor.replacements("XXXX rest", List.of(span),
                List.of(new ai.philterd.phileas.model.filtering.ReplacedRange(1, 5))));
        assertEquals(null, PhileasRedactor.replacements("XXXX rest", List.of(span), List.of()));
        assertEquals(null, PhileasRedactor.replacements("XX", List.of(span),
                List.of(new ai.philterd.phileas.model.filtering.ReplacedRange(0, 4))));
    }

    @Test
    void textWithNothingToRedactIsUnchanged() throws Exception {
        final PhileasRedactor redactor = new PhileasRedactor(policy(Fixtures.REGEX_POLICY), CONFIGURATION);
        final TextRedactor.Redaction r = redactor.redact("Nothing here.\n\tTabs and\r\nCRLF stay.");
        assertEquals("Nothing here.\n\tTabs and\r\nCRLF stay.", r.text());
        assertEquals(Map.of(), r.piiCounts());
    }

    @Test
    void doesNotRunTheLeftoverCheckEvenWhenThePolicyAsks() throws Exception {
        // The email filter's own ignore list lets this address through; Phileas's check would report it, but
        // Philter Batch turns the check off for now.
        final String json = """
                {"identifiers":{"emailAddress":{"ignored":["kept@example.com"],
                  "emailAddressFilterStrategies":[{"strategy":"REDACT","redactionFormat":"{{{REDACTED-%t}}}"}]}},
                 "config":{"review":{"leftoverCheck":{"mode":"always","using":"samePolicy"}}}}
                """;
        final LoadedPolicy loaded = policy(json);
        assertEquals("always/samePolicy", ai.philterd.batch.cli.WorkerFactoryAccess.leftoverCheck(loaded));
        final TextRedactor.Redaction r = new PhileasRedactor(loaded, CONFIGURATION)
                .redact("Mail kept@example.com and gone@example.com.");
        assertTrue(r.text().contains("kept@example.com"));
        assertFalse(r.leftoverChecked());
        assertEquals(Map.of(), r.leftoverCounts());
    }

    @Test
    void noLeftoverCheckWhenThePolicyLeavesItOff() throws Exception {
        final LoadedPolicy loaded = policy(Fixtures.REGEX_POLICY);
        assertEquals("off", ai.philterd.batch.cli.WorkerFactoryAccess.leftoverCheck(loaded));
        final TextRedactor.Redaction r = new PhileasRedactor(loaded, CONFIGURATION).redact("SSN 123-45-6789.");
        assertFalse(r.leftoverChecked());
        assertEquals(Map.of(), r.leftoverCounts());
    }

    @Test
    void policiesThatCannotRunInProcessAreRefused(@TempDir final Path models) throws Exception {
        assertThrows(PolicyException.class, () -> policy("not json"));
        assertThrows(PolicyException.class, () -> policy("[]"));
        assertThrows(PolicyException.class, () -> policy("{\"name\":\"x\"}"));
        // A PhEye filter with no model path would call a PhEye service.
        assertThrows(PolicyException.class, () -> policy("{\"identifiers\":{\"person\":{}}}"));
        assertThrows(PolicyException.class, () -> LoadedPolicy.load(
                "{\"identifiers\":{\"person\":{\"phEyeConfiguration\":{\"modelPath\":\"missing-model\"}}}}"
                        .getBytes(StandardCharsets.UTF_8), models));
        assertThrows(PolicyException.class, () -> LoadedPolicy.load(
                "{\"identifiers\":{\"person\":{\"phEyeConfiguration\":{\"modelPath\":\"..bad name\"}}}}"
                        .getBytes(StandardCharsets.UTF_8), models));

        // A complete model directory (contents are not loaded here) resolves by name.
        final Path model = models.resolve("tiny-model");
        Files.createDirectories(model.resolve("onnx"));
        Files.writeString(model.resolve("gliner_config.json"), "{}");
        Files.writeString(model.resolve("tokenizer.json"), "{}");
        Files.writeString(model.resolve("onnx").resolve("model.onnx"), "x");
        final LoadedPolicy loaded = LoadedPolicy.load(("{\"identifiers\":{\"person\":{\"phEyeConfiguration\":"
                + "{\"modelPath\":\"tiny-model\"}}}}").getBytes(StandardCharsets.UTF_8), models);
        assertEquals(model.toRealPath().toString(),
                loaded.policy().getIdentifiers().getPerson().getPhEyeConfiguration().getModelPath());
        assertEquals("tiny-model", loaded.models().getFirst().name());
        assertEquals(64, loaded.models().getFirst().onnxSha256().length());
    }

}
