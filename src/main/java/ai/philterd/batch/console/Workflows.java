/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.console;

import java.time.Instant;
import java.util.List;

/** The Argo workflows of the deployment's WorkflowTemplate: submitting a run and reading where runs are. */
public interface Workflows {

    /**
     * One workflow.
     * @param phase Argo's phase: Pending, Running, Succeeded, Failed, or Error; null just after submission.
     */
    record Info(String name, String runId, String phase, Instant started, Instant finished, String message) {

        public boolean active() {
            return phase == null || phase.isEmpty() || "Pending".equals(phase) || "Running".equals(phase);
        }
    }

    /** Submits a workflow for the run from the WorkflowTemplate, and returns its name. */
    String submit(String runId);

    /** The namespace's workflows, newest first. */
    List<Info> list();

    /** The workflows of one run, newest first. */
    default List<Info> list(final String runId) {
        return list().stream().filter(w -> runId.equals(w.runId())).toList();
    }

}
