package com.example.inventory.tenancy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Binds {@code app.tenant_id} to every connection checked out of the application
 * pool, and clears it when the connection goes back (CLAUDE.md T3).
 *
 * <p>This is the component that turns {@link TenantContext} — a ThreadLocal the
 * database knows nothing about — into the session variable that RLS actually
 * reads. Without it every policy evaluates {@code tenant_id = NULL} and the
 * application sees nothing at all.
 *
 * <h2>Set on checkout, reset on release, and why both</h2>
 *
 * <p>Setting on checkout is what makes queries work. Resetting on release is
 * what stops a connection carrying one request's tenant into the next request
 * that borrows it — the leak T3 exists to prevent. Reset alone would be
 * insufficient and set alone would <em>almost</em> work, since every checkout
 * overwrites the value; the reset closes the window where a connection sits idle
 * in the pool holding a real tenant id, which matters because anything that
 * borrows a connection without going through this class (a health check, a
 * Hibernate internal, a future scheduled job) would otherwise inherit it.
 *
 * <p>Unbound is written as the empty string rather than skipped, because
 * {@code current_tenant_id()} is {@code NULLIF(current_setting(...), '')::uuid}.
 * Empty means NULL means no rows — the safe direction. A checkout with no tenant
 * bound is therefore explicitly harmless rather than merely untouched.
 *
 * <h2>Why a DataSource wrapper rather than Hibernate's SPI</h2>
 *
 * <p>Hibernate 7 offers {@code MultiTenantConnectionProvider}, which is the
 * obvious home for this and covers only Hibernate. Reporting and sync in this
 * project use hand-written SQL through {@code JdbcTemplate} by convention, and
 * those connections need the same binding — a tenant scoping that applies to JPA
 * but not to the hand-written queries would be worse than none, because it would
 * look like it worked. Wrapping the {@link DataSource} covers both, and every
 * future path, for free.
 *
 * <h2>Not on the login pool</h2>
 *
 * <p>Only the application pool is wrapped. {@code loginDataSource} deliberately
 * runs unbound — that is the whole reason it exists — and binding a tenant there
 * would be meaningless (its {@code login_read} policy already returns every row)
 * and misleading.
 */
public class TenantConnectionProvider extends DelegatingDataSource {

    /**
     * {@code set_config(..., false)} — session scope, not transaction scope.
     *
     * <p>Transaction-local would be wrong here. The binding happens at checkout,
     * which is outside any transaction the caller is about to start, so a
     * transaction-scoped setting would be discarded before the first query ran.
     * Session scope is why the reset below is mandatory rather than tidy.
     */
    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, false)";
    private static final String SET_USER = "SELECT set_config('app.user_id', ?, false)";

    public TenantConnectionProvider(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(super.getConnection(username, password));
    }

    private Connection bind(Connection connection) throws SQLException {
        try {
            applyCurrentTenant(connection);
        } catch (SQLException | RuntimeException e) {
            // Never hand back a connection whose tenant state is unknown: it
            // would run the caller's query under whatever the previous borrower
            // left behind.
            closeQuietly(connection);
            throw e;
        }
        return proxy(connection);
    }

    private static void applyCurrentTenant(Connection connection) throws SQLException {
        var identity = TenantContext.current().orElse(null);
        setConfig(connection, SET_TENANT, identity == null ? null : identity.tenantId());
        setConfig(connection, SET_USER, identity == null ? null : identity.userId());
    }

    private static void setConfig(Connection connection, String sql, UUID value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value == null ? "" : value.toString());
            ps.execute();
        }
    }

    /**
     * Wraps the connection so that {@code close()} clears the tenant before the
     * connection returns to the pool.
     *
     * <p>A dynamic proxy rather than a hand-written delegate: {@link Connection}
     * has a large surface, and a delegate written by hand would need updating
     * whenever the JDBC interface grows. Only {@code close} is intercepted.
     */
    private static Connection proxy(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                TenantConnectionProvider.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new ClearOnClose(connection));
    }

    private record ClearOnClose(Connection target) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                clearThenClose();
                return null;
            }
            // equals/hashCode must not be forwarded blindly, or two proxies over
            // the same connection would compare unequal in a pool's bookkeeping.
            if ("equals".equals(method.getName()) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(method.getName()) && (args == null || args.length == 0)) {
                return System.identityHashCode(proxy);
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        private void clearThenClose() throws SQLException {
            try {
                if (!target.isClosed()) {
                    setConfig(target, SET_TENANT, null);
                    setConfig(target, SET_USER, null);
                }
            } catch (SQLException e) {
                // A connection that cannot be cleared must not go back into the
                // pool still carrying a tenant. Closing it costs one reconnect;
                // reusing it risks serving another tenant's rows.
                closeQuietly(target);
                throw e;
            }
            target.close();
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The caller is already handling a failure; this must not mask it.
        }
    }
}
