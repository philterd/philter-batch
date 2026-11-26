/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.console;

import ai.philterd.batch.config.ConfigException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesWorkflowsTest {

    @Test
    void namesWorkflowsAfterTheRun() {
        assertEquals("pilot-2026-11-", KubernetesWorkflows.namePrefix("pilot-2026-11"));
        assertEquals("run-a-b-", KubernetesWorkflows.namePrefix("Run_A.b"));
        assertEquals("run-", KubernetesWorkflows.namePrefix("___"));
        assertEquals(41, KubernetesWorkflows.namePrefix("a".repeat(64)).length());
    }

    @Test
    void submitsAWorkflowFromTheTemplate() {
        final JsonNode w = KubernetesWorkflows.workflow("pb-philter-batch", "pilot_2026");
        assertEquals("argoproj.io/v1alpha1", w.path("apiVersion").asText());
        assertEquals("Workflow", w.path("kind").asText());
        assertEquals("pilot-2026-", w.path("metadata").path("generateName").asText());
        assertEquals("pilot_2026", w.path("metadata").path("annotations").path("philterd.ai/run-id").asText());
        assertEquals("pb-philter-batch", w.path("spec").path("workflowTemplateRef").path("name").asText());
        final JsonNode p = w.path("spec").path("arguments").path("parameters").get(0);
        assertEquals("run-id", p.path("name").asText());
        assertEquals("pilot_2026", p.path("value").asText());
    }

    @Test
    void readsTheTemplatesWorkflowsNewestFirst() throws Exception {
        final JsonNode list = new ObjectMapper().readTree("""
                {"items":[
                  {"metadata":{"name":"a-1","annotations":{"philterd.ai/run-id":"a"}},
                   "spec":{"workflowTemplateRef":{"name":"t"}},
                   "status":{"phase":"Failed","startedAt":"2026-11-26T10:00:00Z","finishedAt":"2026-11-26T10:05:00Z",
                             "message":"child failed"}},
                  {"metadata":{"name":"a-2","labels":{"workflows.argoproj.io/workflow-template":"t"}},
                   "spec":{"arguments":{"parameters":[{"name":"run-id","value":"a"}]}},
                   "status":{"phase":"Running","startedAt":"2026-11-26T11:00:00Z"}},
                  {"metadata":{"name":"b-1","annotations":{"philterd.ai/run-id":"b"}},
                   "spec":{"workflowTemplateRef":{"name":"t"}}},
                  {"metadata":{"name":"other","annotations":{"philterd.ai/run-id":"a"}},
                   "spec":{"workflowTemplateRef":{"name":"another-template"}}}]}
                """);
        final List<Workflows.Info> all = KubernetesWorkflows.parse(list, "t");
        assertEquals(List.of("b-1", "a-2", "a-1"), all.stream().map(Workflows.Info::name).toList());
        final Workflows.Info failed = all.get(2);
        assertEquals("a", failed.runId());
        assertEquals("Failed", failed.phase());
        assertEquals(Instant.parse("2026-11-26T10:05:00Z"), failed.finished());
        assertEquals("child failed", failed.message());
        assertFalse(failed.active());
        assertTrue(all.get(1).active());
        assertTrue(all.get(0).active(), "a workflow without a phase has just been submitted");
        assertEquals("a", all.get(1).runId(), "the run from the parameter when there is no annotation");

        final Workflows workflows = new Workflows() {
            @Override
            public String submit(final String runId) {
                return null;
            }

            @Override
            public List<Info> list() {
                return all;
            }
        };
        assertEquals(List.of("a-2", "a-1"), workflows.list("a").stream().map(Workflows.Info::name).toList());
    }

    @Test
    void refusesToStartOutsideACluster() {
        assertThrows(ConfigException.class, () -> KubernetesWorkflows.inCluster(name -> null));
    }

    @Test
    void callsTheKubernetesApiWithTheServiceAccountToken(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir)
            throws Exception {
        final java.nio.file.Path token = dir.resolve("token");
        java.nio.file.Files.writeString(token, "test-token\n");
        final List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        final com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
            final String reply;
            final int status;
            if (exchange.getRequestURI().getPath().contains("/namespaces/denied/")) {
                status = 403;
                reply = "{\"kind\":\"Status\",\"message\":\"workflows is forbidden\"}";
            } else if ("POST".equals(exchange.getRequestMethod())) {
                status = 201;
                reply = "{\"metadata\":{\"name\":\"pilot-x7k2p\"}}";
            } else {
                status = 200;
                reply = "{\"items\":[{\"metadata\":{\"name\":\"pilot-x7k2p\",\"annotations\":"
                        + "{\"philterd.ai/run-id\":\"pilot\"}},\"spec\":{\"workflowTemplateRef\":{\"name\":\"t\"}},"
                        + "\"status\":{\"phase\":\"Running\",\"startedAt\":\"2026-11-26T12:00:00Z\"}}]}";
            }
            final byte[] bytes = reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            final java.net.URI api = java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
            final KubernetesWorkflows workflows = new KubernetesWorkflows(http, api, "pb", token, "t");
            assertEquals("pilot-x7k2p", workflows.submit("pilot"));
            assertTrue(seen.getFirst().startsWith("POST /apis/argoproj.io/v1alpha1/namespaces/pb/workflows "
                    + "Bearer test-token {"), seen.getFirst());
            assertTrue(seen.getFirst().contains("\"workflowTemplateRef\":{\"name\":\"t\"}"));
            final List<Workflows.Info> list = workflows.list("pilot");
            assertEquals(1, list.size());
            assertEquals("Running", list.getFirst().phase());
            final ConfigException denied = assertThrows(ConfigException.class,
                    () -> new KubernetesWorkflows(http, api, "denied", token, "t").list());
            assertTrue(denied.getMessage().contains("(403): workflows is forbidden"), denied.getMessage());
            final ConfigException unreachable = assertThrows(ConfigException.class, () -> new KubernetesWorkflows(http,
                    java.net.URI.create("http://127.0.0.1:1"), "pb", token, "t").list());
            assertTrue(unreachable.getMessage().startsWith("Could not reach the Kubernetes API"));
        } finally {
            server.stop(0);
        }
    }

}
