package com.pollen.rag.retrieval;

import java.util.List;

public record RetrievalResult(List<Hit> hits, int denseHits, int sparseHits, int fusedHits) {
}
