package org.havryliuk.rag.ragdemo.model;

public record DocumentResponse(
        String message,
        int chunksCreated
) {
}
