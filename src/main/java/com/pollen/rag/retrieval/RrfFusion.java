package com.pollen.rag.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * Reciprocal Rank Fusion: score(d) = sum(1 / (k + rank + 1)).
     * Adjacent chunks from the same document are deduplicated (keep the higher ranked one).
     */
    public static List<Hit> fuse(List<Hit> dense, List<Hit> sparse, int k, int topN) {
        List<Hit> denseHits = dense == null ? List.of() : dense;
        List<Hit> sparseHits = sparse == null ? List.of() : sparse;
        Set<Long> denseIds = new HashSet<>();
        Set<Long> sparseIds = new HashSet<>();
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, Hit> cache = new HashMap<>();

        accumulate(denseHits, k, scores, cache, denseIds);
        accumulate(sparseHits, k, scores, cache, sparseIds);

        List<Hit> merged = scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(entry -> {
                    Hit hit = cache.get(entry.getKey());
                    boolean inDense = denseIds.contains(entry.getKey());
                    boolean inSparse = sparseIds.contains(entry.getKey());
                    String source = inDense && inSparse ? "both" : (inDense ? "dense" : "sparse");
                    return hit.withFused(entry.getValue(), source);
                })
                .toList();

        int limit = Math.max(1, topN);
        List<Hit> result = new ArrayList<>();
        for (Hit candidate : merged) {
            boolean adjacent = result.stream().anyMatch(kept ->
                    kept.docId() == candidate.docId()
                            && Math.abs(kept.chunkIndex() - candidate.chunkIndex()) <= 1);
            if (!adjacent) {
                result.add(candidate);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private static void accumulate(List<Hit> hits, int k, Map<Long, Double> scores,
                                   Map<Long, Hit> cache, Set<Long> ids) {
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            long id = hit.chunkId();
            ids.add(id);
            scores.merge(id, 1.0 / (k + i + 1), Double::sum);
            cache.merge(id, hit, Hit::merge);
        }
    }
}
