# Multi-tenant Inventory Platform

A multi-tenant inventory management platform for SMEs. Many separate businesses
share one deployment, and each one's data is invisible to every other.

See docs/MILESTONES.md for the delivery plan.

## Layout

```
multi-tenant-system/

├── README.md
├── docker-compose.yml        PostgreSQL 16 for local development
├── inventory-backend/        Spring Boot 4.1, Java 21 — the whole product
├── android/                  Kotlin + Jetpack Compose client (M3)
└── docs/
    ├── openapi.yaml          the API contract, source of truth
    ├── MILESTONES.md
    └── PROJECT_STRUCTURE.md  package layout and rationale
```

`inventory-platform/` holds only Eclipse workspace state and is untracked.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21 | The Boot 4.1 parent defaults to 17, so the build sets 21 explicitly. |
| Docker | running daemon | Required for both the local database and the tests. There is no H2 fallback — see CLAUDE.md T10. |
| Maven | none needed | Use the bundled `./mvnw`. |

Check the daemon is up before anything else:

```bash
docker info >/dev/null && echo "docker ok"
```

If it is not, `sudo systemctl start docker`.

## Running everything

All commands are run from the repository root unless stated otherwise.

### 1. Start PostgreSQL

```bash
docker compose up -d db
```

Wait for it to report healthy:

```bash
docker compose ps db
```

### 2. Build, test and check coverage

```bash
cd inventory-backend
./mvnw clean verify
```

This compiles, starts a throwaway PostgreSQL 16 via Testcontainers, applies every
migration against it, runs the integration tests and enforces the JaCoCo gate.
The coverage report lands in `target/site/jacoco/index.html`.

> The gate is set to 0% for M0 on purpose — there is no business logic to measure
> yet. Raise `jacoco.line.coverage.minimum` in `pom.xml` during M1.

### 3. Run the application

```bash
cd inventory-backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Then, from another shell:

```bash
curl -s localhost:8080/actuator/health
```

Expected: `{"status":"UP",...}` with a `db` component also `UP`.

### Other useful commands

```bash
cd inventory-backend

./mvnw test                              # tests only, no coverage gate
./mvnw test -Dtest=SchemaSmokeTest       # a single test class
./mvnw flyway:info                       # migration state (needs the db up)

docker compose logs -f db                # database logs
docker compose down                      # stop, keep data
docker compose down -v                   # stop and delete the volume
```

Dropping the volume and starting again rebuilds the schema from scratch with no
manual steps — that is one of M0's acceptance criteria.

## Configuration

No credentials are committed. Every value is an environment variable; the `local`
profile supplies local-only defaults that match `docker-compose.yml`, and `prod`
supplies none at all, so a missing variable fails the application at startup
rather than silently pointing it somewhere wrong.

| Variable | Default (local only) | Purpose |
|----------|----------------------|---------|
| `POSTGRES_DB` | `inventory` | Database name |
| `POSTGRES_USER` | `inventory_owner` | Schema owner; Flyway connects as this |
| `POSTGRES_PASSWORD` | `local_dev_only` | Owner password |
| `POSTGRES_PORT` | `5432` | Host port for the container |
| `DB_URL` | `jdbc:postgresql://localhost:5432/inventory` | JDBC URL |
| `DB_APP_USER` | `inventory_app` | Application role — **must not be a superuser** |
| `DB_APP_PASSWORD` | `app_local_only` | Application role password — see below |
| `DB_MIGRATION_USER` | `inventory_owner` | Role Flyway runs migrations as |
| `DB_MIGRATION_PASSWORD` | `local_dev_only` | Migration role password |

Profiles: `local` (default), `test` (integration tests), `prod`.

### The two database roles

This split is load-bearing, not decoration.

- **`inventory_owner`** owns the schema and is the only role Flyway connects as.
  It runs DDL.
- **`inventory_app`** is what the running application connects as.
  `V2__app_role.sql` creates it `NOSUPERUSER NOBYPASSRLS`, grants it DML, and then
  revokes `UPDATE` and `DELETE` on `stock_movements`.

#### The application role's password has one source

Two separate Flyway configurations create and then use that role — Spring's
(`application-local.yml`) and the Maven plugin's (`pom.xml`, for
`flyway:migrate` / `flyway:info`). If they disagree, `V2` creates the role with
one password and the application connects with another.

So the literal lives in exactly one place: the `<app.db.password>` property in
`inventory-backend/pom.xml`. The plugin reads it directly; `application-local.yml`
reads it as `@app.db.password@`, which Maven substitutes at build time. Override
it either way — both consumers follow:

```bash
./mvnw flyway:migrate -Dapp.db.password=somethingelse   # Maven property
DB_APP_PASSWORD=somethingelse ./mvnw flyway:migrate     # environment variable
```

Do not write the password into `application-local.yml`. A second copy is how the
two drift apart.

Pointing `DB_APP_USER` at the owner, or at any superuser or `BYPASSRLS` role,
turns every row-level security policy in the schema into a no-op and removes
tenant isolation completely, without any error being raised (CLAUDE.md T2).
`DB_APP_PASSWORD` is also fed to Flyway as a placeholder so V2 can create the
role with the same password the application will use.

## Migrations

Flyway migrations live in
`inventory-backend/src/main/resources/db/migration/`.

| Migration | Contents |
|-----------|----------|
| `V1__baseline.sql` | Schema, RLS policies, ledger triggers |
| `V2__app_role.sql` | The `inventory_app` role, its grants, and the ledger revoke |

Never edit an applied migration — add `V{n+1}__description.sql` instead
(CLAUDE.md T9). Views belong in `R__views.sql` so they can be replaced in place.

## Tests

Integration tests extend `AbstractIntegrationTest`, which starts PostgreSQL 16
through Testcontainers. Flyway runs against it as the owner; the application
datasource connects as `inventory_app`, so tests exercise the same restricted
role production uses.

`SchemaSmokeTest` asserts that all migrations applied, that every tenant-scoped
table has row-level security both **enabled** and **forced**, that each carries
the `tenant_isolation` policy, that the application role is neither a superuser
nor `BYPASSRLS`, and that the ledger is append-only for it.

### Container reuse is off, on purpose

Each run starts a fresh container. That is a deliberate choice, not an oversight:
reuse shares one database across runs, and because these tests seed tenants, the
first cross-run collision would surface as a *tenant isolation failure*. That is
the most expensive false alarm this codebase can produce — the correct response
to a real one is auditing every query written since M1 — and it is not worth the
few seconds of startup time.

Tests must not collide within a single run either, since one container is shared
by every test class. Take tenant ids from `AbstractIntegrationTest.newTenantId()`
rather than hard-coding UUID literals, never assert on a fixed tenant id, and
never assume your tenant is the only one in the database.

If you want reuse locally anyway, it is opt-in per machine:

```bash
echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
```

Nothing in the build asks for it, so this alone will not enable it — the
container would also need `.withReuse(true)` restored in
`AbstractIntegrationTest`. Please read the paragraph above before doing that.

## Milestone status

M0 (skeleton and pipeline) is complete. M1 adds tenancy and authentication —
`TenantContext`, `TenantFilter`, `TenantConnectionProvider`, JWT, and the
`spring-boot-starter-security` dependency, which is deliberately absent until
there is security configuration to go with it.
