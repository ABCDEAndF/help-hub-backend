package org.isolatedareas.helphub.routing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/routes")
public class RoutePlanningController {
    private final RoutePlanningService routes;

    public RoutePlanningController(RoutePlanningService routes) {
        this.routes = routes;
    }

    @PostMapping
    RoutePlanningService.RoutePlanView create(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody PlanRequest input
    ) {
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

