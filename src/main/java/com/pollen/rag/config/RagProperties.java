package com.pollen.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private VectorStoreConfig vectorStore = new VectorStoreConfig();
    private Retrieval retrieval = new Retrieval();
    private Ingestion ingestion = new Ingestion();
    private Storage storage = new Storage();
    private Llm llm = new Llm();
    private Prompt prompt = new Prompt();

    public VectorStoreConfig getVectorStore() {
        return vectorStore;
    }

    public void setVectorStore(VectorStoreConfig vectorStore) {
        this.vectorStore = vectorStore;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Retrieval retrieval) {
        this.retrieval = retrieval;
    }

    public Ingestion getIngestion() {
        return ingestion;
    }

    public void setIngestion(Ingestion ingestion) {
        this.ingestion = ingestion;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public static class VectorStoreConfig {
        private String type = "simple";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    public static class Retrieval {
        private int denseTopK = 20;
        private int sparseTopK = 20;
        private int fuseTopN = 5;
        private int rrfK = 60;
        private double scoreThreshold = 0.30;
        private boolean rerankEnabled = false;

        public int getDenseTopK() {
            return denseTopK;
        }

        public void setDenseTopK(int denseTopK) {
            this.denseTopK = denseTopK;
        }

        public int getSparseTopK() {
            return sparseTopK;
        }

        public void setSparseTopK(int sparseTopK) {
            this.sparseTopK = sparseTopK;
        }

        public int getFuseTopN() {
            return fuseTopN;
        }

        public void setFuseTopN(int fuseTopN) {
            this.fuseTopN = fuseTopN;
        }

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }

        public double getScoreThreshold() {
            return scoreThreshold;
        }

        public void setScoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
        }

        public boolean isRerankEnabled() {
            return rerankEnabled;
        }

        public void setRerankEnabled(boolean rerankEnabled) {
            this.rerankEnabled = rerankEnabled;
        }
    }

    public static class Ingestion {
        private int batchSize = 16;
        private int maxRetry = 3;
        private int defaultChunkSize = 600;
        private int defaultOverlap = 2;
        private int maxChunkChars = 2000;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxRetry() {
            return maxRetry;
        }

        public void setMaxRetry(int maxRetry) {
            this.maxRetry = maxRetry;
        }

        public int getDefaultChunkSize() {
            return defaultChunkSize;
        }

        public void setDefaultChunkSize(int defaultChunkSize) {
            this.defaultChunkSize = defaultChunkSize;
        }

        public int getDefaultOverlap() {
            return defaultOverlap;
        }

        public void setDefaultOverlap(int defaultOverlap) {
            this.defaultOverlap = defaultOverlap;
        }

        public int getMaxChunkChars() {
            return maxChunkChars;
        }

        public void setMaxChunkChars(int maxChunkChars) {
            this.maxChunkChars = maxChunkChars;
        }
    }

    public static class Storage {
        private String uploadDir = "./data/uploads";
        private String vectorFile = "./data/simple-vectors.json";

        public String getUploadDir() {
            return uploadDir;
        }

        public void setUploadDir(String uploadDir) {
            this.uploadDir = uploadDir;
        }

        public String getVectorFile() {
            return vectorFile;
        }

        public void setVectorFile(String vectorFile) {
            this.vectorFile = vectorFile;
        }
    }

    public static class Llm {
        private int timeoutSeconds = 60;

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Prompt {
        private int historyRounds = 3;

        public int getHistoryRounds() {
            return historyRounds;
        }

        public void setHistoryRounds(int historyRounds) {
            this.historyRounds = historyRounds;
        }
    }
}
