package com.example.inventory.auth;

import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.CurrentUser;
import com.example.inventory.auth.AuthDtos.TenantRegistrationRequest;
import com.example.inventory.auth.AuthDtos.TenantSummary;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.ConflictException;

/**
 * Creates a tenant and its first owner — the only write path in the system that
 * runs before any token for that tenant can exist.
 *
 * <h2>Where the tenant id comes from</h2>
 *
 * <p>{@link #newTenantId}, and nowhere else. It is a {@link Supplier} purely so
 * that a test can force a collision (see {@code TenantRegistrationTest}); in
 * production it is {@code UUID::randomUUID}. It is <strong>never</strong> read
 * from the request — {@link TenantRegistrationRequest} has no field for one, so
 * a client that sends {@code tenantId} anyway is ignored by Jackson.
 *
 * <p>This matters more than it might look. If a caller could choose the id, they
 * could name an <em>existing</em> tenant, and since the registration transaction
 * binds {@code app.tenant_id} to that id, the {@code users} insert that follows
 * would satisfy {@code WITH CHECK (tenant_id = current_tenant_id())} — the
 * attacker would have inserted themselves an owner account inside somebody
 * else's business, with RLS's full blessing. The database cannot stop this,
 * because at that point the request is indistinguishable from a legitimate one.
 * Server-side generation is the entire defence.
 *
 * <h2>Why the tenants row goes in first</h2>
 *
 * <p>Ordering is what makes the above fail safe rather than fail open. The
 * {@code tenants} insert carries the generated id as its primary key, so an id
 * that already exists collides and the transaction aborts <em>before</em> a
 * single user row is written. Even in the hypothetical where an id did reach
 * this method from outside, the worst outcome is a rejected request rather than
 * an account planted in another tenant.
 *
 * <p>{@code TenantRegistrationTest.pkCollisionAbortsBeforeAnyUserRowIsWritten}
 * pins the ordering down, because it is the kind of thing a later refactor
 * reorders without noticing.
 *
 * <h2>This is the documented exception to T1</h2>
 *
 * <p>Every other code path binds a tenant from a verified {@code tid} claim via
 * {@code TenantFilter}. This one binds an id it just generated, because there is
 * no token yet and cannot be. See CLAUDE.md section 12. No other class may call
 * {@link TenantContext#bindForRegistration}.
 */
@Service
public class TenantRegistrationService {

    private final JdbcTemplate appJdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final Supplier<UUID> newTenantId;

    /**
     * The production constructor. Annotated because the test seam below gives
     * this class two, and Spring will not choose between them unaided.
     */
    @Autowired
    public TenantRegistrationService(@Qualifier("appDataSource") DataSource appDataSource,
                                     TransactionTemplate transactions,
                                     PasswordEncoder passwordEncoder,
                                     TokenIssuer tokenIssuer) {
        this(appDataSource, transactions, passwordEncoder, tokenIssuer, UUID::randomUUID);
    }

    /** Test seam. The supplier exists to force a collision, nothing else. */
    TenantRegistrationService(DataSource appDataSource,
                              TransactionTemplate transactions,
                              PasswordEncoder passwordEncoder,
                              TokenIssuer tokenIssuer,
                              Supplier<UUID> newTenantId) {
        this.appJdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.newTenantId = newTenantId;
    }

    public AuthTokens register(TenantRegistrationRequest request) {
        UUID tenantId = newTenantId.get();

        // Bound BEFORE the transaction opens, not inside it. TenantConnectionProvider
        // applies app.tenant_id when the connection is checked out, and the
        // checkout happens as the transaction begins — binding after that point
        // would leave the transaction running unbound, and every insert below
        // would be refused by WITH CHECK.
        TenantContext.bindForRegistration(tenantId);
        try {
            return transactions.execute(status -> createTenantAndOwner(tenantId, request));
        } finally {
            TenantContext.clear();
        }
    }

    private AuthTokens createTenantAndOwner(UUID tenantId, TenantRegistrationRequest request) {
        // ---- 1. The tenant. FIRST, always. See the class javadoc. ----
        try {
            appJdbc.update("""
                    INSERT INTO tenants (id, slug, name, status, currency_code, timezone)
                    VALUES (?, ?, ?, 'active', ?, ?)
                    """,
                    tenantId,
                    request.slug(),
                    request.businessName(),
                    request.currencyCodeOrDefault(),
                    request.timezoneOrDefault());
        } catch (DuplicateKeyException e) {
            // Two different collisions arrive here and they mean opposite things.
            //
            // A slug collision is ordinary: somebody already registered that
            // name. 409 is the documented answer, and it does disclose that the
            // slug is taken — a decision on record, see CLAUDE.md section 12.
            //
            // A primary key collision means the generated UUID already existed,
            // which for a v4 UUID is not something that happens by chance. It is
            // reported the same way rather than distinguished, because telling a
            // caller which of the two occurred would confirm the existence of a
            // tenant id they guessed.
            throw new ConflictException(
                    "A business with that slug already exists", "tenant-slug-taken");
        }

        // ---- 2. The owner. Only now, and only inside the same transaction. ----
        UUID userId = UUID.randomUUID();
        appJdbc.update("""
                INSERT INTO users (id, tenant_id, email, password_hash, full_name, role, status)
                VALUES (?, ?, ?, ?, ?, 'owner', 'active')
                """,
                userId,
                tenantId,
                request.ownerEmail(),
                passwordEncoder.encode(request.ownerPassword()),
                request.ownerName());

        // ---- 3. A default location, so the business can hold stock. ----
        //
        // Without this a new tenant is dead on arrival: every stock movement
        // needs a location, and every endpoint that takes one falls back to the
        // tenant's default. Registration created none, so the very first
        // adjustment a new business attempted was refused, and the only way
        // forward was to discover POST /locations and call it by hand. Found on
        // the emulator, where the first adjustment after registering failed.
        //
        // Same transaction as the tenant and the owner, and it satisfies the
        // locations policy on its own terms — WITH CHECK (tenant_id =
        // current_tenant_id()) — because app.tenant_id is bound to the id
        // generated above. No new privilege and no fourth role.
        //
        // "Main" is a starting point rather than a guess at the business's
        // vocabulary: it is renameable through PATCH /locations, and a shop with
        // one till never has to think about locations at all.
        UUID locationId = UUID.randomUUID();
        appJdbc.update("""
                INSERT INTO locations (id, tenant_id, name, is_default, is_active)
                VALUES (?, ?, 'Main', true, true)
                """,
                locationId,
                tenantId);

        CurrentUser user = new CurrentUser(
                userId,
                request.ownerEmail(),
                request.ownerName(),
                "owner",
                new TenantSummary(tenantId, request.slug(), request.businessName()),
                request.currencyCodeOrDefault(),
                request.timezoneOrDefault(),
                // Now populated for a freshly registered tenant. The contract
                // still marks it nullable, and that stays correct: tenants
                // registered before this change have no default location, and
                // nothing stops one being deactivated later.
                locationId);

        return tokenIssuer.issueFor(tenantId, userId, "owner", user, null);
    }
}
