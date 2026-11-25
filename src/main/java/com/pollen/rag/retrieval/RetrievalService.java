package com.pollen.rag.retrieval;

import com.pollen.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final DenseSearcher denseSearcher;
    private final Bm25Searcher bm25Searcher;
    private final RagProperties props;

    public RetrievalService(DenseSearcher denseSearcher, Bm25Searcher bm25Searcher, RagProperties props) {
        this.denseSearcher = denseSearcher;
        this.bm25Searcher = bm25Searcher;
        this.props = props;
    }

    public RetrievalResult retrieve(long kbId, String question, int topK) {
        long start = System.currentTimeMillis();
        List<Hit> dense = safeDense(kbId, question);
        List<Hit> sparse = safeSparse(kbId, question);
        int limit = topK > 0 ? topK : props.getRetrieval().getFuseTopN();
        List<Hit> fused = RrfFusion.fuse(dense, sparse, props.getRetrieval().getRrfK(), limit);
        log.info("retrieve kb={} dense={} sparse={} fused={} took={}ms",
                kbId, dense.size(), sparse.size(), fused.size(), System.currentTimeMillis() - start);
        return new RetrievalResult(fused, dense.size(), sparse.size(), fused.size());
    }

    private List<Hit> safeDense(long kbId, String question) {
        try {
            return denseSearcher.search(kbId, question, props.getRetrieval().getDenseTopK());
        } catch (Exception e) {
            log.warn("dense retrieval failed for kb={}: {}", kbId, e.getMessage());
            return List.of();
        }
    }

    private List<Hit> safeSparse(long kbId, String question) {
        try {
            return bm25Searcher.search(kbId, question, props.getRetrieval().getSparseTopK());
        } catch (Exception e) {
            log.warn("sparse retrieval failed for kb={}: {}", kbId, e.getMessage());
            return List.of();
        }
    }
}
