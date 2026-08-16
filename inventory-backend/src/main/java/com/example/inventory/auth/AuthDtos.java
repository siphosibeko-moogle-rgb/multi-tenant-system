package com.example.inventory.auth;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request and response shapes for {@code /auth/*} and {@code /me}, mirroring
 * {@code docs/openapi.yaml} — which is the contract of record (CLAUDE.md §1).
 *
 * <p>Records, per the project convention. Grouped in one file because they are
 * one contract read together; splitting eight four-line records across eight
 * files makes the shape harder to see, not easier.
 *
 * <p><strong>Note what no request record has: a tenant id.</strong> Not as a
 * field, not as a header binding. That absence is T1 expressed in the type
 * system — there is no syntax available to a caller for naming a tenant, so
 * there is nothing for the server to have to ignore. {@code TenantRegistrationRequest}
 * is the one place where a caller might expect to supply one, and it does not
 * have the field; a client that sends one anyway is ignored by Jackson, which
 * {@code TenantRegistrationTest} asserts rather than assumes.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record TenantRegistrationRequest(
            @NotBlank @Size(max = 200) String businessName,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$",
                    message = "must be lowercase letters, digits and hyphens")
            String slug,
            @NotBlank @Email @Size(max = 320) String ownerEmail,
            @NotBlank @Size(min = 8, max = 200) String ownerPassword,
            @NotBlank @Size(max = 200) String ownerName,
            @Size(min = 3, max = 3) String currencyCode,
            String timezone) {

        public String currencyCodeOrDefault() {
            return currencyCode == null || currencyCode.isBlank() ? "ZAR" : currencyCode;
        }

        public String timezoneOrDefault() {
            return timezone == null || timezone.isBlank() ? "UTC" : timezone;
        }
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8) String password,
            String tenantSlug,
            String deviceLabel) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TenantSummary(UUID id, String slug, String name) {
    }

    public record CurrentUser(
            UUID id,
            String email,
            String fullName,
            String role,
            TenantSummary tenant,
            String currencyCode,
            String timezone,
            UUID defaultLocationId) {
    }

    public record AuthTokens(
            String accessToken,
            String refreshToken,
            long expiresIn,
            String tokenType,
            CurrentUser user) {

        public static AuthTokens of(String access, String refresh, long expiresIn, CurrentUser user) {
            return new AuthTokens(access, refresh, expiresIn, "Bearer", user);
        }
    }

    /** The 300 response: an email that resolves to more than one tenant. */
    public record TenantChoices(List<TenantSummary> tenants) {
    }
}
