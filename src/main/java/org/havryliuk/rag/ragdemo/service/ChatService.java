package org.havryliuk.rag.ragdemo.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.havryliuk.rag.ragdemo.model.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int SOURCE_PREVIEW_LENGTH = 100;

    private final ChatClient chatClient;

    public ChatResponse ask(String question) {
        var response = chatClient.prompt()
                .user(question)
                .call()
                .chatResponse();

        if (response == null) {
            log.warn("Can't get chat response");
            throw new RuntimeException("Can't get chat response");
        }

        String answer = response.getResult().getOutput().getContent();
        List<String> sources = extractSources(response);

        return new ChatResponse(answer, sources);
    }

    private List<String> extractSources(org.springframework.ai.chat.model.ChatResponse response) {
        var context = response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (context instanceof List<?> documents) {
            return documents.stream()
                    .filter(Document.class::isInstance)
                    .map(Document.class::cast)
                    .map(doc -> truncate(doc.getContent()))
                    .toList();
        }
        return List.of();
    }

    private String truncate(String text) {
        if (text == null || text.length() <= SOURCE_PREVIEW_LENGTH) {
            return text;
        }
        return text.substring(0, SOURCE_PREVIEW_LENGTH) + "...";
    }
}
