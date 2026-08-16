package com.example.inventory.auth;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Records the SQL statements prepared through it, in order.
 *
 * <h2>Why this exists</h2>
 *
 * <p>To test {@code TenantRegistrationService}'s insert <em>ordering</em>, which
 * turns out not to be observable from the database's final state at all.
 *
 * <p>The first version of the ordering test asserted that no {@code users} row
 * existed for the victim tenant after a primary-key collision. That assertion
 * passes whether the inserts run in the right order or the wrong one, because
 * both inserts share a transaction and the rollback removes the user row either
 * way — it tests atomicity and quietly says nothing about ordering. Swapping the
 * two inserts as a mutation probe failed four other tests and left that one
 * green, which is how the gap came to light.
 *
 * <p>Ordering is a property of what the service <em>does</em>, not of what the
 * database ends up holding, so it has to be observed as it happens. This wrapper
 * is the smallest thing that can see it.
 *
 * <p>Worth keeping the distinction in mind: atomicity means the wrong order is
 * still safe <em>today</em>. Ordering matters the moment those two inserts stop
 * sharing a transaction — which is exactly the kind of refactor nobody announces.
 */
final class RecordingDataSource extends DelegatingDataSource {

    private final List<String> statements = new CopyOnWriteArrayList<>();

    RecordingDataSource(DataSource target) {
        super(target);
    }

    List<String> statements() {
        return List.copyOf(statements);
    }

    /** Index of the first recorded statement containing {@code fragment}, or -1. */
    int indexOf(String fragment) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    boolean sawAny(String fragment) {
        return indexOf(fragment) >= 0;
    }

    void reset() {
        statements.clear();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return record(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return record(super.getConnection(username, password));
    }

    private Connection record(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                RecordingDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (("prepareStatement".equals(method.getName())
                            || "createStatement".equals(method.getName()))
                            && args != null && args.length > 0 && args[0] instanceof String sql) {
                        statements.add(sql);
                    }
                    return invoke(connection, method, args);
                });
    }

    private static Object invoke(Connection target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
