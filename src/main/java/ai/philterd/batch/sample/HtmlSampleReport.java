/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.sample;

import ai.philterd.batch.run.Formats;
import ai.philterd.batch.run.Formats.Estimate;
import ai.philterd.batch.run.Formats.ModelInfo;
import ai.philterd.batch.run.Formats.Replacement;
import ai.philterd.batch.run.Formats.SampleEntry;
import ai.philterd.batch.run.Formats.SampleReport;
import ai.philterd.batch.run.Formats.Throughput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The sample report as one static HTML file: no scripts, fonts, images, or requests of any kind (its content security
 * policy forbids them), so it opens from disk and outlives the cluster. It holds redacted text only: the text the run
 * wrote for each sampled entry, with the replacements highlighted by type. It never holds an original value or an
 * input key, since a file pairing originals with their replacements would itself be sensitive.
 */
public final class HtmlSampleReport {

    /** A sampled entry's redacted text and where its replacements are, which may be null. */
    public record Text(String text, List<Replacement> replacements) {
    }

    /** Background colors for the types, light enough for dark text, distinct from each other. */
    private static final String[] COLORS = {"#ffd166", "#8ecae6", "#b5e48c", "#f4a6c1", "#cdb4db", "#ffb703",
            "#90e0ef", "#f6bd60", "#a3c4f3", "#c7f9cc", "#ffadad", "#e9c46a"};

    private HtmlSampleReport() {
    }

    public static String render(final SampleReport report, final Map<String, Text> texts, final int maxChars) {
        final List<String> types = types(report, texts);
        final StringBuilder h = new StringBuilder(64 * 1024);
        h.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta name=\"referrer\" content=\"no-referrer\">\n")
                .append("<title>Sample report: ").append(esc(report.runId())).append("</title>\n<style>\n")
                .append(css(types)).append("</style>\n</head>\n<body>\n<main>\n");

        h.append("<h1>Philter Batch sample report</h1>\n");
        h.append("<p class=\"notice\">This report holds the redacted text of a sample of run <b>")
                .append(esc(report.runId())).append("</b>, never the original values. Redaction is probabilistic: the "
                        + "text below may still contain sensitive values the policy did not detect. Handle this file as "
                        + "you would the run's output.</p>\n");

        h.append("<h2>Run</h2>\n<table>\n");
        row(h, "Run", report.runId());
        row(h, "Sample completed", String.valueOf(report.completed()));
        row(h, "Policy", report.policySha256() == null ? "" : "sha256:" + report.policySha256());
        row(h, "Output mode", report.outputMode());
        row(h, "Phileas", report.phileasVersion() + " (phileas-pheye-onnx " + report.pheyeOnnxVersion() + ")");
        if (report.models() != null) {
            for (final ModelInfo m : report.models()) {
                row(h, "Model", m.name() + " (ONNX sha256:" + m.onnxSha256() + ")");
            }
        }
        row(h, "Sampled", number(report.entries()) + " entries of " + number(report.documents()) + " documents, "
                + bytes(report.bytes()) + " (seed " + report.seed() + ")");
        h.append("</table>\n");

        if (report.policyWarnings() != null && !report.policyWarnings().isEmpty()) {
            h.append("<h2>Policy warnings</h2>\n<p>The policy uses strategies that weaken the output. They are not "
                    + "refused; check that each is intended.</p>\n<ul class=\"warnings\">\n");
            for (final Formats.PolicyWarning w : report.policyWarnings()) {
                h.append("<li><b>").append(esc(w.strategy())).append("</b> at <code>").append(esc(w.where()))
                        .append("</code>: ").append(esc(w.message())).append("</li>\n");
            }
            h.append("</ul>\n");
        }

        h.append("<h2>Decision</h2>\n<p>Approve the sample to start the rest of the run, or reject it to end the run. "
                + "A run is tied to its policy: to change the policy, reject this run and start a new one.</p>\n<pre class=\"cmd\">")
                .append(esc("philter-batch approve " + report.runId() + " sample --by \"<name>\""))
                .append("\n").append(esc("philter-batch reject " + report.runId() + " sample --by \"<name>\" --note \"<why>\""))
                .append("</pre>\n");

        h.append("<h2>Results</h2>\n<div class=\"grid\">\n");
        counts(h, "Entries by status", report.entriesByStatus(), null);
        counts(h, "Quarantined, by reason", report.quarantinedByReason(), "None");
        countsByType(h, "Values replaced, by type", report.piiCounts(), types);
        counts(h, "Leftover check findings, by type", report.leftoverCounts(), "None");
        counts(h, "Entries by file type", report.entriesByExtension(), null);
        h.append("</div>\n<p>The leftover check ran on ").append(number(report.leftoverChecked())).append(" of ")
                .append(number(report.entries())).append(" entries.</p>\n");

