package com.pollen.rag.chat;

public record Citation(int index,
                       long docId,
                       long chunkId,
                       String docName,
                       Integer page,
                       String snippet,
                       double score,
                       String source) {
}
