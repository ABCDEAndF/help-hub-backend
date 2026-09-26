package org.isolatedareas.helphub.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.isolatedareas.helphub.api.IdempotencyService;
import org.isolatedareas.helphub.api.IdempotencyKeys;
import org.isolatedareas.helphub.auth.CurrentUser;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryController {
    private final InventoryRepository inventory;
    private final ReservationService reservations;
    private final IdempotencyService idempotency;

    public InventoryController(InventoryRepository inventory, ReservationService reservations,
                               IdempotencyService idempotency) {
        this.inventory = inventory;
        this.reservations = reservations;
        this.idempotency = idempotency;
    }

    @GetMapping("/api/resident/inventory")
    List<InventoryItemView> residentInventory() {
        return inventory.list();
    }

    @PostMapping("/api/resident/reservations")
    ReservationService.ReservationReceipt reserve(
        @AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @RequestHeader(value = "X-Idempotency-Key", required = false) String cloudRunKey,
        @RequestParam(value = "idempotencyKey", required = false) String queryKey,
        @Valid @RequestBody ReservationService.ReserveInput input
    ) {
        long userId = CurrentUser.id(jwt);
        return idempotency.execute(IdempotencyKeys.resolve(key, cloudRunKey, queryKey), userId,
            "CREATE_RESERVATION", input,
            ReservationService.ReservationReceipt.class, () -> reservations.reserve(userId, input));
    }

    @GetMapping("/api/resident/reservations")
    List<ReservationService.ReservationView> myReservations(@AuthenticationPrincipal Jwt jwt) {
        return reservations.list(CurrentUser.id(jwt));
    }

    @GetMapping("/api/admin/inventory")
    List<InventoryItemView> adminInventory() {
        return inventory.list();
    }

    @PatchMapping("/api/admin/inventory/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    InventoryItemView adjust(@PathVariable long id, @Valid @RequestBody AdjustInventory input) {
        if (inventory.adjust(id, input.delta(), input.version()) != 1) {
            throw new OptimisticLockingFailureException("Inventory changed concurrently or adjustment is invalid");
        }
        return inventory.find(id).orElseThrow();
    }

    @PatchMapping("/api/admin/inventory/{id}/price")
    @PreAuthorize("hasRole('ADMIN')")
    InventoryItemView setPrice(@PathVariable long id, @Valid @RequestBody SetPrice input) {
        int changed = inventory.setPrice(id, input.unitPriceFen(), input.version());
        if (changed != 1) {
            throw new OptimisticLockingFailureException("Inventory changed concurrently");
        }
        return inventory.find(id).orElseThrow();
    }

    @PostMapping("/api/admin/reservations/{id}/collect")
    void collect(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody CollectInput input) {
        reservations.collect(CurrentUser.id(jwt), id, input.pickupCode());
    }

    public record AdjustInventory(@Min(-10000) @Max(10000) int delta, @Min(0) long version) {
    }
    public record SetPrice(@Min(0) @Max(1_000_000) int unitPriceFen, @Min(0) long version) {
    }
    public record CollectInput(@jakarta.validation.constraints.Pattern(regexp = "\\d{6}") String pickupCode) {
    }
}
