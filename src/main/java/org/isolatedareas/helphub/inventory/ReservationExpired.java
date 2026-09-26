package org.isolatedareas.helphub.inventory;

/** Published inside the expiry transaction when an unused hold is released. */
public record ReservationExpired(long requestId, long reservationId) {
}
