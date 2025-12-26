package org.havryliuk.rag.ragdemo.model;

import java.util.List;

public record ChatResponse(
        String answer,
        List<String> sources
) {
}