        h.append("<h2>Throughput and estimate</h2>\n");
        final Throughput t = report.throughput();
        final Estimate e = report.estimate();
        if (t == null) {
            h.append("<p>No entry was timed, so there is no estimate.</p>\n");
        } else {
            h.append("<table>\n");
            row(h, "Measured", bytes(t.bytes()) + " in " + decimal(t.processingSeconds()) + " s of processing");
            row(h, "Per thread", bytes((long) t.bytesPerSecondPerThread()) + "/s");
            row(h, "Per worker pod", bytes((long) t.bytesPerSecondPerPod()) + "/s (" + t.threads() + " thread"
                    + (t.threads() == 1 ? "" : "s") + ")");
            row(h, "Remaining", bytes(e.remainingBytes()) + " in " + number(e.remainingEntries()) + " entries");
            row(h, "Worker pods", String.valueOf(e.workerPods()));
            row(h, "Estimated time", duration(e.hours()));
            row(h, "Worker pod hours", decimal(e.workerHours()));
            if (e.cost() != null) {
                row(h, "Estimated cost", String.format(Locale.ROOT, "%,.2f %s (at %,.4f %s per worker pod hour)",
                        e.cost(), e.currency(), e.pricePerWorkerHour(), e.currency()));
            }
            h.append("</table>\n<p class=\"small\">From the sample's processing time per entry, so it leaves out pod "
                    + "start-up and model loading, retries, and interrupted spot nodes. A sample this size gives a "
                    + "rough estimate.</p>\n");
        }

