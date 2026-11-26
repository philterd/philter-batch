/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.sample;

import ai.philterd.batch.ExitCodes;
import ai.philterd.batch.Fixtures;
import ai.philterd.batch.TestClock;
import ai.philterd.batch.config.ConfigException;
import ai.philterd.batch.config.RunConfig;
import ai.philterd.batch.finalize.Finalizer;
import ai.philterd.batch.gate.Gates;
import ai.philterd.batch.gate.Gates.Decision;
import ai.philterd.batch.gate.Gates.Gate;
import ai.philterd.batch.inventory.Inventory;
import ai.philterd.batch.run.Formats.GateDecision;
import ai.philterd.batch.run.Formats.InventorySummary;
import ai.philterd.batch.run.Formats.SampleReport;
import ai.philterd.batch.run.Formats.Success;
import ai.philterd.batch.run.Hashes;
import ai.philterd.batch.run.Json;
import ai.philterd.batch.run.RunLayout;
import ai.philterd.batch.store.InMemoryObjectStore;
import ai.philterd.batch.store.ObjectInfo;
import ai.philterd.batch.worker.StopSignal;
import ai.philterd.batch.worker.Worker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleGateTest {

    private static final byte[] POLICY = Fixtures.REGEX_POLICY.getBytes(StandardCharsets.UTF_8);

    private final TestClock clock = TestClock.at("2026-11-26T12:00:00Z");
    private final InMemoryObjectStore store = new InMemoryObjectStore(clock);
    private final Fixtures.FakeRedactor redactor = new Fixtures.FakeRedactor();

    /** Twenty invented letters, each with two values the fake redactor replaces. */
    private void letters() {
        for (int i = 0; i < 20; i++) {
            Fixtures.put(store, "in/Avery Example " + i + "/letter-" + i + (i % 4 == 0 ? ".md" : ".txt"),
                    "Letter " + i + " for secret-name" + i + ", mail secret-mail" + i + "@example.com.\n"
                            + "Line two of letter " + i + ".\n".repeat(1 + i * 3));
        }
    }

    private RunConfig config(final String extra) {
        return Fixtures.config("sample: {enabled: true, size: 5, price_per_worker_hour: 0.5}\n"
                + "inventory: {shard_target_bytes: 400}\n" + extra);
    }

    private Worker worker(final RunConfig config) {
        return new Worker(store, config, redactor, List.of(), clock, new StopSignal(), "proc");
    }

    private InventorySummary sample(final RunConfig config) {
        final InventorySummary inventory = new Inventory(store, config, clock).run(POLICY);
        redactor.delayMillis = 2;
        assertEquals(ExitCodes.OK, worker(config).onlyShards(Set.copyOf(SampleReporter.sampleShards(inventory)))
                .run(true));
        assertEquals(ExitCodes.OK, new SampleReporter(store, config, clock).write(inventory, 2, 4));
        return inventory;
    }

    private String text(final RunLayout layout, final String key) {
        return new String(store.get(layout.bucket(), key).orElseThrow().data(), StandardCharsets.UTF_8);
    }

    @Test
    void theSampleIsRedactedFirstAndNotRedoneAfterApproval() {
        letters();
        final RunConfig config = config("");
        final RunLayout layout = new RunLayout(config);
        final InventorySummary inventory = sample(config);
        assertEquals(5, inventory.sampleShards());
        assertEquals(5, redactor.totalCalls(), "only the sample is redacted before approval");

        final Gates gates = new Gates(store, config);
        assertTrue(gates.active(Gate.SAMPLE));
        assertEquals(Optional.empty(), gates.decision(Gate.SAMPLE));
        final ConfigException waiting = assertThrows(ConfigException.class, gates::requireSampleApproved);
        assertTrue(waiting.getMessage().contains("has not been approved"), waiting.getMessage());

        final SampleReport report = Json.read(store.get(layout.bucket(), layout.sampleReport()).orElseThrow().data(),
                SampleReport.class);
        assertEquals(5, report.entries());
        assertEquals(5, report.samples().size());
        assertEquals(Long.valueOf(5), report.entriesByStatus().get("redacted"));
        assertEquals(Long.valueOf(10), report.piiCounts().get("secret"));
        assertNotNull(report.throughput());
        assertEquals(2, report.throughput().threads());
        assertEquals(4, report.estimate().workerPods());
        assertEquals(inventory.bytes() - inventory.sampleBytes(), report.estimate().remainingBytes());
        assertEquals(15, report.estimate().remainingEntries());
        assertEquals(report.estimate().workerHours() * 0.5, report.estimate().cost(), 1e-9);
        assertEquals("USD", report.estimate().currency());
        assertEquals(Inventory.sampleSeed(config), report.seed());
        // The sample's redacted records, under sample/.
        final String records = text(layout, layout.sampleRecords());
        assertEquals(5, records.split("\n").length);
        assertFalse(records.contains("secret-"));

        // Approval lets the rest run; the sampled documents are not redacted again.
        assertTrue(gates.decide(Gate.SAMPLE, Decision.APPROVED, "Test Reviewer", "looks right", clock));
        gates.requireSampleApproved();
        final GateDecision record = gates.record(Gate.SAMPLE).orElseThrow();
        assertEquals("Test Reviewer", record.by());
        assertEquals(Hashes.sha256(store.get(layout.bucket(), layout.sampleReportHtml()).orElseThrow().data()),
                record.sampleReportSha256());
        assertEquals(ExitCodes.OK, worker(config).run(true));
        assertEquals(20, redactor.calls.size());
        assertTrue(redactor.calls.values().stream().allMatch(n -> n.get() == 1), "every text redacted exactly once");
        assertEquals(ExitCodes.OK, new Finalizer(store, config, clock).run());
        final Success success = Json.read(store.get(layout.bucket(), layout.success()).orElseThrow().data(), Success.class);
        assertEquals(20, success.counts().records(), "the sample's records are part of the run's output");
    }

    @Test
    void theHtmlReportHoldsRedactedTextOnly() {
        letters();
        final RunConfig config = config("");
        final RunLayout layout = new RunLayout(config);
        sample(config);
        final String html = text(layout, layout.sampleReportHtml());
        final String json = text(layout, layout.sampleReport());

        // No original value, and no input key or file name, from any fixture document.
        for (final String report : List.of(html, json)) {
            assertFalse(report.contains("secret-"), "no original value");
            assertFalse(report.contains("Avery"), "no file name");
            assertFalse(report.contains("letter-"), "no file name");
            assertFalse(report.contains("in/"), "no key");
        }
        final Matcher original = Pattern.compile("secret-\\w+").matcher(html);
        assertFalse(original.find());

        // Replacements are highlighted by type.
        assertTrue(html.contains("<mark class=\"t0\" title=\"secret\">[REDACTED]</mark>"), html);
        assertTrue(html.contains("Letter "), "the redacted text is shown");
        assertTrue(html.contains("USD (at 0.5000 USD per worker pod hour)"), html);

        // Self-contained: no scripts and nothing fetched.
        assertTrue(html.contains("default-src 'none'"));
        assertFalse(html.toLowerCase(java.util.Locale.ROOT).contains("<script"));
        assertFalse(html.contains("http://") || html.contains("https://"), "no external reference");
        assertFalse(html.contains("src=") || html.contains("href=") || html.contains("url(")
                || html.contains("@import"));
    }

    @Test
    void theReportEscapesTextAndShowsQuarantinesWithoutText() {
        Fixtures.put(store, "in/a.txt", "<script>alert(1)</script> & secret-one\n");
        Fixtures.put(store, "in/b.txt", "this one EXPLODEs\n");
        Fixtures.put(store, "in/c.txt", "LEFTOVER secret-two\n");
        Fixtures.put(store, "in/d.txt", "OMEGA and secret-three\n");
        final RunConfig config = config("");
        final RunLayout layout = new RunLayout(config);
        sample(config);
        final String html = text(layout, layout.sampleReportHtml());
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; <mark"), html);
        assertTrue(html.contains("REDACTION_FAILED"));
        assertTrue(html.contains("LEFTOVER_FOUND"));
        assertTrue(html.contains("Quarantined: nothing was written for this entry"));
        assertTrue(html.contains("could not be placed exactly"), "unplaced replacements are said to be unplaced");
        assertFalse(html.contains("secret-"));
        assertFalse(html.contains("EXPLODE"), "a quarantined entry's text is never shown");
    }

    @Test
    void longTextIsCutInTheReport() {
        Fixtures.put(store, "in/long.txt", ("secret-x " + "word ".repeat(40) + "\n").repeat(200));
        final RunConfig config = config("sample: {enabled: true, size: 5, report_max_chars: 1000}\n");
        sample(config);
        final RunLayout layout = new RunLayout(config);
        final String html = text(layout, layout.sampleReportHtml());
        assertTrue(html.contains("Showing the first 1,000 of"), html);
        // A replacement past the cut is left out, never shown partly.
        assertFalse(html.contains("[REDACTED</mark>"));
    }

    @Test
    void documentModeWritesTheSampleDocuments() {
        letters();
        final RunConfig config = config("output: {bucket: output-bucket, prefix: out/, mode: document}\n");
        final RunLayout layout = new RunLayout(config);
        sample(config);
        final List<ObjectInfo> documents = store.list(layout.bucket(), layout.samplePrefix() + "documents/");
        assertEquals(5, documents.size());
        for (final ObjectInfo o : documents) {
            assertFalse(text(layout, o.key()).contains("secret-"));
        }
        assertTrue(store.get(layout.bucket(), layout.sampleRecords()).isEmpty(), "no records in document mode");
        final String html = text(layout, layout.sampleReportHtml());
        assertTrue(html.contains("<mark class=\"t0\" title=\"secret\">[REDACTED]</mark>"));
    }

    @Test
    void aRejectionEndsTheRun() {
        letters();
        final RunConfig config = config("");
        sample(config);
        final Gates gates = new Gates(store, config);
        assertTrue(gates.decide(Gate.SAMPLE, Decision.REJECTED, "Test Reviewer", "wrong policy", clock));
        assertEquals(Optional.of(Decision.REJECTED), gates.await(Gate.SAMPLE, Duration.ofMillis(10), new StopSignal()));
        final ConfigException rejected = assertThrows(ConfigException.class, gates::requireSampleApproved);
        assertTrue(rejected.getMessage().contains("rejected"));
        assertThrows(ConfigException.class, () -> gates.decide(Gate.SAMPLE, Decision.APPROVED, null, null, clock));
        assertFalse(gates.decide(Gate.SAMPLE, Decision.REJECTED, null, null, clock), "the same decision again");
    }

    @Test
    void aRejectionWinsOverAnApproval() {
        letters();
        final RunConfig config = config("");
        final RunLayout layout = new RunLayout(config);
        sample(config);
        // Markers written by hand (aws s3 cp): presence is the decision, whatever the content.
        store.put(layout.bucket(), layout.gateMarker("sample", "approved"), new byte[0], "application/octet-stream");
        final Gates gates = new Gates(store, config);
        assertEquals(Optional.of(Decision.APPROVED), gates.decision(Gate.SAMPLE));
        assertEquals(Optional.empty(), gates.record(Gate.SAMPLE), "a hand-written marker has no record");
        store.put(layout.bucket(), layout.gateMarker("sample", "rejected"), "no".getBytes(StandardCharsets.UTF_8),
                "text/plain");
        assertEquals(Optional.of(Decision.REJECTED), gates.decision(Gate.SAMPLE));
    }

    @Test
    void theSampleCannotBeApprovedBeforeItsReport() {
        letters();
        final RunConfig config = config("");
        new Inventory(store, config, clock).run(POLICY);
        final Gates gates = new Gates(store, config);
        final ConfigException early = assertThrows(ConfigException.class,
                () -> gates.decide(Gate.SAMPLE, Decision.APPROVED, null, null, clock));
        assertTrue(early.getMessage().contains("not ready"));
        // Rejecting needs no report: a run can be abandoned at any point before the gate.
        assertTrue(gates.decide(Gate.SAMPLE, Decision.REJECTED, null, null, clock));
    }

    @Test
    void withTheGateOffNothingWaits() {
        letters();
        final RunConfig config = config("sample: {enabled: false}\nrelease_gate: {enabled: false}\n");
        final InventorySummary inventory = new Inventory(store, config, clock).run(POLICY);
        assertEquals(0, inventory.sampleShards());
        final Gates gates = new Gates(store, config);
        assertFalse(gates.active(Gate.SAMPLE));
        gates.requireSampleApproved();
        assertEquals(Optional.of(Decision.APPROVED), gates.await(Gate.SAMPLE, Duration.ofHours(1), new StopSignal()));
        assertEquals(Optional.of(Decision.APPROVED), gates.await(Gate.RELEASE, Duration.ofHours(1), new StopSignal()));
        assertThrows(ConfigException.class, () -> gates.decide(Gate.SAMPLE, Decision.APPROVED, null, null, clock));
        assertThrows(ConfigException.class, () -> gates.decide(Gate.RELEASE, Decision.APPROVED, null, null, clock));
        // The sample step does nothing; the reporter has nothing to report.
        assertEquals(ExitCodes.OK, worker(config).run(true));
        assertEquals(20, redactor.totalCalls());
    }

    @Test
    void theReleaseGateWaitsForFinalizeAndADecision() throws Exception {
        letters();
        final RunConfig config = config("sample: {enabled: false}\n");
        new Inventory(store, config, clock).run(POLICY);
        final Gates gates = new Gates(store, config);
        assertTrue(gates.active(Gate.RELEASE));
        assertThrows(ConfigException.class, () -> gates.decide(Gate.RELEASE, Decision.APPROVED, null, null, clock),
                "not finalized yet");
        assertEquals(ExitCodes.OK, worker(config).run(true));
        assertEquals(ExitCodes.OK, new Finalizer(store, config, clock).run());

        // A wait that a decision from elsewhere ends.
        final StopSignal stop = new StopSignal();
        final Thread approver = new Thread(() -> {
            try {
                Thread.sleep(200);
                gates.decide(Gate.RELEASE, Decision.APPROVED, "Test Reviewer", null, clock);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        approver.start();
        assertEquals(Optional.of(Decision.APPROVED), gates.await(Gate.RELEASE, Duration.ofMillis(50), stop));
        approver.join();
        assertNull(gates.record(Gate.RELEASE).orElseThrow().sampleReportSha256());

        // A stop ends a wait without a decision.
        final RunConfig other = config("run_id: other-run\nsample: {enabled: false}\n");
        final StopSignal stopped = new StopSignal();
        stopped.stop();
        assertEquals(Optional.empty(), new Gates(store, other).await(Gate.RELEASE, Duration.ofMillis(10), stopped));
    }

    @Test
    void reportingIsIdempotentAndWaitsForEverySampleShard() {
        letters();
        final RunConfig config = config("");
        final InventorySummary inventory = new Inventory(store, config, clock).run(POLICY);
        final SampleReporter reporter = new SampleReporter(store, config, clock);
        assertEquals(ExitCodes.INCOMPLETE, reporter.write(inventory, 1, 1));
        assertTrue(reporter.existing().isEmpty());
        assertEquals(ExitCodes.OK, worker(config).onlyShards(Set.copyOf(SampleReporter.sampleShards(inventory)))
                .run(true));
        assertEquals(ExitCodes.OK, reporter.write(inventory, 1, 1));
        final RunLayout layout = new RunLayout(config);
        final String first = text(layout, layout.sampleReport());
        clock.advance(Duration.ofHours(1));
        assertEquals(ExitCodes.OK, reporter.write(inventory, 1, 1));
        assertEquals(first, text(layout, layout.sampleReport()), "an existing report is kept");
    }

    @Test
    void formatsSizesAndDurations() {
        assertEquals("512 B", HtmlSampleReport.bytes(512));
        assertEquals("1.5 KiB", HtmlSampleReport.bytes(1536));
        assertEquals("2.0 GiB", HtmlSampleReport.bytes(2L << 30));
        assertEquals("under a minute", HtmlSampleReport.duration(0.001));
        assertEquals("30 minutes", HtmlSampleReport.duration(0.5));
        assertEquals("5.0 hours", HtmlSampleReport.duration(5));
        assertEquals("3.0 days", HtmlSampleReport.duration(72));
        assertEquals("&lt;&amp;&gt;&quot;&#39;", HtmlSampleReport.esc("<&>\"'"));
    }

}
