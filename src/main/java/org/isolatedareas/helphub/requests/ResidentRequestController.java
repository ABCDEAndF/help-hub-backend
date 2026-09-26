package org.isolatedareas.helphub.requests;

import jakarta.validation.Valid;
import java.util.List;
import org.isolatedareas.helphub.api.IdempotencyService;
import org.isolatedareas.helphub.api.IdempotencyKeys;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resident/requests")
public class ResidentRequestController {
    private final RequestService service;
    private final SupplyRequestRepository requests;
    private final IdempotencyService idempotency;

    public ResidentRequestController(RequestService service, SupplyRequestRepository requests,
                                     IdempotencyService idempotency) {
        this.service = service;
        this.requests = requests;
        this.idempotency = idempotency;
    }

    @PostMapping
    SupplyRequestView create(@AuthenticationPrincipal Jwt jwt,
                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                             @RequestHeader(value = "X-Idempotency-Key", required = false) String cloudRunKey,
                             @Valid @RequestBody CreateSupplyRequest input) {
        long userId = CurrentUser.id(jwt);
        return idempotency.execute(IdempotencyKeys.resolve(key, cloudRunKey), userId,
            "CREATE_SUPPLY_REQUEST", input, SupplyRequestView.class,
            () -> service.create(userId, input));
    }

    @GetMapping
    List<SupplyRequestView> mine(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return requests.findByResident(CurrentUser.id(jwt), Math.min(Math.max(limit, 1), 100), Math.max(offset, 0));
    }

    @GetMapping("/{id}")
    SupplyRequestView get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return requests.findOwned(id, CurrentUser.id(jwt))
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Supply request not found"));
    }
}
