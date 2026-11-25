package com.pollen.rag.retrieval;

import com.pollen.rag.document.ChunkRepository;
import com.pollen.rag.document.ChunkView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory BM25 keyword path over the PostgreSQL chunk table.
 * The index is cached per kb and invalidated after ingestion / deletion.
 */
@Component
public class Bm25Searcher {

    private static final Logger log = LoggerFactory.getLogger(Bm25Searcher.class);
    private static final long INDEX_TTL_MS = 60_000L;
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final ChunkRepository chunks;
    private final Map<Long, Index> cache = new ConcurrentHashMap<>();

    public Bm25Searcher(ChunkRepository chunks) {
        this.chunks = chunks;
    }

    public void invalidate(long kbId) {
        cache.remove(kbId);
    }

    public List<Hit> search(long kbId, String query, int topK) {
        Index index = cache.compute(kbId, (key, existing) -> {
            if (existing == null || System.currentTimeMillis() - existing.builtAt() > INDEX_TTL_MS) {
                return build(key);
            }
            return existing;
        });
        if (index.docs().isEmpty()) {
            return List.of();
        }

        Map<String, Integer> queryTf = ChineseTokenizer.termFrequency(ChineseTokenizer.tokenize(query));
        if (queryTf.isEmpty()) {
            return List.of();
        }

        int n = index.docs().size();
        List<Scored> scored = new ArrayList<>();
        for (DocTerms doc : index.docs()) {
            double score = 0;
            for (Map.Entry<String, Integer> entry : queryTf.entrySet()) {
                String term = entry.getKey();
                Integer df = index.df().get(term);
                if (df == null) {
                    continue;
                }
                int tf = doc.tf().getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                double denom = tf + K1 * (1 - B + B * doc.length() / Math.max(1.0, index.avgdl()));
                score += idf * (tf * (K1 + 1) / denom) * entry.getValue();
            }
            if (score > 0) {
                scored.add(new Scored(doc.view(), score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < topK; i++) {
            ChunkView v = scored.get(i).view();
            hits.add(new Hit(v.id(), v.docId(), v.kbId(), v.chunkIndex(), v.pageNo(),
                    v.docName(), v.content(), 0, scored.get(i).score(), 0, "sparse"));
        }
        return hits;
    }

    public int indexedChunks(long kbId) {
        Index index = cache.get(kbId);
        return index == null ? 0 : index.docs().size();
    }

    private Index build(long kbId) {
        long start = System.currentTimeMillis();
        List<ChunkView> views = chunks.findViewsByKb(kbId);
        List<DocTerms> docs = new ArrayList<>(views.size());
        Map<String, Integer> df = new HashMap<>();
        long totalLength = 0;
        for (ChunkView view : views) {
            List<String> tokens = ChineseTokenizer.tokenize(view.content());
            Map<String, Integer> tf = ChineseTokenizer.termFrequency(tokens);
            docs.add(new DocTerms(view, tf, tokens.size()));
            totalLength += tokens.size();
            for (String term : tf.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }
        double avgdl = docs.isEmpty() ? 0 : (double) totalLength / docs.size();
        log.info("Built BM25 index kb={} chunks={} terms={} took={}ms",
                kbId, docs.size(), df.size(), System.currentTimeMillis() - start);
        return new Index(List.copyOf(docs), Map.copyOf(df), avgdl, System.currentTimeMillis());
    }

    private record Index(List<DocTerms> docs, Map<String, Integer> df, double avgdl, long builtAt) {
    }

    private record DocTerms(ChunkView view, Map<String, Integer> tf, int length) {
    }

    private record Scored(ChunkView view, double score) {
    }
}
