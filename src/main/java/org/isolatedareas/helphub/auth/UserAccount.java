package org.isolatedareas.helphub.auth;

import org.isolatedareas.helphub.domain.Role;

public record UserAccount(long id, String phone, String displayName, Role role, boolean enabled) {
}

