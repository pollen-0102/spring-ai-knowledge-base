package com.pollen.rag.document;

public record ChunkView(long id,
                        long docId,
                        long kbId,
                        int chunkIndex,
                        String content,
                        Integer pageNo,
                        String vectorId,
                        String docName,
                        String aclScope) {
}
