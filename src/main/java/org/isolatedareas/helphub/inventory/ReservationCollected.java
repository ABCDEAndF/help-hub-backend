package org.isolatedareas.helphub.inventory;

/** Published inside the collecting transaction once supplies have been handed over. */
public record ReservationCollected(long requestId, long reservationId, long actorId) {
}
