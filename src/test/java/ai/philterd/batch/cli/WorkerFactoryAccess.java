/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.cli;

import ai.philterd.batch.redaction.LoadedPolicy;

/** Test access to {@link WorkerFactory}'s package-private helpers. */
public final class WorkerFactoryAccess {

    private WorkerFactoryAccess() {
    }

    public static String leftoverCheck(final LoadedPolicy policy) {
        return WorkerFactory.leftoverCheck(policy);
    }

}
