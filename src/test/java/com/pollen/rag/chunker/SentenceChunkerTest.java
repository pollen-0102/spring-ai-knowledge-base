package com.pollen.rag.chunker;

import com.pollen.rag.document.chunker.ChunkDraft;
import com.pollen.rag.document.chunker.SentenceChunker;
import com.pollen.rag.document.parser.ParsedDocument;
import com.pollen.rag.document.parser.ParsedPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceChunkerTest {

    private final SentenceChunker chunker = new SentenceChunker();

    @Test
    void splitsBySentenceAndKeepsPageNumber() {
        // Long enough to exceed the chunker's 100-char minimum target.
        String text = "第一句话。第二句话。第三句话。第四句话。第五句话。".repeat(10);
        List<ChunkDraft> chunks = chunker.split(
                new ParsedDocument(List.of(new ParsedPage(3, text)), text.length()), 100, 1, 200);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allMatch(chunk -> chunk.pageNo() == 3);
        assertThat(chunks.stream().map(ChunkDraft::chunkIndex).distinct().count()).isEqualTo(chunks.size());
    }

    @Test
    void guardsChunkLength() {
        String longSentence = "这是一段没有句号的很长的文字".repeat(30) + "。";
        List<ChunkDraft> chunks = chunker.split(
                new ParsedDocument(List.of(new ParsedPage(null, longSentence)), longSentence.length()), 100, 0, 120);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allMatch(chunk -> chunk.content().length() <= 120);
    }

    @Test
    void emptyDocumentProducesNoChunks() {
        List<ChunkDraft> chunks = chunker.split(
                new ParsedDocument(List.of(new ParsedPage(null, "   ")), 0), 600, 2, 2000);
        assertThat(chunks).isEmpty();
    }
}
