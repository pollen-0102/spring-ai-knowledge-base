package com.pollen.rag.document;

import java.time.OffsetDateTime;

public record DocumentEntity(long id,
                             long kbId,
                             String fileName,
                             String fileType,
                             String storagePath,
                             long fileSize,
                             String fileHash,
                             String status,
                             int chunkCount,
                             int indexedCount,
                             int charCount,
                             String errorMsg,
                             int retryCount,
                             String aclScope,
                             OffsetDateTime createdAt,
                             OffsetDateTime updatedAt) {
}
