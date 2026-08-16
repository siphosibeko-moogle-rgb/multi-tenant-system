package com.example.inventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Fails the build if {@code docs/openapi.yaml} is not a valid OpenAPI 3.1
 * document.
 *
 * <h2>Why this exists</h2>
 *
 * <p>CLAUDE.md section 1 calls that file the source of truth, and section 7 used
 * to claim it validated against OpenAPI 3.1. It did not, and never had: every
 * line was flush-left, so it failed to parse as YAML at line 23 and no OpenAPI
 * tool could read it at all. That went unnoticed from the first commit through
 * the whole of M0 and M1, because nothing ever tried to read it.
 *
 * <p>A contract nothing parses is a contract that rots silently. This is the
 * check that stops it happening twice — and it runs on every {@code ./mvnw test},
 * not when somebody remembers.
 *
 * <h2>Two checks, deliberately separate</h2>
 *
 * <p>Well-formed YAML and a valid OpenAPI document are different failures with
 * very different messages. Indentation damage of the kind this file had produces
 * a YAML scanner error pointing at a line number, which is actionable; running
 * it straight through the OpenAPI parser instead yields a pile of resolution
 * errors about missing fields, which is not. So YAML is asserted first and the
 * OpenAPI check only runs on something that at least parses.
 *
 * <p>M3 generates the Android client from this file
 * ({@code ./gradlew :app:generateApiClient}), which cannot work while it is
 * broken — so this is a real build dependency, not tidiness.
 */
@DisplayName("The API contract")
class ApiContractTest {

    /**
     * The contract lives beside the backend module, not inside it — it describes
     * an API that the Android client also consumes.
     */
    private static final Path CONTRACT = Path.of("..", "docs", "openapi.yaml");

    @Test
    @DisplayName("exists and parses as YAML")
    void contractParsesAsYaml() throws Exception {
        assertThat(CONTRACT)
                .as("docs/openapi.yaml is the source of truth and must be present")
                .exists();

        String content = Files.readString(CONTRACT);
        try {
            Object parsed = new Yaml().load(content);
            assertThat(parsed)
                    .as("the contract must be a YAML mapping, not a scalar or a list")
                    .isInstanceOf(Map.class);
        } catch (Exception e) {
            fail("""
                    docs/openapi.yaml does not parse as YAML.

                    This is exactly the failure that went unnoticed from the first commit \
                    through M0 and M1: the file was flush-left, so every level of nesting \
                    was gone while the content still read correctly to a human.

                    Do not reconstruct the indentation by inference — a plausible-but-wrong \
                    structure in the contract of record is worse than an obviously broken \
                    one. Restore a known-good copy.

                    Parser said: %s""".formatted(e.getMessage()));
        }
    }

    @Test
    @DisplayName("is a valid OpenAPI 3.1 document")
    void contractIsValidOpenApi31() {
        ParseOptions options = new ParseOptions();
        // Resolve $ref so a dangling reference is an error rather than an
        // unread placeholder. The contract leans heavily on components/*, and a
        // typo'd $ref is precisely the kind of rot this test is here to catch.
        options.setResolve(true);
        options.setResolveFully(false);

        SwaggerParseResult result =
                new OpenAPIV3Parser().readLocation(CONTRACT.toString(), null, options);

        List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();

        assertThat(result.getOpenAPI())
                .as("the contract must be readable as an OpenAPI document. Parser messages: %s",
                        messages)
                .isNotNull();

        assertThat(messages)
                .as("the contract must have no validation errors — including dangling $refs")
                .isEmpty();

        assertThat(result.getOpenAPI().getOpenapi())
                .as("this project targets OpenAPI 3.1")
                .startsWith("3.1");

        // Guards the test itself. A document that parsed but described nothing
        // would satisfy everything above.
        assertThat(result.getOpenAPI().getPaths())
                .as("a contract with no paths is not a contract")
                .isNotEmpty();
    }

    @Test
    @DisplayName("still declares the auth endpoints M1 implements")
    void contractCoversTheAuthEndpoints() {
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(
                CONTRACT.toString(), null, new ParseOptions());

        assertThat(result.getOpenAPI())
                .as("covered by contractIsValidOpenApi31; skipping detail if unreadable")
                .isNotNull();

        // Keeps code and contract honest in one direction at least: if an
        // endpoint M1 built is dropped from the contract, this fails. The
        // reverse direction — contract paths with no implementation — is
        // expected for now, since most of the contract is M2 onwards.
        assertThat(result.getOpenAPI().getPaths().keySet())
                .as("M1 implements these, so the contract must still describe them")
                .contains("/auth/register-tenant", "/auth/login", "/auth/refresh",
                        "/auth/logout", "/me", "/users");
    }
}
