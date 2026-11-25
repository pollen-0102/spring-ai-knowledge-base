package com.pollen.rag.chat;

import com.pollen.rag.common.BizException;
import com.pollen.rag.common.ErrorCode;
import com.pollen.rag.config.RagProperties;
import com.pollen.rag.kb.Kb;
import com.pollen.rag.kb.KbRepository;
import com.pollen.rag.retrieval.ChineseTokenizer;
import com.pollen.rag.retrieval.Hit;
import com.pollen.rag.retrieval.RetrievalResult;
import com.pollen.rag.retrieval.RetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final KbRepository kbs;
    private final ChatRepository chats;
    private final RetrievalService retrievalService;
    private final PromptService promptService;
    private final CitationBuilder citationBuilder;
    private final ConfidenceCalculator confidenceCalculator;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RagProperties props;
    private final String modelName;

    public ChatService(KbRepository kbs, ChatRepository chats, RetrievalService retrievalService,
                       PromptService promptService, CitationBuilder citationBuilder,
                       ConfidenceCalculator confidenceCalculator, ChatClient chatClient,
                       ObjectMapper objectMapper, RagProperties props,
                       @Value("${spring.ai.deepseek.chat.options.model:deepseek-chat}") String modelName) {
        this.kbs = kbs;
        this.chats = chats;
        this.retrievalService = retrievalService;
        this.promptService = promptService;
        this.citationBuilder = citationBuilder;
        this.confidenceCalculator = confidenceCalculator;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.props = props;
        this.modelName = modelName;
    }

    public SseEmitter chat(long kbId, ChatDtos.ChatRequest request, String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long started = System.currentTimeMillis();
        try {
            Kb kb = kbs.findById(kbId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + kbId));
            String question = request.question() == null ? "" : request.question().trim();
            if (question.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "问题不能为空");
            }

            long sessionId = request.sessionId() != null
                    ? requireSession(request.sessionId(), kbId)
                    : chats.createSession(kbId, userId, title(question));
            String history = loadHistory(sessionId);
            chats.insertMessage(sessionId, "USER", question, null, false);

            long retrievalStart = System.currentTimeMillis();
            RetrievalResult result = retrievalService.retrieve(kbId, question,
                    request.topK() != null ? request.topK() : kb.topK());
            long retrievalMs = System.currentTimeMillis() - retrievalStart;

            double threshold = request.scoreThreshold() != null ? request.scoreThreshold() : kb.scoreThreshold();
            List<Hit> used = pickUsed(result.hits(), threshold, question);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("requestId", requestId);
            meta.put("sessionId", sessionId);
            meta.put("rewrittenQuery", question);
            meta.put("denseHits", result.denseHits());
            meta.put("sparseHits", result.sparseHits());
            meta.put("fusedHits", result.fusedHits());
            meta.put("vectorStore", props.getVectorStore().getType());
            send(emitter, "meta", meta);

            if (used.isEmpty()) {
                String refusal = "在【" + kb.name() + "】中未找到与该问题相关的资料。"
                        + "建议补充相关文档，或换用更具体的关键词重试。";
                send(emitter, "delta", Map.of("text", refusal));
                long messageId = saveAssistant(sessionId, refusal, List.of(), 0, true);
                insertLog(requestId, sessionId, kbId, question, result, used, 0, 0, "[]",
                        retrievalMs, 0, System.currentTimeMillis() - started, true);
                send(emitter, "done", donePayload(messageId, sessionId, 0, true, 0, 0));
                safeComplete(emitter);
                return emitter;
            }

            List<Citation> citations = citationBuilder.build(used);
            String context = citationBuilder.context(used);
            PromptTemplate template = promptService.get("QA_ANSWER", kbId);
            String system = promptService.renderSystem(template, context, history, kb.name(), question);
            String userPrompt = promptService.renderUser(template, question);

            StringBuilder answer = new StringBuilder();
            long llmStart = System.currentTimeMillis();
            AtomicReference<Disposable> disposableRef = new AtomicReference<>();

            Flux<String> stream = chatClient.prompt()
                    .system(system)
                    .user(userPrompt)
                    .stream()
                    .content();

            Disposable disposable = stream
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            delta -> {
                                if (delta == null || delta.isEmpty()) {
                                    return;
                                }
                                answer.append(delta);
                                send(emitter, "delta", Map.of("text", delta));
                            },
                            error -> {
                                log.warn("LLM stream failed request={}: {}", requestId, error.getMessage());
                                sendError(emitter, ErrorCode.MODEL_UNAVAILABLE,
                                        "模型调用失败: " + error.getMessage());
                                safeComplete(emitter);
                            },
                            () -> finish(emitter, requestId, sessionId, kbId, question, result, used,
                                    citations, answer.toString(), system, userPrompt,
                                    llmStart, retrievalMs, started)
                    );
            disposableRef.set(disposable);
            emitter.onCompletion(() -> dispose(disposableRef));
            emitter.onTimeout(() -> {
                dispose(disposableRef);
                safeComplete(emitter);
            });
            emitter.onError(e -> dispose(disposableRef));
            return emitter;
        } catch (Exception e) {
            log.warn("chat setup failed request={}: {}", requestId, e.getMessage());
            int code = e instanceof BizException biz ? biz.getCode() : ErrorCode.INTERNAL;
            sendError(emitter, code, e.getMessage());
            safeComplete(emitter);
            return emitter;
        }
    }

    public List<ChatDtos.MessageView> messages(long sessionId) {
        chats.findSession(sessionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        return chats.messages(sessionId);
    }

    private void finish(SseEmitter emitter, String requestId, long sessionId, long kbId,
                        String question, RetrievalResult result, List<Hit> used,
                        List<Citation> citations, String answer, String system, String userPrompt,
                        long llmStart, long retrievalMs, long started) {
        try {
            long llmMs = System.currentTimeMillis() - llmStart;
            double confidence = confidenceCalculator.calculate(used, citations, answer);
            long messageId = saveAssistant(sessionId, answer, citations, confidence, false);
            for (Citation citation : citations) {
                send(emitter, "citation", citation);
            }
            int promptTokens = estimateTokens(system) + estimateTokens(userPrompt);
            int completionTokens = estimateTokens(answer);
            insertLog(requestId, sessionId, kbId, question, result, used,
                    promptTokens, completionTokens, toJson(used),
                    retrievalMs, llmMs, System.currentTimeMillis() - started, false);
            send(emitter, "done", donePayload(messageId, sessionId, confidence, false,
                    promptTokens, completionTokens));
            safeComplete(emitter);
        } catch (Exception e) {
            log.warn("finish chat failed request={}: {}", requestId, e.getMessage());
            sendError(emitter, ErrorCode.INTERNAL, "保存回答失败: " + e.getMessage());
            safeComplete(emitter);
        }
    }

    private List<Hit> pickUsed(List<Hit> hits, double threshold, String question) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        // A strong dense match is enough.
        if (hits.get(0).denseScore() >= threshold) {
            return hits;
        }
        // Dense is weak: only keep sparse evidence if it matches a meaningful
        // query term (bigram / English word), not just a single common character.
        List<String> strongTerms = ChineseTokenizer.tokenize(question).stream()
                .filter(term -> term.length() >= 2)
                .distinct()
                .toList();
        if (strongTerms.isEmpty()) {
            // Very short query (e.g. a single Chinese character): any sparse hit counts.
            return hits.stream().anyMatch(hit -> hit.sparseScore() > 0) ? hits : List.of();
        }
        boolean sparseStrong = hits.stream()
                .filter(hit -> hit.sparseScore() > 0)
                .anyMatch(hit -> hit.content() != null
                        && strongTerms.stream().anyMatch(term -> hit.content().contains(term)));
        return sparseStrong ? hits : List.of();
    }

    private String loadHistory(long sessionId) {
        int rounds = Math.max(1, props.getPrompt().getHistoryRounds());
        List<ChatDtos.MessageView> messages = chats.recentMessages(sessionId, rounds * 2);
        StringBuilder sb = new StringBuilder();
        for (ChatDtos.MessageView message : messages) {
            if (!"USER".equals(message.role()) && !"ASSISTANT".equals(message.role())) {
                continue;
            }
            String role = "USER".equals(message.role()) ? "用户" : "助手";
            String content = message.content() == null ? "" : message.content();
            if (content.length() > 400) {
                content = content.substring(0, 400) + "...";
            }
            sb.append(role).append("：").append(content).append("\n");
        }
        String history = sb.toString().trim();
        if (history.length() > 2000) {
            history = history.substring(history.length() - 2000);
        }
        return history.isBlank() ? "（无）" : history;
    }

    private long requireSession(long sessionId, long kbId) {
        ChatDtos.SessionView session = chats.findSession(sessionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        if (session.kbId() != kbId) {
            throw new BizException(ErrorCode.BAD_REQUEST, "会话不属于该知识库");
        }
        return session.id();
    }

    private long saveAssistant(long sessionId, String content, List<Citation> citations,
                               double confidence, boolean refused) {
        try {
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("citations", citations);
            structured.put("confidence", confidence);
            structured.put("refused", refused);
            long messageId = chats.insertMessage(sessionId, "ASSISTANT", content,
                    objectMapper.writeValueAsString(structured), refused);
            if (!citations.isEmpty()) {
                chats.insertCitations(messageId, citations);
            }
            return messageId;
        } catch (Exception e) {
            throw new IllegalStateException("保存助手消息失败", e);
        }
    }

    private void insertLog(String requestId, long sessionId, long kbId, String question,
                           RetrievalResult result, List<Hit> used, int promptTokens,
                           int completionTokens, String usedChunksJson, long retrievalMs,
                           long llmMs, long latencyMs, boolean refused) {
        try {
            Double topScore = used.isEmpty() ? null : used.get(0).denseScore();
            chats.insertLog(new ChatRepository.RetrievalLog(
                    requestId, sessionId, kbId, question, question,
                    result.denseHits(), result.sparseHits(), result.fusedHits(), topScore,
                    usedChunksJson, promptTokens, completionTokens, promptTokens + completionTokens,
                    modelName, (int) retrievalMs, (int) llmMs, (int) latencyMs, refused));
        } catch (Exception e) {
            log.warn("insert retrieval log failed: {}", e.getMessage());
        }
    }

    private String toJson(List<Hit> used) {
        try {
            List<Map<String, Object>> rows = used.stream().map(hit -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("chunkId", hit.chunkId());
                row.put("score", hit.fusedScore());
                row.put("denseScore", hit.denseScore());
                row.put("sparseScore", hit.sparseScore());
                row.put("source", hit.source());
                return row;
            }).toList();
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Map<String, Object> donePayload(long messageId, long sessionId, double confidence,
                                            boolean refused, int promptTokens, int completionTokens) {
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("messageId", messageId);
        done.put("sessionId", sessionId);
        done.put("confidence", confidence);
        done.put("refused", refused);
        done.put("usage", Map.of(
                "promptTokens", promptTokens,
                "completionTokens", completionTokens,
                "totalTokens", promptTokens + completionTokens,
                "estimated", true));
        return done;
    }

    private int estimateTokens(String text) {
        return text == null ? 0 : Math.max(0, text.length());
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            throw new IllegalStateException("SSE 发送失败: " + e.getMessage(), e);
        }
    }

    private void sendError(SseEmitter emitter, int code, String message) {
        try {
            send(emitter, "error", Map.of("code", code, "message", message == null ? "unknown error" : message));
        } catch (Exception ignored) {
            // client already gone
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // already completed
        }
    }

    private void dispose(AtomicReference<Disposable> ref) {
        Disposable disposable = ref.get();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    private String title(String question) {
        return question.length() > 50 ? question.substring(0, 50) : question;
    }
}
