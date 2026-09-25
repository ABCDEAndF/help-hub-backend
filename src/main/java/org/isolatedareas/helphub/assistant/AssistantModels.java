package org.isolatedareas.helphub.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AssistantModels {
    private AssistantModels() {
    }

    public record ChatRequest(
        String conversationId,
        @NotBlank @Size(max = 2000) String message,
        String confirmationToken
    ) {
    }

    public record ChatResponse(
        String conversationId,
        String message,
        List<ToolExecution> toolExecutions,
        Confirmation confirmation,
        boolean aiProviderUsed
    ) {
    }

    public record ToolExecution(String tool, Object result) {
    }

    public record Confirmation(String token, String summary, long expiresInSeconds) {
    }
}

