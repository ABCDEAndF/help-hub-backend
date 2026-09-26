package org.isolatedareas.helphub.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.isolatedareas.helphub.audit.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrators create or reset staff sign-ins (couriers, service-point staff). */
@RestController
@RequestMapping("/api/admin/staff")
@PreAuthorize("hasRole('ADMIN')")
public class StaffController {
    private final UserRepository users;
    private final AuditService audit;

    public StaffController(UserRepository users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    @GetMapping
    List<UserAccount> list() {
        return users.staff();
    }

    @PutMapping
    @Transactional
    UserAccount upsert(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StaffInput input) {
        UserAccount account = users.upsertStaff(input.account().trim(), input.displayName().trim(),
            AuthController.PASSWORDS.encode(input.password()));
        audit.record(CurrentUser.id(jwt), "STAFF_ACCOUNT_SAVED", "USER", account.id(), null,
            Map.of("account", account.phone(), "displayName", account.displayName()));
        return account;
    }

    public record StaffInput(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,32}") String account,
        @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Size(min = 12, max = 72) String password
    ) {
    }
}
