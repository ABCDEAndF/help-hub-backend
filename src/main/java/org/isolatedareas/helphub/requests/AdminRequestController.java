package org.isolatedareas.helphub.requests;

import jakarta.validation.Valid;
import java.util.List;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.isolatedareas.helphub.domain.RequestStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/requests")
public class AdminRequestController {
    private final RequestService service;
    private final SupplyRequestRepository requests;

    public AdminRequestController(RequestService service, SupplyRequestRepository requests) {
        this.service = service;
        this.requests = requests;
    }

    @GetMapping
    List<SupplyRequestView> list(
        @RequestParam(required = false) RequestStatus status,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return requests.findForOperations(status, Math.min(Math.max(limit, 1), 100), Math.max(offset, 0));
    }

    @PatchMapping("/{id}/status")
    SupplyRequestView transition(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id,
        @Valid @RequestBody RequestService.TransitionRequest input
    ) {
        return service.transition(id, CurrentUser.id(jwt), input);
    }
}

