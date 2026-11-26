/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.worker;

import ai.philterd.batch.run.Formats.ShardEntry;

/** Processes one shard entry. */
public interface EntryProcessor {

    DocumentProcessor.Outcome process(int index, ShardEntry entry);

}
