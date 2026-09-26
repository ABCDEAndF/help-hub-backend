package org.isolatedareas.helphub.automation;

import java.time.Instant;
import java.util.List;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class TripController {
    private final CartTripService trips;

    public TripController(CartTripService trips) {
        this.trips = trips;
    }

    /** The cart delivering the caller's own request: position, ETA and a path that shows only their stop. */
    @GetMapping("/resident/requests/{id}/tracking")
    CartTripService.Tracking tracking(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return trips.tracking(id, CurrentUser.id(jwt), Instant.now())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No delivery trip for this request"));
    }

    /** Courier closes a delivery without the resident's code; the resident may appeal by phone. */
    @PostMapping("/admin/requests/{id}/mark-delivered")
    void markDelivered(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        trips.markDelivered(id, CurrentUser.id(jwt));
    }

    @GetMapping("/admin/trips")
    List<CartTripService.AdminTrip> activeTrips() {
        return trips.adminTrips(Instant.now());
    }
}
