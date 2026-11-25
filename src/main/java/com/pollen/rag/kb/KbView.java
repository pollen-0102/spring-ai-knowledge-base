package com.pollen.rag.kb;

import java.time.OffsetDateTime;

public record KbView(long id,
                     String name,
                     String description,
                     String embeddingModel,
                     int embeddingDim,
                     int chunkSize,
                     int chunkOverlap,
                     int topK,
                     double scoreThreshold,
                     long documentCount,
                     long chunkCount,
                     OffsetDateTime createdAt) {
}
