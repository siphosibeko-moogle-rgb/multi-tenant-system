-- Test-only migration, never on the application classpath location.
--
-- FlywayPrimaryBindingControlTest uses this to answer one question and only one:
-- when spring.flyway.url/user/password are absent and two datasources exist,
-- WHICH connection does Boot hand to Flyway?
--
-- It has to be a migration that any role with CREATE on schema public can run.
-- Pointing the control at db/migration instead would confuse the answer with a
-- second question — whether the app role happens to have the rights V1 and V2
-- need — and V2's ALTER ROLE ... NOBYPASSRLS would fail for a non-superuser,
-- making the run fail loudly for a reason that has nothing to do with binding.
CREATE TABLE control_probe (
    id integer
);
