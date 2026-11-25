package com.pollen.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.File;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * In-memory vector index used by the first runnable cut.
     * It implements the same Spring AI VectorStore contract as Milvus, so
     * DenseSearcher / IngestPipeline are the only classes to swap in phase 2.
     */
    @Bean
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel, RagProperties props) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File file = new File(props.getStorage().getVectorFile());
        if (file.exists() && file.length() > 0) {
            try {
                store.load(file);
                log.info("Loaded vector store from {}", file.getAbsolutePath());
            } catch (Exception e) {
                log.warn("Failed to load vector store from {}, starting empty: {}", file.getAbsolutePath(), e.getMessage());
            }
        }
        return store;
    }

    @Bean("ingestExecutor")
    public ThreadPoolTaskExecutor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        // Spring calls afterPropertiesSet()/initialize() on the bean; do not call initialize() here.
        return executor;
    }

    @Bean
    @Profile("mock")
    public ChatModel mockChatModel(@Value("${spring.ai.deepseek.chat.options.model:mock-chat}") String modelName) {
        return new MockChatModel(modelName);
    }
}
