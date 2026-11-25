package com.pollen.rag.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight CJK tokenizer for the BM25 path:
 * - Latin/digit words are kept as-is
 * - CJK runs emit unigrams + bigrams (+ the whole short run)
 * - common single-character stop words are dropped
 *
 * This avoids a Lucene dependency for the first cut. Swapping in Lucene/
 * Milvus BM25 later only touches Bm25Searcher.
 */
public final class ChineseTokenizer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9_]+|[\\u4e00-\\u9fa5]+");

    private static final Set<Character> STOP_CHARS = Set.of(
            '的', '了', '是', '在', '和', '与', '及', '或', '有', '无', '不', '需', '要',
            '这', '那', '个', '我', '你', '他', '她', '它', '们', '就', '都', '而', '被',
            '把', '对', '从', '到', '为', '以', '于', '中', '上', '下', '还', '也', '很'
    );

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "or", "of", "to", "is", "are", "a", "an", "in", "on", "for", "with"
    );

    private ChineseTokenizer() {
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase());
        while (matcher.find()) {
            String token = matcher.group();
            char first = token.charAt(0);
            if (first < 128) {
                if (token.length() > 1 || !STOP_WORDS.contains(token)) {
                    if (token.length() > 1 || !Character.isDigit(first)) {
                        tokens.add(token);
                    }
                }
                continue;
            }
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (!STOP_CHARS.contains(c)) {
                    tokens.add(String.valueOf(c));
                }
            }
            for (int i = 0; i + 1 < token.length(); i++) {
                tokens.add(token.substring(i, i + 2));
            }
            if (token.length() <= 8) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    public static Map<String, Integer> termFrequency(List<String> tokens) {
        Map<String, Integer> tf = new HashMap<>();
        for (String token : tokens) {
            tf.merge(token, 1, Integer::sum);
        }
        return tf;
    }
}