        h.append("<h2>Sampled text</h2>\n<p>Each entry is a file, or a segment of a large file, named by its opaque "
                + "document ID. Replacements are highlighted by type; hover over one for its type.</p>\n");
        h.append("<p class=\"legend\">");
        for (final String type : types) {
            h.append("<mark class=\"").append(typeClass(types, type)).append("\">").append(esc(type)).append("</mark> ");
        }
        h.append("</p>\n");
        int n = 0;
        for (final SampleEntry s : report.samples()) {
            n++;
            h.append("<section>\n<h3>").append(n).append(". ").append(esc(s.documentId()));
            if (s.segments() > 1) {
                h.append(", segment ").append(s.segment() + 1).append(" of ").append(s.segments());
            }
            h.append("</h3>\n<p class=\"meta\">").append(esc(s.extension())).append(", ").append(bytes(s.bytes()))
                    .append(", <b>").append(esc(s.status())).append("</b>");
            if (s.reason() != null) {
                h.append(" (").append(esc(s.reason())).append(")");
            }
            if (s.micros() != null) {
                h.append(", ").append(String.format(Locale.ROOT, "%,.3f", s.micros() / 1_000_000.0)).append(" s");
            }
            if (s.piiCounts() != null && !s.piiCounts().isEmpty()) {
                h.append(". Replaced: ").append(esc(join(s.piiCounts())));
            }
            if (s.leftoverCounts() != null && !s.leftoverCounts().isEmpty()) {
                h.append(". Leftover check found: ").append(esc(join(s.leftoverCounts())));
            }
            h.append("</p>\n");
            final Text text = texts.get(s.id());
            if (text == null) {
                h.append("<p class=\"none\">").append("quarantined".equals(s.status())
                        ? "Quarantined: nothing was written for this entry, so there is no text to show."
                        : "No text.").append("</p>\n");
            } else {
                if (text.replacements() == null && s.piiCounts() != null && !s.piiCounts().isEmpty()) {
                    h.append("<p class=\"small\">The replacements in this entry could not be placed exactly, so they "
                            + "are not highlighted.</p>\n");
                }
                if (text.text().length() > maxChars) {
                    h.append("<p class=\"small\">Showing the first ").append(number(maxChars)).append(" of ")
                            .append(number(text.text().length())).append(" characters.</p>\n");
                }
                h.append("<pre>");
                highlight(h, text, maxChars, types);
                h.append("</pre>\n");
            }
            h.append("</section>\n");
        }
        h.append("</main>\n</body>\n</html>\n");
        return h.toString();
    }

    /** Every type in the counts and the replacements, sorted, so each gets a stable color. */
    private static List<String> types(final SampleReport report, final Map<String, Text> texts) {
        final TreeSet<String> types = new TreeSet<>();
        if (report.piiCounts() != null) {
            types.addAll(report.piiCounts().keySet());
        }
        for (final Text t : texts.values()) {
            if (t.replacements() != null) {
                t.replacements().forEach(r -> types.add(r.type()));
            }
        }
        return new ArrayList<>(types);
    }

    private static String typeClass(final List<String> types, final String type) {
        return "t" + (types.indexOf(type) % COLORS.length);
    }

    private static void highlight(final StringBuilder h, final Text text, final int maxChars, final List<String> types) {
        final String s = text.text();
        final int limit = Math.min(s.length(), maxChars);
        int at = 0;
        if (text.replacements() != null) {
            final List<Replacement> ordered = new ArrayList<>(text.replacements());
            ordered.sort(Comparator.comparingInt(Replacement::start));
            for (final Replacement r : ordered) {
                if (r.start() < at || r.end() > limit) {
                    // Overlapping (not produced by Phileas) or past the cut: shown as plain text.
                    continue;
                }
                h.append(esc(s.substring(at, r.start())));
                h.append("<mark class=\"").append(typeClass(types, r.type())).append("\" title=\"")
                        .append(esc(r.type())).append("\">").append(esc(s.substring(r.start(), r.end())))
                        .append("</mark>");
                at = r.end();
            }
        }
        h.append(esc(s.substring(at, limit)));
    }

    private static String css(final List<String> types) {
        final StringBuilder c = new StringBuilder("""
                body{margin:0;background:#fff;color:#1d1d1f;font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
                main{max-width:1100px;margin:0 auto;padding:24px 16px 64px}
                h1{font-size:26px;margin:0 0 12px}h2{font-size:19px;margin:32px 0 8px;border-bottom:1px solid #ddd}
                h3{font-size:15px;margin:24px 0 4px;font-family:ui-monospace,Menlo,Consolas,monospace;word-break:break-all}
                table{border-collapse:collapse;margin:8px 0}td,th{text-align:left;padding:3px 12px 3px 0;vertical-align:top}
                th{font-weight:600;color:#555}td.n{text-align:right;font-variant-numeric:tabular-nums}
                .grid{display:flex;flex-wrap:wrap;gap:8px 40px}
                .notice{background:#fff4e5;border-left:4px solid #e08e0b;padding:8px 12px}
                .small,.meta{color:#555;font-size:13px}.none{color:#777;font-style:italic}.warnings li{margin:4px 0}
                pre{white-space:pre-wrap;word-wrap:break-word;background:#f7f7f8;border:1px solid #e3e3e6;padding:12px;
                font:13px/1.5 ui-monospace,Menlo,Consolas,monospace;max-height:480px;overflow:auto}
                pre.cmd{max-height:none}
                mark{border-radius:3px;padding:0 2px;color:#1d1d1f}
                """);
        for (int i = 0; i < Math.min(types.size(), COLORS.length); i++) {
            c.append(".t").append(i).append("{background:").append(COLORS[i]).append("}\n");
        }
        return c.toString();
    }

    private static void row(final StringBuilder h, final String name, final String value) {
        h.append("<tr><th>").append(esc(name)).append("</th><td>").append(esc(value)).append("</td></tr>\n");
    }

    private static void counts(final StringBuilder h, final String title, final Map<String, Long> counts,
                               final String empty) {
        h.append("<div><h4>").append(esc(title)).append("</h4>");
        if (counts == null || counts.isEmpty()) {
            h.append("<p class=\"none\">").append(esc(empty == null ? "None" : empty)).append("</p></div>\n");
            return;
        }
        h.append("<table>");
        counts.forEach((k, v) -> h.append("<tr><th>").append(esc(k)).append("</th><td class=\"n\">").append(number(v))
                .append("</td></tr>"));
        h.append("</table></div>\n");
    }

    private static void countsByType(final StringBuilder h, final String title, final Map<String, Long> counts,
                                     final List<String> types) {
        h.append("<div><h4>").append(esc(title)).append("</h4>");
        if (counts == null || counts.isEmpty()) {
            h.append("<p class=\"none\">None</p></div>\n");
            return;
        }
        h.append("<table>");
        counts.forEach((k, v) -> h.append("<tr><th><mark class=\"").append(typeClass(types, k)).append("\">")
                .append(esc(k)).append("</mark></th><td class=\"n\">").append(number(v)).append("</td></tr>"));
        h.append("</table></div>\n");
    }

    private static String join(final Map<String, Integer> counts) {
        final StringBuilder s = new StringBuilder();
        counts.forEach((k, v) -> s.append(s.isEmpty() ? "" : ", ").append(k).append(" ").append(v));
        return s.toString();
    }

    static String esc(final String s) {
        if (s == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String number(final long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String decimal(final double d) {
        return String.format(Locale.ROOT, "%,.1f", d);
    }

    static String bytes(final long b) {
        final String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
        double v = b;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u == 0 ? b + " B" : String.format(Locale.ROOT, "%.1f %s", v, units[u]);
    }

    static String duration(final double hours) {
        if (hours * 60 < 1) {
            return "under a minute";
        }
        if (hours < 1) {
            return String.format(Locale.ROOT, "%.0f minutes", hours * 60);
        }
        if (hours < 48) {
            return String.format(Locale.ROOT, "%.1f hours", hours);
        }
        return String.format(Locale.ROOT, "%.1f days", hours / 24);
    }

}
