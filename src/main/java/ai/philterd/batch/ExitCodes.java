/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch;

/**
 * Process exit codes, modeled on the OpenSearch Migration Assistant's RFS workers: a worker exits 0 only when no
 * work is left, and with a distinct code when work remains but every shard is claimed, so Kubernetes or Argo does not
 * restart it in a tight loop.
 */
public final class ExitCodes {

    /** Done: no work left (worker), or the step completed (inventory, finalize). */
    public static final int OK = 0;
    /** An error: bad configuration, a store failure, or a settings mismatch with the run. */
    public static final int ERROR = 1;
    /** A command-line usage error (picocli's code). */
    public static final int USAGE = 2;
    /** Stopped by SIGTERM or SIGINT after saving progress. Work remains. */
    public static final int STOPPED = 3;
    /** Work remains, but every remaining shard is claimed by another worker. */
    public static final int ALL_LEASED = 4;
    /** Finalize: not every shard has a done marker yet. */
    public static final int INCOMPLETE = 5;

    private ExitCodes() {
    }

}
