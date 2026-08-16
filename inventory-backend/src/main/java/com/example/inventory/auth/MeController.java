package com.example.inventory.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.auth.AuthDtos.CurrentUser;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.UnauthorizedException;

/**
 * {@code GET /me} — the authenticated caller, their role and their tenant.
 *
 * <p>Note where the identity comes from: {@link TenantContext}, which
 * {@code TenantFilter} populated from the verified {@code tid} and {@code sub}
 * claims. There is no parameter on this method at all, so there is nothing a
 * caller could send that would change whose profile is returned.
 */
@RestController
public class MeController {

    private final CurrentUserService currentUserService;

    public MeController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping("/me")
    ResponseEntity<CurrentUser> me() {
        var identity = TenantContext.current()
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));

        // Not found is reported as 401 rather than 404. The token verified, so
        // the caller is who they say; a missing row means the account was
        // deleted while the token was still live, and the correct answer is
        // "authenticate again", not "your user does not exist".
        return currentUserService.load(identity.tenantId(), identity.userId())
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));
    }
}
