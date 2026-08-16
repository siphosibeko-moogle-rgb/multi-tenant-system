package com.example.inventory.auth;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Stateless JWT security.
 *
 * <h2>What is open and why</h2>
 *
 * <ul>
 *   <li>{@code /auth/**} — necessarily. These endpoints exist to obtain a token,
 *       so requiring one would be circular. They are rate limited instead
 *       ({@code AuthRateLimiter}).</li>
 *   <li>{@code /actuator/health} — the container's liveness probe has no
 *       credentials. {@code show-details} is {@code never} in prod, so it
 *       returns UP or DOWN and nothing about the schema or the driver.</li>
 *   <li>Everything else requires a verified token.</li>
 * </ul>
 *
 * <h2>Stateless, and no CSRF</h2>
 *
 * <p>No sessions and no cookies: the credential is a bearer token the client
 * stores and sends explicitly. CSRF defences exist because browsers attach
 * cookies automatically to cross-site requests; nothing attaches an
 * {@code Authorization} header automatically, so there is no forgery to prevent.
 * If a cookie-based flow is ever added for a web client, CSRF has to come back
 * with it.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Obtaining a token cannot require a token.
                        .requestMatchers("/auth/register-tenant", "/auth/login", "/auth/refresh")
                        .permitAll()
                        // /auth/logout is NOT in that list. The contract applies
                        // the default bearerAuth to it — it is the one auth
                        // endpoint without `security: []` — and M1 originally had
                        // it open, which contradicted the contract. Requiring a
                        // token also makes the endpoint meaningful: revoking a
                        // session should be something the session's owner does.
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Maps the {@code role} claim onto a Spring Security authority.
     *
     * <p>The default converter reads {@code scope}/{@code scp}, which these
     * tokens do not carry, so without this every authenticated caller would have
     * <em>no</em> authorities and every {@code @PreAuthorize("hasRole(...)")}
     * would deny — including the owner's. The failure is at least loud, but it
     * would be loud in a way that invites someone to loosen the rule rather than
     * fix the mapping.
     *
     * <p>{@code ROLE_} prefix because {@code hasRole('owner')} looks for
     * {@code ROLE_owner}. The claim value stays lowercase, matching the
     * {@code user_role} enum in the database and the contract.
     */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString(JwtService.ROLE_CLAIM);
            return role == null || role.isBlank()
                    ? List.of()
                    : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    /**
     * bcrypt at the default strength (10).
     *
     * <p>Slow on purpose: passwords are low-entropy and human-chosen, so the
     * defence against an offline attack on a stolen hash is the cost per guess.
     * Contrast {@code TokenIssuer}, which uses plain SHA-256 for refresh tokens
     * because those are 200+ bits of server-generated randomness with nothing to
     * guess.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    /**
     * Verifies signature and expiry, and rejects refresh tokens.
     *
     * <p>The last part is not optional. Both token kinds are signed by the same
     * key, so without an explicit check a refresh token presented as
     * {@code Authorization: Bearer …} verifies perfectly and authenticates the
     * request — turning a 30-day, at-rest credential into a general-purpose API
     * key and defeating the point of a 15-minute access token entirely.
     *
     * <p>{@code RefreshTokenService} makes the opposite check, so the two kinds
     * are non-interchangeable in both directions.
     */
    /**
     * {@code @Primary} because there are two {@link JwtDecoder} beans and the
     * resource-server filter chain must get the strict one. The permissive
     * refresh decoder is reachable only by qualifier.
     */
    @Bean
    @Primary
    JwtDecoder jwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(signingKey(properties))
                .macAlgorithm(AuthProperties.SIGNING_ALGORITHM)
                .build();

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                accessTokensOnly()));

        return decoder;
    }

    /**
     * The decoder {@code /auth/refresh} uses — signature, expiry and issuer, but
     * without the access-token-only rule, since refresh tokens are precisely
     * what it is given.
     *
     * <p>Two decoders rather than one, because a single permissive decoder would
     * mean the resource server accepting refresh tokens, and a single strict one
     * would mean the refresh endpoint rejecting them. Each accepts exactly the
     * kind it is for: {@code RefreshTokenService} rejects anything without
     * {@code typ=refresh}, and the bean above rejects anything with it.
     *
     * <p>This split was not obvious. Adding the {@code typ} check to the shared
     * decoder broke token rotation outright — caught by {@code AuthFlowTest},
     * which is why the rotation tests earn their keep.
     */
    @Bean
    JwtDecoder refreshTokenDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(signingKey(properties))
                .macAlgorithm(AuthProperties.SIGNING_ALGORITHM)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> accessTokensOnly() {
        OAuth2Error error = new OAuth2Error(
                "invalid_token", "A refresh token cannot be used as an access token", null);

        return jwt -> JwtService.REFRESH_TOKEN_TYPE.equals(
                jwt.getClaimAsString(JwtService.TOKEN_TYPE_CLAIM))
                ? OAuth2TokenValidatorResult.failure(error)
                : OAuth2TokenValidatorResult.success();
    }

    /**
     * Fails the application startup rather than accepting a weak key.
     *
     * <p>A short HMAC key is brute-forceable offline, and forging the signing key
     * means forging {@code tid} — which is the whole of tenant isolation. The
     * length check is here rather than as a bean-validation annotation so that
     * the message says why.
     */
    private static SecretKeySpec signingKey(AuthProperties properties) {
        String key = properties.signingKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "app.auth.signing-key must be set. There is deliberately no default: a "
                            + "shipped signing key is a key every deployment shares.");
        }
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < AuthProperties.MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(
                    "app.auth.signing-key must be at least " + AuthProperties.MINIMUM_KEY_BYTES
                            + " bytes for " + AuthProperties.SIGNING_ALGORITHM.getName()
                            + "; forging this key means forging the tid claim, which is the whole "
                            + "of tenant isolation");
        }
        return new SecretKeySpec(bytes, AuthProperties.SIGNING_ALGORITHM.getName());
    }
}
