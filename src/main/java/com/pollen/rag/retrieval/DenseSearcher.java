package com.pollen.rag.retrieval;

import com.pollen.rag.config.VectorStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DenseSearcher {

    private static final Logger log = LoggerFactory.getLogger(DenseSearcher.class);

    private final VectorStoreManager vectors;

    public DenseSearcher(VectorStoreManager vectors) {
        this.vectors = vectors;
    }

    public List<Hit> search(long kbId, String query, int topK) {
        int limit = Math.max(topK, 1);
        List<Document> documents;
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(limit * 4)
                    .similarityThreshold(0.0)
                    .filterExpression("kbId == " + kbId)
                    .build();
            documents = vectors.search(request);
        } catch (Exception e) {
            log.warn("Dense search with filter failed, retry with application-side filter: {}", e.getMessage());
            SearchRequest fallback = SearchRequest.builder()
                    .query(query)
                    .topK(Math.max(limit * 8, 50))
                    .similarityThreshold(0.0)
                    .build();
            documents = vectors.search(fallback).stream()
                    .filter(d -> longValue(d.getMetadata().get("kbId"), -1) == kbId)
                    .limit(limit)
                    .toList();
        }

        List<Hit> hits = new ArrayList<>();
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            if (longValue(metadata.get("kbId"), -1) != kbId) {
                continue;
            }
            hits.add(new Hit(
                    longValue(metadata.get("chunkId"), -1),
                    longValue(metadata.get("docId"), -1),
                    kbId,
                    intValue(metadata.get("chunkIndex"), 0),
                    intValueOrNull(metadata.get("pageNo")),
                    stringValue(metadata.get("docName")),
                    document.getText(),
                    Math.max(0, document.getScore()),
                    0,
                    0,
                    "dense"
            ));
            if (hits.size() >= limit) {
                break;
            }
        }
        return hits;
    }

    private long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private Integer intValueOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
