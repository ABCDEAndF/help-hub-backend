package org.isolatedareas.helphub.requests;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.isolatedareas.helphub.automation.ApprovalService;
import org.isolatedareas.helphub.automation.CourierRouteService;
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
    private final CourierRouteService couriers;
    private final ApprovalService approvals;

    public AdminRequestController(RequestService service, SupplyRequestRepository requests, CourierRouteService couriers,
                                  ApprovalService approvals) {
        this.service = service;
        this.requests = requests;
        this.couriers = couriers;
        this.approvals = approvals;
    }

    @GetMapping
    List<SupplyRequestView> list(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) RequestStatus status,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        int size = Math.min(Math.max(limit, 1), 100);
        // A courier sees only the deliveries on their own cart.
        Optional<Long> cart = couriers.courierCart(jwt);
        if (cart.isPresent()) return requests.findForCart(cart.get(), size, Math.max(offset, 0));
        return requests.findForOperations(status, size, Math.max(offset, 0));
    }

    @PatchMapping("/{id}/status")
    SupplyRequestView transition(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable long id,
        @Valid @RequestBody RequestService.TransitionRequest input
    ) {
        couriers.requireOwnRequest(jwt, id);
        // Approval is never automatic: it is this explicit staff action, and it reserves the stock.
        if (input.status() == RequestStatus.APPROVED) return approvals.approve(id, CurrentUser.id(jwt));
        return service.transition(id, CurrentUser.id(jwt), input);
    }
}

