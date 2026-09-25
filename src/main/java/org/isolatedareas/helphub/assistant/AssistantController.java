package org.isolatedareas.helphub.assistant;

import jakarta.validation.Valid;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resident/assistant")
public class AssistantController {
    private final AssistantService assistant;

    public AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/chat")
    AssistantModels.ChatResponse chat(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody AssistantModels.ChatRequest request
    ) {
        return assistant.chat(CurrentUser.id(jwt), request);
    }
}

