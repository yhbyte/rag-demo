package org.havryliuk.rag.ragdemo.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
@RequiredArgsConstructor
public class RagConfig {

    private final RagProperties ragProperties;

    @Value("classpath:prompts/rag-system-prompt.st")
    private Resource systemPromptResource;

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter(
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap(),
                5,
                10000,
                true
        );
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore) {
        return builder
                .defaultSystem(systemPromptResource)
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore,
                        SearchRequest.defaults().withTopK(ragProperties.getTopK())))
                .build();
    }
}
