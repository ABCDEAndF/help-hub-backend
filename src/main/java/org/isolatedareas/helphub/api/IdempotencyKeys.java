package org.isolatedareas.helphub.api;

public final class IdempotencyKeys {
    private IdempotencyKeys() {
    }

    public static String resolve(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
