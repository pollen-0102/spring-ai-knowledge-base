package com.pollen.rag.kb;

import java.time.OffsetDateTime;

public record Kb(long id,
                 String name,
                 String description,
                 String embeddingModel,
                 int embeddingDim,
                 String milvusCollection,
                 int chunkSize,
                 int chunkOverlap,
                 int topK,
                 double scoreThreshold,
                 boolean deleted,
                 OffsetDateTime createdAt,
                 OffsetDateTime updatedAt) {
}
