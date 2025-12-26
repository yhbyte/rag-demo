package org.havryliuk.rag.ragdemo.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.havryliuk.rag.ragdemo.model.DocumentRequest;
import org.havryliuk.rag.ragdemo.model.DocumentResponse;
import org.havryliuk.rag.ragdemo.service.DocumentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping
    public DocumentResponse ingest(@Valid @RequestBody DocumentRequest request) {
        int chunksCreated = documentService.ingest(request.content());
        return new DocumentResponse("Document ingested successfully", chunksCreated);
    }
}
