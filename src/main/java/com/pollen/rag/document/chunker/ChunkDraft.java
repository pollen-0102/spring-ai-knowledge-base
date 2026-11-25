package com.pollen.rag.document.chunker;

public record ChunkDraft(String content, Integer pageNo, int chunkIndex) {
}
