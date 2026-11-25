package com.pollen.rag.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Local no-key chat model used by the "mock" profile.
 * It exists so the upload -> retrieve -> SSE -> citation loop can be
 * verified before a real LLM key is configured.
 */
public class MockChatModel implements ChatModel {

    private final String modelName;

    public MockChatModel(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return response(answer(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String answer = answer(prompt);
        return Flux.fromIterable(split(answer, 6)).map(this::response);
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private String answer(Prompt prompt) {
        String question = questionOf(prompt);
        return "（Mock 模式，未调用真实大模型）\n\n"
                + "根据检索到的参考资料，关于「" + question + "」的回答如下：\n\n"
                + "1. 资料中给出了对应的流程与规则，详见引用 [1]。\n"
                + "2. 如需真实模型回答，请设置环境变量 LLM_API_KEY 后以默认 profile 启动。\n\n"
                + "本回答由 " + modelName + " 生成，仅用于验证检索、SSE 流式输出、引用与会话落库链路。";
    }

    private String questionOf(Prompt prompt) {
        String question = prompt.getInstructions().stream()
                .filter(message -> message instanceof UserMessage)
                .map(Message::getText)
                .reduce((first, second) -> second)
                .orElse("当前问题");
        question = question == null ? "当前问题" : question.trim();
        if (question.isBlank()) {
            question = "当前问题";
        }
        return question.length() > 60 ? question.substring(0, 60) + "..." : question;
    }

    private List<String> split(String text, int size) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }
}
