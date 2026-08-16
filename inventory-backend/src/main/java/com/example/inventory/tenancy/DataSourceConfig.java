package com.example.inventory.tenancy;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The two connection pools M1 runs on.
 *
 * <table border="1">
 * <caption>Pools</caption>
 * <tr><th>Bean</th><th>Role</th><th>Sees</th><th>Used by</th></tr>
 * <tr>
 *   <td>{@code appDataSource} ({@code @Primary})</td>
 *   <td>{@code inventory_app}</td>
 *   <td>one tenant, whichever {@code app.tenant_id} names</td>
 *   <td>everything: JPA, every repository, registration</td>
 * </tr>
 * <tr>
 *   <td>{@code loginDataSource}</td>
 *   <td>{@code inventory_login}</td>
 *   <td>{@code tenants} and {@code users}, unscoped, read-only</td>
 *   <td>login and tenant resolution, nothing else</td>
 * </tr>
 * </table>
 *
 * <p>Migrations are on neither of them. Flyway holds a third connection of its
 * own, as the schema owner, configured by {@code spring.flyway.url/user/password}.
 *
 * <h2>Why the login pool exists</h2>
 *
 * <p>Authentication is a chicken-and-egg problem under RLS. The user supplies an
 * email; the tenant is what the lookup is meant to <em>discover</em>. On the
 * application pool with nothing bound, {@code current_tenant_id()} is NULL, so
 * the {@code users} policy matches no rows and login can never succeed. V3
 * answers that with a role that holds SELECT on exactly {@code tenants} and
 * {@code users}, plus a {@code login_read} policy scoped {@code TO inventory_login}
 * — so the unscoped read exists for this pool and for nothing else in the
 * system. See {@code V3__login_role.sql} for the full reasoning.
 *
 * <h2>Defining both pools is not optional</h2>
 *
 * <p>Boot's {@code DataSourceAutoConfiguration} is {@code @ConditionalOnMissingBean(DataSource.class)}.
 * Declaring the login pool therefore switches the automatic one off <em>entirely</em>,
 * which is why {@code appDataSource} is built by hand here rather than left to
 * Boot. It still binds the same {@code spring.datasource.*} and
 * {@code spring.datasource.hikari.*} properties, so nothing about its
 * configuration moves — but deleting this class would leave the application with
 * no primary datasource at all, not with the Boot default.
 *
 * <p>{@code @Primary} on the app pool is what keeps JPA, {@code JdbcTemplate} and
 * every future repository on the RLS-bound connection by default.
 *
 * <h2>Injecting the login pool requires {@code @Qualifier}</h2>
 *
 * <pre>{@code
 * @Autowired DataSource loginDataSource;                      // WRONG — the app pool
 * @Autowired @Qualifier("loginDataSource") DataSource login;  // right
 * }</pre>
 *
 * <p>Spring resolves a dependency by type, then consults {@code @Primary}, and
 * falls back to matching the field or parameter name only if no primary candidate
 * exists. One does. So naming the field {@code loginDataSource} and omitting the
 * qualifier silently yields the application pool.
 *
 * <p>The resulting bug is quiet, in the way everything in this area is quiet:
 * login queries would run on the RLS-bound connection with no tenant bound, the
 * {@code users} lookup would match zero rows, and every authentication attempt
 * would fail as "unknown email" — including the correct ones. Nothing throws.
 * {@code LoginRoleTest} asserts {@code current_user} on the injected pool for
 * exactly this reason, and caught this mistake once already.
 *
 * <p>The same rule applies to the {@code @Bean} methods below, which is subtler
 * and bit this class during its first run: {@code loginDataSource} takes a
 * {@link DataSourceProperties} parameter, and without a qualifier that parameter
 * resolves to the {@code @Primary} <em>application</em> properties bean. The
 * login pool was then built from the app role's URL, username and password — a
 * second application pool wearing the login pool's name, with no error anywhere.
 * Every {@code DataSourceProperties} parameter here is qualified explicitly.
 *
 * <h2>This class is exactly the hazard CLAUDE.md section 9 warns about</h2>
 *
 * <p>With two {@link DataSource} beans present, Boot would bind Flyway to the
 * {@code @Primary} one — {@code inventory_app}, which has no DDL rights and is
 * subject to every policy in the schema. The explicit
 * {@code spring.flyway.url/user/password} properties are what prevent it, and
 * their absence fails silently rather than loudly: Flyway connects happily and
 * records {@code installed_by = inventory_app}. Do not remove them on the
 * grounds that a datasource is already configured. {@code FlywayBindingTest}
 * (this config, asserting the owner) and {@code FlywayPrimaryBindingControlTest}
 * (the same config without the properties, asserting the app role) hold the line
 * in both directions.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    // ------------------------------------------------------------------
    // The application pool — RLS-bound, one tenant at a time
    // ------------------------------------------------------------------

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties appDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource appDataSource(
            @Qualifier("appDataSourceProperties") DataSourceProperties appDataSourceProperties) {
        return appDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    // ------------------------------------------------------------------
    // The login pool — unscoped, read-only, two tables
    // ------------------------------------------------------------------

    /**
     * Bound to {@code app.datasource.login.*} rather than a second key under
     * {@code spring.datasource}, which names Boot's own primary datasource and
     * would be claimed by autoconfiguration instead of by this class.
     */
    @Bean
    @ConfigurationProperties("app.datasource.login")
    DataSourceProperties loginDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * The login pool.
     *
     * <p>Never bind {@code app.tenant_id} on a connection from here. It would do
     * nothing useful — {@code login_read} already returns every row to this role
     * — and it would suggest to the next reader that this pool is tenant-scoped
     * when it is the one pool in the system that deliberately is not.
     */
    @Bean
    @ConfigurationProperties("app.datasource.login.hikari")
    HikariDataSource loginDataSource(
            @Qualifier("loginDataSourceProperties") DataSourceProperties loginDataSourceProperties) {
        return loginDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
