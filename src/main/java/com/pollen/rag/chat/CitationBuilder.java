package com.pollen.rag.chat;

import com.pollen.rag.retrieval.Hit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CitationBuilder {

    public List<Citation> build(List<Hit> hits) {
        List<Citation> citations = new ArrayList<>();
        int index = 1;
        for (Hit hit : hits) {
            String snippet = hit.content() == null ? "" : hit.content().trim();
            if (snippet.length() > 300) {
                snippet = snippet.substring(0, 300) + "...";
            }
            // Prefer the interpretable cosine score; fall back to RRF for sparse-only hits.
            double score = hit.denseScore() > 0 ? hit.denseScore() : hit.fusedScore();
            citations.add(new Citation(index++, hit.docId(), hit.chunkId(), hit.docName(),
                    hit.pageNo(), snippet, score, hit.source()));
        }
        return citations;
    }

    public String context(List<Hit> hits) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Hit hit : hits) {
            sb.append("[").append(index++).append("] 来源：").append(hit.docName());
            if (hit.pageNo() != null) {
                sb.append("（第").append(hit.pageNo()).append("页）");
            }
            sb.append("\n");
            String content = hit.content() == null ? "" : hit.content();
            if (content.length() > 1500) {
                content = content.substring(0, 1500);
            }
            sb.append(content).append("\n\n");
        }
        return sb.toString().trim();
    }
}
