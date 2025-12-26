package org.havryliuk.rag.ragdemo.model;

import jakarta.validation.constraints.NotBlank;

public record DocumentRequest(
        @NotBlank(message = "Content cannot be blank")
        String content
) {
}
