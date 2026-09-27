package org.isolatedareas.helphub.routing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.isolatedareas.helphub.automation.CartTripService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/routes")
public class RoutePlanningController {
    private final RoutePlanningService routes;
    private final CartTripService trips;

    public RoutePlanningController(RoutePlanningService routes, CartTripService trips) {
        this.routes = routes;
        this.trips = trips;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    RoutePlanningService.RoutePlanView create(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody PlanRequest input
    ) {
        // Deliveries are dispatched automatically; a manual plan only helps when one is waiting.
        if (!trips.dispatchNeeded()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前没有待派送的配送需求，系统会自动派车");
        }
        return routes.requestPlan(CurrentUser.id(jwt), input.serviceDate());
    }

    @GetMapping
    List<RoutePlanningService.RoutePlanSummary> list() {
        return routes.list();
    }

    @GetMapping("/{id}")
    RoutePlanningService.RoutePlanView get(@PathVariable long id) {
        return routes.get(id);
    }

    public record PlanRequest(@NotNull LocalDate serviceDate) {
    }
}

