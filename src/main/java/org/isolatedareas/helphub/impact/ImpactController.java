package org.isolatedareas.helphub.impact;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ImpactController {
    private final ImpactService impact;
    private final FeedbackService feedback;

    public ImpactController(ImpactService impact, FeedbackService feedback) {
        this.impact = impact;
        this.feedback = feedback;
    }

    @GetMapping("/admin/impact")
    ImpactService.ImpactSummary summary() {
        return impact.summary();
    }

    @PostMapping("/resident/feedback")
    void feedback(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FeedbackInput input) {
        feedback.record(CurrentUser.id(jwt), input.requestId(), input.rating(), input.comments(),
            input.previousTravelMinutes(), input.currentTravelMinutes());
    }

    public record FeedbackInput(long requestId, @Min(1) @Max(5) int rating,
                                @Size(max = 1000) String comments,
                                @Min(0) Integer previousTravelMinutes,
                                @Min(0) Integer currentTravelMinutes) {
    }
}

