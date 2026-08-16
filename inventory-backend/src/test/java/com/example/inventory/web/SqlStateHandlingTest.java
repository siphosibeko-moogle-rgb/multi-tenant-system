package com.example.inventory.web;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.example.inventory.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The one thing {@link GlobalExceptionHandler} must get right: a cross-tenant
 * write is identified by <strong>SQLSTATE 42501</strong>, not by exception type.
 *
 * <h2>Why the type is useless here</h2>
 *
 * <p>PostgreSQL raises {@code 42501} ({@code insufficient_privilege}) for a
 * {@code WITH CHECK} violation — an attempted cross-tenant write, the most
 * serious event this system can observe. Spring's
 * {@code SQLStateSQLExceptionTranslator} maps that SQLSTATE class to
 * {@link BadSqlGrammarException}, whose message names only the statement and
 * never mentions row-level security.
 *
 * <p>So the isolation violation arrives wearing the costume of a typo. Anything
 * dispatching on exception type reports and logs it as a malformed query, and
 * the one event that should page somebody looks like a developer error in a
 * WHERE clause.
 *
 * <p>These tests assert the disguise is real (so the risk is not hypothetical)
 * and that the handler sees through it by reading the SQLSTATE off the cause
 * chain.
 */
@DisplayName("SQLSTATE handling")
class SqlStateHandlingTest extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("appDataSource")
    private DataSource appDataSource;

    /** Provokes a genuine WITH CHECK violation on the RLS-bound pool. */
    private Throwable crossTenantWrite() {
        UUID boundTenant = newTenantId();
        UUID otherTenant = newTenantId();

        try (Connection conn = appDataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                JdbcTemplate jdbc =
                        new JdbcTemplate(new SingleConnectionDataSource(conn, true));
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                        String.class, boundTenant.toString());

                return catchThrowable(() -> jdbc.update(
                        "INSERT INTO categories (tenant_id, name) VALUES (?, ?)",
                        otherTenant, "Smuggled"));
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a cross-tenant write really does surface as BadSqlGrammarException")
    void theDisguiseIsReal() {
        Throwable thrown = crossTenantWrite();

        assertThat(thrown)
                .as("if this ever stops being BadSqlGrammarException, the reasoning in "
                        + "GlobalExceptionHandler needs revisiting — but do not relax the "
                        + "SQLSTATE check on the strength of it")
                .isInstanceOf(BadSqlGrammarException.class);

        assertThat(thrown.getMessage())
                .as("and its message names only the statement — nothing about row-level "
                        + "security, which is exactly why type-based dispatch fails here")
                .doesNotContain("row-level security")
                .doesNotContain("policy");
    }

    @Test
    @DisplayName("the handler identifies it by SQLSTATE 42501 off the cause chain")
    void theHandlerReadsTheSqlState() {
        Throwable thrown = crossTenantWrite();

        assertThat(GlobalExceptionHandler.sqlStateOf(thrown))
                .as("42501 is what identifies an isolation violation; the type does not")
                .isEqualTo(GlobalExceptionHandler.INSUFFICIENT_PRIVILEGE);
    }

    @Test
    @DisplayName("the SQLSTATE is only on the cause, never the wrapper")
    void theSqlStateIsNotOnTheWrapper() {
        // The reason sqlStateOf walks the chain rather than casting once. Spring
        // wraps the driver exception, and the wrapper carries no SQLSTATE at all,
        // so a single-level check would find nothing and fall through to the
        // generic branch — silently.
        Throwable thrown = crossTenantWrite();

        assertThat(thrown).isNotInstanceOf(SQLException.class);
        assertThat(thrown.getCause())
                .as("the driver's exception, and the only thing holding the SQLSTATE")
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("an ordinary SQL mistake is NOT reported as an isolation event")
    void aRealSyntaxErrorIsNotMistakenForOne() {
        // The other direction. A handler that treated every BadSqlGrammarException
        // as a tenant violation would cry wolf on every genuine typo, and the
        // alert would be ignored within a week.
        try (Connection conn = appDataSource.getConnection()) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));

            Throwable thrown = catchThrowable(() ->
                    jdbc.queryForObject("SELECT nonexistent_column FROM users", String.class));

            assertThat(GlobalExceptionHandler.sqlStateOf(thrown))
                    .as("an undefined column is 42703, not 42501 — the two must not be conflated")
                    .isNotEqualTo(GlobalExceptionHandler.INSUFFICIENT_PRIVILEGE);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("sqlStateOf survives a cause chain with no SQLException")
    void sqlStateOfHandlesAnUnrelatedException() {
        assertThatThrownBy(() -> {
            throw new IllegalStateException("nothing to do with the database");
        }).isInstanceOf(IllegalStateException.class);

        assertThat(GlobalExceptionHandler.sqlStateOf(
                new IllegalStateException("nothing to do with the database")))
                .as("must return null rather than throwing, or the handler itself becomes the bug")
                .isNull();
    }
}
