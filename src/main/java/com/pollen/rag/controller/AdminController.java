package com.pollen.rag.controller;

import com.pollen.rag.common.ApiResponse;
import com.pollen.rag.config.RagProperties;
import com.pollen.rag.config.VectorStoreManager;
import com.pollen.rag.document.ChunkRepository;
import com.pollen.rag.document.ChunkView;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final int BATCH = 32;

    private final VectorStoreManager vectors;
    private final ChunkRepository chunks;
    private final RagProperties props;

    public AdminController(VectorStoreManager vectors, ChunkRepository chunks, RagProperties props) {
        this.vectors = vectors;
        this.chunks = chunks;
        this.props = props;
    }

    @GetMapping("/milvus/health")
    public ApiResponse<Map<String, Object>> health() {
        long pgChunks = chunks.countAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", props.getVectorStore().getType());
        result.put("vectorCount", vectors.count());
        result.put("pgChunkCount", pgChunks);
        result.put("consistent", vectors.count() == pgChunks);
        result.put("loadState", "in-memory");
        return ApiResponse.ok(result);
    }

    /**
     * Rebuilds vector entries that exist in PostgreSQL but are missing from the
     * derived index. In the Milvus phase this endpoint becomes a real
     * PG-chunk <-> Milvus-entity reconciliation.
     */
    @PostMapping("/milvus/reconcile")
    public ApiResponse<Map<String, Object>> reconcile() {
        Set<String> ids = vectors.ids();
        List<ChunkView> all = chunks.findAllViews();
        List<ChunkView> missing = all.stream().filter(c -> !ids.contains(c.vectorId())).toList();

        for (int i = 0; i < missing.size(); i += BATCH) {
            List<ChunkView> batch = missing.subList(i, Math.min(i + BATCH, missing.size()));
            List<Document> documents = new ArrayList<>(batch.size());
            for (ChunkView view : batch) {
                documents.add(toDocument(view));
            }
            vectors.add(documents);
        }
        if (!missing.isEmpty()) {
            vectors.save();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", props.getVectorStore().getType());
        result.put("pgChunkCount", all.size());
        result.put("vectorCountBefore", ids.size());
        result.put("missing", missing.size());
        result.put("repaired", missing.size());
        result.put("vectorCountAfter", vectors.count());
        result.put("consistent", vectors.count() == all.size());
        return ApiResponse.ok(result);
    }

    private Document toDocument(ChunkView view) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkId", view.id());
        metadata.put("docId", view.docId());
        metadata.put("kbId", (int) view.kbId());
        metadata.put("chunkIndex", view.chunkIndex());
        if (view.pageNo() != null) {
            metadata.put("pageNo", view.pageNo());
        }
        metadata.put("docName", view.docName());
        metadata.put("aclScope", view.aclScope());
        return new Document(view.vectorId(), view.content(), metadata);
    }
}
