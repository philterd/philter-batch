/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.console;

import ai.philterd.batch.config.ConfigException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Argo workflows through the Kubernetes API, from inside the cluster, with the pod's service account. It needs only
 * {@code create} and {@code list} on {@code workflows.argoproj.io} in the namespace: a workflow names the chart's
 * WorkflowTemplate, which Argo's controller resolves. No Kubernetes client library or {@code argo} binary is needed.
 */
public final class KubernetesWorkflows implements Workflows {

    /** The environment variable naming the WorkflowTemplate; the Helm chart sets it on the console pod. */
    public static final String TEMPLATE_ENVIRONMENT = "PHILTER_BATCH_WORKFLOW_TEMPLATE";

    /** The annotation recording a workflow's run. */
    public static final String RUN_ANNOTATION = "philterd.ai/run-id";

    static final Path SERVICE_ACCOUNT = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI workflows;
    private final Path token;
    private final String template;

    KubernetesWorkflows(final HttpClient http, final URI api, final String namespace, final Path token,
                        final String template) {
        this.http = http;
        this.workflows = api.resolve("/apis/argoproj.io/v1alpha1/namespaces/" + namespace + "/workflows");
        this.token = token;
        this.template = template;
    }

    /** From the pod's service account and the environment. */
    public static KubernetesWorkflows inCluster(final Function<String, String> env) {
        final String host = env.apply("KUBERNETES_SERVICE_HOST");
        final String port = env.apply("KUBERNETES_SERVICE_PORT");
        final String template = env.apply(TEMPLATE_ENVIRONMENT);
        if (host == null || port == null || !Files.isRegularFile(SERVICE_ACCOUNT.resolve("token"))) {
            throw new ConfigException("Not in a Kubernetes pod with a service account: submit and status work from "
                    + "the console pod.");
        }
        if (template == null || template.isBlank()) {
            throw new ConfigException(TEMPLATE_ENVIRONMENT + " is not set: the console pod's chart sets it.");
        }
        try {
            final String namespace = Files.readString(SERVICE_ACCOUNT.resolve("namespace")).trim();
            final KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            try (InputStream in = Files.newInputStream(SERVICE_ACCOUNT.resolve("ca.crt"))) {
                int i = 0;
                for (final Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    trust.setCertificateEntry("ca-" + i++, c);
                }
            }
            final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            final SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            final HttpClient http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(10)).build();
            final String h = host.contains(":") ? "[" + host + "]" : host;
            return new KubernetesWorkflows(http, URI.create("https://" + h + ":" + port), namespace,
                    SERVICE_ACCOUNT.resolve("token"), template);
        } catch (final IOException | GeneralSecurityException e) {
            throw new ConfigException("Could not set up the Kubernetes client: " + e.getMessage(), e);
        }
    }

    /** A workflow name prefix from the run ID: lowercase letters, digits, and hyphens. */
    static String namePrefix(final String runId) {
        String s = runId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-");
        s = s.substring(0, Math.min(s.length(), 40)).replaceAll("^-+|-+$", "");
        return (s.isEmpty() ? "run" : s) + "-";
    }

    /** The Workflow the console creates: the template, the run as its parameter, and the run in an annotation. */
    static ObjectNode workflow(final String template, final String runId) {
        final ObjectNode w = JSON.createObjectNode();
        w.put("apiVersion", "argoproj.io/v1alpha1");
        w.put("kind", "Workflow");
        final ObjectNode metadata = w.putObject("metadata");
        metadata.put("generateName", namePrefix(runId));
        metadata.putObject("annotations").put(RUN_ANNOTATION, runId);
        final ObjectNode spec = w.putObject("spec");
        spec.putObject("workflowTemplateRef").put("name", template);
        final ArrayNode parameters = spec.putObject("arguments").putArray("parameters");
        parameters.addObject().put("name", "run-id").put("value", runId);
        return w;
    }

    @Override
    public String submit(final String runId) {
        final JsonNode created = send(HttpRequest.newBuilder(workflows)
                .POST(HttpRequest.BodyPublishers.ofString(workflow(template, runId).toString()))
                .header("Content-Type", "application/json"), 201);
        return created.path("metadata").path("name").asText();
    }

    @Override
    public List<Info> list() {
        return parse(send(HttpRequest.newBuilder(workflows).GET(), 200), template);
    }

    /** The workflows of the template in a WorkflowList, newest first. */
    static List<Info> parse(final JsonNode list, final String template) {
        final List<Info> out = new ArrayList<>();
        for (final JsonNode w : list.path("items")) {
            final String ref = w.path("spec").path("workflowTemplateRef").path("name").asText(null);
            final String label = w.path("metadata").path("labels").path("workflows.argoproj.io/workflow-template")
                    .asText(null);
            if (!template.equals(ref) && !template.equals(label)) {
                continue;
            }
            String runId = w.path("metadata").path("annotations").path(RUN_ANNOTATION).asText(null);
            if (runId == null) {
                for (final JsonNode p : w.path("spec").path("arguments").path("parameters")) {
                    if ("run-id".equals(p.path("name").asText())) {
                        runId = p.path("value").asText(null);
                    }
                }
            }
            final JsonNode status = w.path("status");
            out.add(new Info(w.path("metadata").path("name").asText(), runId, status.path("phase").asText(null),
                    instant(status.path("startedAt")), instant(status.path("finishedAt")),
                    status.path("message").asText(null)));
        }
        out.sort(Comparator.comparing((Info i) -> i.started() == null ? Instant.MAX : i.started()).reversed());
        return out;
    }

    private static Instant instant(final JsonNode node) {
        return node.isTextual() && !node.asText().isEmpty() ? Instant.parse(node.asText()) : null;
    }

    private JsonNode send(final HttpRequest.Builder request, final int expected) {
        try {
            final HttpResponse<String> response = http.send(request
                    .header("Authorization", "Bearer " + Files.readString(token, StandardCharsets.UTF_8).trim())
                    .header("Accept", "application/json").timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            final JsonNode body = response.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(response.body());
            if (response.statusCode() != expected) {
                throw new ConfigException("The Kubernetes API refused the request (" + response.statusCode() + "): "
                        + body.path("message").asText("no message"));
            }
            return body;
        } catch (final IOException e) {
            throw new ConfigException("Could not reach the Kubernetes API: " + e.getMessage(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConfigException("Interrupted.", e);
        }
    }

}
