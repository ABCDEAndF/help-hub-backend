package org.isolatedareas.helphub.api;

public final class IdempotencyKeys {
    private IdempotencyKeys() {
    }

    public static String resolve(String standardKey, String cloudRunKey) {
        if (standardKey != null && !standardKey.isBlank()) {
            return standardKey;
        }
        return cloudRunKey;
    }
}
