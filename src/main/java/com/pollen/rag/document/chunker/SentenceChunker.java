package com.pollen.rag.document.chunker;

import com.pollen.rag.document.parser.ParsedDocument;
import com.pollen.rag.document.parser.ParsedPage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Chinese-friendly sentence chunker.
 * Sentences are accumulated up to chunkSize chars, then the window backs off
 * by `overlapSentences` sentences. Every chunk is guarded to maxChars so it
 * can later be written into a Milvus VARCHAR field without surprises.
 */
@Component
public class SentenceChunker {

    public List<ChunkDraft> split(ParsedDocument document, int chunkSize, int overlapSentences, int maxChars) {
        int target = Math.max(100, Math.min(chunkSize, maxChars));
        int overlap = Math.max(0, Math.min(overlapSentences, 10));
        List<ChunkDraft> result = new ArrayList<>();
        int chunkIndex = 0;
        for (ParsedPage page : document.pages()) {
            List<String> sentences = splitSentences(page.text(), maxChars);
            int start = 0;
            while (start < sentences.size()) {
                StringBuilder sb = new StringBuilder();
                int end = start;
                while (end < sentences.size() && sb.length() < target) {
                    sb.append(sentences.get(end));
                    end++;
                }
                String content = sb.toString().trim();
                if (!content.isEmpty()) {
                    for (String piece : guardLength(content, maxChars)) {
                        result.add(new ChunkDraft(piece, page.pageNo(), chunkIndex++));
                    }
                }
                if (end >= sentences.size()) {
                    break;
                }
                int next = Math.max(end - overlap, start + 1);
                if (next <= start) {
                    break;
                }
                start = next;
            }
        }
        return result;
    }

    private List<String> splitSentences(String text, int maxChars) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String[] raw = text.split("(?<=[。！？；!?;\\n])");
        for (String sentence : raw) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            result.addAll(guardLength(trimmed, maxChars));
        }
        return result;
    }

    private List<String> guardLength(String text, int maxChars) {
        List<String> pieces = new ArrayList<>();
        if (text.length() <= maxChars) {
            pieces.add(text);
            return pieces;
        }
        for (int i = 0; i < text.length(); i += maxChars) {
            pieces.add(text.substring(i, Math.min(text.length(), i + maxChars)).trim());
        }
        return pieces;
    }
}
