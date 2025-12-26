package org.havryliuk.rag.ragdemo.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public int ingest(String content) {
        log.info("User content: {}", content);

        List<Document> documents = List.of(new Document(content));
        List<Document> chunks = textSplitter.apply(documents);
        vectorStore.add(chunks);

        log.info("Successfully ingested new context. Chunks size: {}", chunks.size());
        return chunks.size();
    }
}
