package com.pollen.rag.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thin wrapper around SimpleVectorStore:
 * - keeps the set of indexed vector ids (for health / reconcile)
 * - serialises save() so concurrent ingest jobs cannot corrupt the file
 */
@Component
public class VectorStoreManager {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreManager.class);

    private final SimpleVectorStore store;
    private final RagProperties props;
    private final ObjectMapper objectMapper;
    private final Set<String> indexedIds = ConcurrentHashMap.newKeySet();
    private final ReentrantLock saveLock = new ReentrantLock();

    public VectorStoreManager(SimpleVectorStore store, RagProperties props, ObjectMapper objectMapper) {
        this.store = store;
        this.props = props;
        this.objectMapper = objectMapper;
        loadIds();
    }

    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        store.add(documents);
        for (Document document : documents) {
            indexedIds.add(document.getId());
        }
    }

    public void delete(List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return;
        }
        store.delete(vectorIds);
        indexedIds.removeAll(vectorIds);
    }

    public List<Document> search(SearchRequest request) {
        return store.similaritySearch(request);
    }

    public void save() {
        saveLock.lock();
        try {
            File file = new File(props.getStorage().getVectorFile());
            if (file.getParentFile() != null) {
                Files.createDirectories(file.getParentFile().toPath());
            }
            store.save(file);
            log.info("Saved {} vectors to {}", indexedIds.size(), file.getAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save vector store: " + e.getMessage(), e);
        } finally {
            saveLock.unlock();
        }
    }

    public int count() {
        return indexedIds.size();
    }

    public Set<String> ids() {
        return Set.copyOf(indexedIds);
    }

    private void loadIds() {
        File file = new File(props.getStorage().getVectorFile());
        if (!file.exists() || file.length() == 0) {
            return;
        }
        try {
            Map<String, Object> content = objectMapper.readValue(file, new TypeReference<>() {
            });
            indexedIds.addAll(content.keySet());
            log.info("VectorStoreManager loaded {} vector ids from {}", indexedIds.size(), file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to read vector id index from {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }
}
