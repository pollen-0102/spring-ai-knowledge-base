package com.pollen.rag.config;

import com.pollen.rag.document.ChunkRepository;
import com.pollen.rag.document.ChunkView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL is the source of truth. If the local vector file is missing
 * (fresh clone, deleted index, ...) rebuild the derived index from chunks.
 */
@Component
public class VectorRebuildRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorRebuildRunner.class);
    private static final int BATCH = 32;

    private final VectorStoreManager vectors;
    private final ChunkRepository chunks;
    private final ThreadPoolTaskExecutor executor;

    public VectorRebuildRunner(VectorStoreManager vectors, ChunkRepository chunks,
                               @Qualifier("ingestExecutor") ThreadPoolTaskExecutor executor) {
        this.vectors = vectors;
        this.chunks = chunks;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void schedule() {
        executor.execute(this::rebuildIfNeeded);
    }

    private void rebuildIfNeeded() {
        if (vectors.count() > 0) {
            return;
        }
        try {
            List<ChunkView> views = chunks.findAllViews();
            if (views.isEmpty()) {
                return;
            }
            log.info("Vector store is empty, rebuilding {} chunks from PostgreSQL", views.size());
            for (int i = 0; i < views.size(); i += BATCH) {
                List<ChunkView> batch = views.subList(i, Math.min(i + BATCH, views.size()));
                List<Document> documents = new ArrayList<>(batch.size());
                for (ChunkView view : batch) {
                    documents.add(toDocument(view));
                }
                vectors.add(documents);
            }
            vectors.save();
            log.info("Vector rebuild finished: {} vectors", vectors.count());
        } catch (Exception e) {
            log.error("Vector rebuild failed", e);
        }
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
