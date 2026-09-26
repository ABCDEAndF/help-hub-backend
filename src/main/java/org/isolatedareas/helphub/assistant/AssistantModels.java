package org.isolatedareas.helphub.assistant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AssistantModels {
    private AssistantModels() {
    }

    public record ChatRequest(
        String conversationId,
        @NotBlank @Size(max = 2000) String message,
        String confirmationToken,
        // The place the resident saved in the mini program, so the assistant can file a request
        // without asking for coordinates nobody knows by heart. Optional.
        @DecimalMin("30.0") @DecimalMax("32.0") BigDecimal latitude,
        @DecimalMin("120.0") @DecimalMax("123.0") BigDecimal longitude,
        @Size(max = 120) String placeLabel
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

