-- Makes the schema's owner: a role that is not a superuser, which owns the
-- database, everything in it and the journal, runs the migrations, and
-- creates the service's role (database.ServiceRole). Run by the database's
-- superuser, the only thing it is used for, before every migration (the
-- storage-db-roles container, and TestcontainersConfiguration):
--
--   psql -v ON_ERROR_STOP=1 -v owner=reviews_owner -v service=reviews_service -f owner.sql
--
-- with the owner's password in the environment, STORAGE_DB_OWNER_PASSWORD,
-- rather than on the command line. It is idempotent, and sets the password
-- each time, so changing it in .env and running this again rotates it.

\getenv owner_password STORAGE_DB_OWNER_PASSWORD
\if :{?owner_password}
\else
  DO $$ BEGIN RAISE EXCEPTION 'STORAGE_DB_OWNER_PASSWORD is not set'; END $$;
\endif

-- CREATEROLE alone, to create the service's role: since PostgreSQL 16 that
-- manages only the roles it creates (or is given ADMIN on), and grants none
-- of the attributes it lacks, such as SUPERUSER or CREATEDB.
SELECT format('CREATE ROLE %I', :'owner')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'owner') \gexec
SELECT format('ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD %L',
              :'owner', :'owner_password') \gexec

-- Owning the database makes it the owner of the public schema too
-- (pg_database_owner), where the migrations create everything.
SELECT format('ALTER DATABASE %I OWNER TO %I', current_database(), :'owner') \gexec

-- Anything in public owned by another role becomes the owner's: what an
-- earlier layout, where the superuser ran the migrations, created, and what a
-- restore (pg_restore --no-owner, as the superuser) creates. Identity
-- sequences follow their tables.
SELECT format('ALTER TABLE %I.%I OWNER TO %I', n.nspname, c.relname, :'owner')
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
  AND c.relowner <> (SELECT oid FROM pg_roles WHERE rolname = :'owner') \gexec
SELECT format('ALTER SEQUENCE %I.%I OWNER TO %I', n.nspname, c.relname, :'owner')
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'S'
  AND c.relowner <> (SELECT oid FROM pg_roles WHERE rolname = :'owner')
  AND NOT EXISTS (SELECT FROM pg_depend d
                  WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype IN ('a', 'i')) \gexec
SELECT format('ALTER ROUTINE %s OWNER TO %I', p.oid::regprocedure, :'owner')
FROM pg_proc p
WHERE p.pronamespace = 'public'::regnamespace
  AND p.proowner <> (SELECT oid FROM pg_roles WHERE rolname = :'owner') \gexec
SELECT format('ALTER DOMAIN %s OWNER TO %I', t.oid::regtype, :'owner')
FROM pg_type t
WHERE t.typnamespace = 'public'::regnamespace AND t.typtype = 'd'
  AND t.typowner <> (SELECT oid FROM pg_roles WHERE rolname = :'owner') \gexec

-- A service role made before the owner existed (by the superuser) is the
-- owner's to manage too: its password, never its privileges.
SELECT format('GRANT %I TO %I WITH ADMIN OPTION, INHERIT FALSE, SET FALSE', :'service', :'owner')
WHERE EXISTS (SELECT FROM pg_roles WHERE rolname = :'service')
  AND NOT EXISTS (SELECT FROM pg_auth_members m
                  WHERE m.roleid = (SELECT oid FROM pg_roles WHERE rolname = :'service')
                    AND m.member = (SELECT oid FROM pg_roles WHERE rolname = :'owner')
                    AND m.admin_option) \gexec
