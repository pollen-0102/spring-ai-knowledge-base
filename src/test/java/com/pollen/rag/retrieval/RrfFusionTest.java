package com.pollen.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    @Test
    void hitPresentInBothListsRanksFirst() {
        List<Hit> dense = List.of(
                hit(1, 10, 0, "dense", 0.9, 0),
                hit(2, 20, 0, "dense", 0.8, 0));
        List<Hit> sparse = List.of(
                hit(2, 20, 0, "sparse", 0, 5.0),
                hit(3, 30, 0, "sparse", 0, 3.0));

        List<Hit> fused = RrfFusion.fuse(dense, sparse, 60, 5);

        assertThat(fused.get(0).chunkId()).isEqualTo(2);
        assertThat(fused.get(0).source()).isEqualTo("both");
        assertThat(fused).extracting(Hit::chunkId).contains(1L, 2L, 3L);
    }

    @Test
    void dropsAdjacentChunksFromSameDocument() {
        List<Hit> dense = List.of(
                hit(1, 10, 0, "dense", 0.9, 0),
                hit(2, 10, 1, "dense", 0.8, 0),
                hit(3, 20, 0, "dense", 0.7, 0));

        List<Hit> fused = RrfFusion.fuse(dense, List.of(), 60, 5);

        assertThat(fused).extracting(Hit::chunkId).containsExactly(1L, 3L);
    }

    private Hit hit(long chunkId, long docId, int chunkIndex, String source, double dense, double sparse) {
        return new Hit(chunkId, docId, 1L, chunkIndex, null, "doc.md",
                "content-" + chunkId, dense, sparse, 0, source);
    }
}
