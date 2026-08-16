package com.example.inventory.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.LoginRequest;
import com.example.inventory.auth.AuthDtos.RefreshRequest;
import com.example.inventory.auth.AuthDtos.TenantChoices;
import com.example.inventory.auth.AuthDtos.TenantRegistrationRequest;
import com.example.inventory.auth.LoginService.LoginOutcome;
import com.example.inventory.web.AuthRateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * The unauthenticated authentication endpoints.
 *
 * <p>HTTP only: validate, delegate, map the result to a status code
 * (CLAUDE.md §4). Every decision worth reviewing is in the services.
 *
 * <p><strong>No method here takes a tenant id, in any form.</strong> Not a path
 * variable, not a query parameter, not a field on a request record, not a
 * header. T1 is enforced by there being no way to express one.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final TenantRegistrationService registrationService;
    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final AuthRateLimiter rateLimiter;

    public AuthController(TenantRegistrationService registrationService,
                          LoginService loginService,
                          RefreshTokenService refreshTokenService,
                          AuthRateLimiter rateLimiter) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register-tenant")
    ResponseEntity<AuthTokens> registerTenant(@Valid @RequestBody TenantRegistrationRequest request,
                                              HttpServletRequest http) {
        // Rate limited before any work: this endpoint is unauthenticated and
        // writes rows, so it is the cheapest way to fill the database.
        rateLimiter.checkRegistration(http);
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.register(request));
    }

    /**
     * 200 with tokens, or 300 with the candidate tenants when the email belongs
     * to more than one business.
     */
    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimiter.checkLogin(http);

        return switch (loginService.login(request)) {
            case LoginOutcome.Authenticated authenticated ->
                    ResponseEntity.ok(authenticated.tokens());
            case LoginOutcome.MultipleTenants multiple ->
                    ResponseEntity.status(HttpStatus.MULTIPLE_CHOICES)
                            .body(new TenantChoices(multiple.tenants()));
        };
    }

    @PostMapping("/refresh")
    ResponseEntity<AuthTokens> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(refreshTokenService.refresh(request.refreshToken(), null));
    }

    /**
     * Always 204, whether or not the token was live.
     *
     * <p>Reporting "that token was already revoked" would turn logout into an
     * oracle for whether a stolen token is still usable.
     */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request) {
        if (request != null && request.refreshToken() != null) {
            refreshTokenService.logout(request.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }
}
