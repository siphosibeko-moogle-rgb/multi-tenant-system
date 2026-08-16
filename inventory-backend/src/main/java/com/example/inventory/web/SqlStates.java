package com.example.inventory.web;

import java.sql.SQLException;

/**
 * PostgreSQL SQLSTATE codes this application reacts to, and the one correct way
 * to read a SQLSTATE off a Spring exception.
 *
 * <h2>Why anything dispatches on SQLSTATE at all</h2>
 *
 * <p>Because the exception type is not specific enough to be safe. Spring's
 * {@code SQLStateSQLExceptionTranslator} collapses whole classes of SQLSTATE
 * onto a handful of types: a row-level-security refusal and a genuine syntax
 * error both arrive as {@code BadSqlGrammarException}, and an oversell arrives
 * as {@code DataIntegrityViolationException} alongside every other CHECK
 * constraint on the table. Dispatching on type therefore either misses the case
 * or catches far too much — see {@link GlobalExceptionHandler} for what that
 * costs when the case is a tenant-isolation breach.
 */
public final class SqlStates {

    private SqlStates() {
    }

    /**
     * {@code insufficient_privilege}. Raised both for a missing table privilege
     * and for a {@code WITH CHECK} rejection — i.e. an attempted cross-tenant
     * write.
     */
    public static final String INSUFFICIENT_PRIVILEGE = "42501";

    /**
     * {@code check_violation}. The oversell trigger raises this, and so does
     * every CHECK constraint on the table, so the message must be consulted to
     * tell them apart.
     */
    public static final String CHECK_VIOLATION = "23514";

    /** {@code foreign_key_violation} — a referenced row is absent, or invisible. */
    public static final String FOREIGN_KEY_VIOLATION = "23503";

    /**
     * Walks the cause chain to the {@link SQLException} and returns its SQLSTATE,
     * or null if there is no SQLException in the chain.
     *
     * <p>The walk is the point. Spring wraps the driver's exception at least
     * once and the wrapper carries no SQLSTATE at all, so a single-level check
     * finds nothing and silently falls through to whatever the default branch
     * does.
     */
    public static String of(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    /** The message of the deepest cause — where PostgreSQL's own text lives. */
    public static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
