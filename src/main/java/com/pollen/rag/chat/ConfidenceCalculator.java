package com.pollen.rag.chat;

import com.pollen.rag.config.RagProperties;
import com.pollen.rag.retrieval.ChineseTokenizer;
import com.pollen.rag.retrieval.Hit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConfidenceCalculator {

    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d+)]");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[。！？!?;\\n]");

    private final RagProperties props;

    public ConfidenceCalculator(RagProperties props) {
        this.props = props;
    }

    /**
     * confidence = 0.5 * topFusedScoreNorm + 0.3 * citationCoverage + 0.2 * agreement
     * All terms are explainable and computed server-side; the LLM is never asked
     * to self-report confidence.
     */
    public double calculate(List<Hit> hits, List<Citation> citations, String answer) {
        if (hits == null || hits.isEmpty()) {
            return 0;
        }
        double maxFused = 2.0 / (props.getRetrieval().getRrfK() + 1);
        double topNorm = maxFused <= 0 ? 0 : Math.min(1, hits.get(0).fusedScore() / maxFused);
        double coverage = coverage(answer);
        double agreement = agreement(citations);
        double confidence = 0.5 * topNorm + 0.3 * coverage + 0.2 * agreement;
        return Math.round(Math.min(1, Math.max(0, confidence)) * 100.0) / 100.0;
    }

    /**
     * Citation coverage: cited sentences / total answer sentences, computed from
     * the model's [n] markers. Server-side and explainable; never asks the LLM
     * to self-report confidence.
     */
    private double coverage(String answer) {
        if (answer == null || answer.isBlank()) {
            return 0;
        }
        int cited = 0;
        Matcher matcher = CITATION_MARKER.matcher(answer);
        while (matcher.find()) {
            cited++;
        }
        int sentences = 0;
        for (String sentence : SENTENCE_BOUNDARY.split(answer)) {
            if (!sentence.isBlank()) {
                sentences++;
            }
        }
        return sentences == 0 ? 0 : Math.min(1, cited / (double) sentences);
    }

    private double agreement(List<Citation> citations) {
        if (citations.size() < 2) {
            return 0.5;
        }
        List<Set<String>> tokenSets = new ArrayList<>();
        for (Citation citation : citations) {
            tokenSets.add(new HashSet<>(ChineseTokenizer.tokenize(citation.snippet())));
        }
        double sum = 0;
        int pairs = 0;
        for (int i = 0; i < tokenSets.size(); i++) {
            for (int j = i + 1; j < tokenSets.size(); j++) {
                Set<String> a = tokenSets.get(i);
                Set<String> b = tokenSets.get(j);
                if (a.isEmpty() || b.isEmpty()) {
                    continue;
                }
                Set<String> intersection = new HashSet<>(a);
                intersection.retainAll(b);
                Set<String> union = new HashSet<>(a);
                union.addAll(b);
                sum += (double) intersection.size() / union.size();
                pairs++;
            }
        }
        return pairs == 0 ? 0.5 : sum / pairs;
    }
}
