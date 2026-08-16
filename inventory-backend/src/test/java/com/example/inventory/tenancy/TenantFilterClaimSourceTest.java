package com.example.inventory.tenancy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down the single most important property of {@link TenantFilter}: the
 * tenant it binds comes from the verified {@code tid} claim, and from nothing
 * else a caller can influence (CLAUDE.md T1).
 *
 * <p>Two independent checks, because either alone is escapable. The behavioural
 * tests prove the filter ignores headers and parameters <em>today</em>; the
 * source check proves it has no way to read them at all, which is what stops a
 * future edit from quietly adding an {@code X-Tenant-Id} escape hatch "just for
 * the admin tool" and having every behavioural test still pass because nobody
 * wrote one for that header.
 */
@DisplayName("TenantFilter reads the tid claim and nothing else")
class TenantFilterClaimSourceTest {

    private final TenantFilter filter = new TenantFilter();

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static Jwt tokenFor(UUID tenantId, UUID userId, String role) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(userId.toString())
                .claim("tid", tenantId.toString())
                .claim("role", role)
                .build();
    }

    private static void authenticateWith(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(jwt, null, List.of()));
    }

    /** Captures what was bound at the moment the chain ran, before the filter clears it. */
    private UUID runAndCaptureBoundTenant(MockHttpServletRequest request) throws Exception {
        UUID[] captured = new UUID[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req,
                                 jakarta.servlet.ServletResponse res) {
                captured[0] = TenantContext.currentTenantId().orElse(null);
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return captured[0];
    }

    @Test
    @DisplayName("binds the tid claim from the verified token")
    void bindsTheTenantFromTheClaim() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        authenticateWith(tokenFor(tenant, user, "manager"));

        assertThat(runAndCaptureBoundTenant(new MockHttpServletRequest()))
                .as("without this the other assertions here would pass on a filter that binds nothing")
                .isEqualTo(tenant);
    }

    @Test
    @DisplayName("a header, query parameter or body naming another tenant is ignored")
    void requestSuppliedTenantIdsAreIgnored() throws Exception {
        UUID realTenant = UUID.randomUUID();
        UUID attackerTenant = UUID.randomUUID();
        authenticateWith(tokenFor(realTenant, UUID.randomUUID(), "clerk"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", attackerTenant.toString());
        request.addHeader("X-Tenant", attackerTenant.toString());
        request.addHeader("tid", attackerTenant.toString());
        request.setParameter("tenantId", attackerTenant.toString());
        request.setParameter("tenant_id", attackerTenant.toString());

        assertThat(runAndCaptureBoundTenant(request))
                .as("the token wins over anything the caller attached to the request")
                .isEqualTo(realTenant);
    }

    @Test
    @DisplayName("with no token, nothing is bound however much the request asks")
    void withoutATokenNothingIsBound() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", UUID.randomUUID().toString());
        request.setParameter("tenantId", UUID.randomUUID().toString());

        assertThat(runAndCaptureBoundTenant(request))
                .as("an unauthenticated request must bind no tenant at all — app.tenant_id stays "
                        + "empty and RLS returns zero rows, which is the safe direction")
                .isNull();
    }

    @Test
    @DisplayName("a signed token without tid binds nothing rather than guessing")
    void aTokenMissingTheClaimBindsNothing() throws Exception {
        authenticateWith(Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("role", "owner")
                .build());

        assertThat(runAndCaptureBoundTenant(new MockHttpServletRequest())).isNull();
    }

    @Test
    @DisplayName("the context is cleared even when the chain throws")
    void contextIsClearedOnFailure() {
        authenticateWith(tokenFor(UUID.randomUUID(), UUID.randomUUID(), "owner"));

        MockFilterChain throwing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req,
                                 jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("handler blew up");
            }
        };

        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), throwing);
        } catch (Exception expected) {
            // The exception is the handler's, not the filter's concern.
        }

        assertThat(TenantContext.current())
                .as("a failed request must not leave its tenant on a pooled thread")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // The structural guard
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the source reads nothing off the request at all")
    void theFilterSourceNeverReadsTheRequest() throws Exception {
        Path source = Path.of("src/main/java/com/example/inventory/tenancy/TenantFilter.java");
        assertThat(source)
                .as("if this class moved, update the path rather than deleting the test")
                .exists();

        // Only the code, so that the javadoc above can discuss X-Tenant-Id
        // without tripping its own guard.
        String code = Files.readAllLines(source).stream()
                .map(String::strip)
                .filter(line -> !line.startsWith("*") && !line.startsWith("//")
                        && !line.startsWith("/*"))
                .reduce("", (a, b) -> a + "\n" + b);

        SoftAssertions.assertSoftly(softly -> {
            for (String forbidden : List.of(
                    "getHeader", "getHeaders", "getParameter", "getParameterMap",
                    "getQueryString", "getCookies", "getInputStream", "getReader",
                    "getPathInfo", "getRequestURI", "getAttribute")) {
                softly.assertThat(code)
                        .as("TenantFilter must not call %s. The tenant comes from the verified "
                                + "tid claim and from nothing a caller can set — a request-supplied "
                                + "tenant id is a forgeable tenant id (T1).", forbidden)
                        .doesNotContain(forbidden);
            }
        });
    }
}
