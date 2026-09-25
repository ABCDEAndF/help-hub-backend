package org.isolatedareas.helphub.impact;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.isolatedareas.helphub.requests.SupplyRequestRepository;
import org.isolatedareas.helphub.requests.SupplyRequestView;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ImpactController {
    private final ImpactService impact;
    private final SupplyRequestRepository requests;
    private final JdbcClient jdbc;

    public ImpactController(ImpactService impact, SupplyRequestRepository requests, JdbcClient jdbc) {
        this.impact = impact;
        this.requests = requests;
        this.jdbc = jdbc;
    }

    @GetMapping("/admin/impact")
    ImpactService.ImpactSummary summary() {
        return impact.summary();
    }

    @PostMapping("/resident/feedback")
    @Transactional
    void feedback(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FeedbackInput input) {
        long residentId = CurrentUser.id(jwt);
        SupplyRequestView request = requests.findOwned(input.requestId(), residentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        if (request.status() != org.isolatedareas.helphub.domain.RequestStatus.FULFILLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Feedback requires a fulfilled request");
        }
        jdbc.sql("""
                INSERT INTO service_feedback
                  (request_id, resident_id, rating, comments, previous_travel_minutes, current_travel_minutes)
                VALUES (:requestId, :residentId, :rating, :comments, :previous, :current)
                """).param("requestId", input.requestId()).param("residentId", residentId)
            .param("rating", input.rating()).param("comments", input.comments())
            .param("previous", input.previousTravelMinutes()).param("current", input.currentTravelMinutes()).update();
        jdbc.sql("""
                INSERT INTO impact_events (event_type, resident_id, request_id, value_number, metadata)
                VALUES ('SATISFACTION_RECORDED', :residentId, :requestId, :rating,
                  JSON_OBJECT('previousTravelMinutes', :previous, 'currentTravelMinutes', :current))
                """).param("residentId", residentId).param("requestId", input.requestId())
            .param("rating", input.rating()).param("previous", input.previousTravelMinutes())
            .param("current", input.currentTravelMinutes()).update();
    }

    public record FeedbackInput(long requestId, @Min(1) @Max(5) int rating,
                                @Size(max = 1000) String comments,
                                @Min(0) Integer previousTravelMinutes,
                                @Min(0) Integer currentTravelMinutes) {
    }
}

