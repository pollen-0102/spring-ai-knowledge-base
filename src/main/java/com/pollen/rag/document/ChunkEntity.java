package com.pollen.rag.document;

import java.time.OffsetDateTime;

public record ChunkEntity(long id,
                          long docId,
                          long kbId,
                          int chunkIndex,
                          String content,
                          String contentHash,
                          Integer tokenEstimate,
                          Integer pageNo,
                          String section,
                          String vectorId,
                          String vectorStatus,
                          OffsetDateTime createdAt) {
}
