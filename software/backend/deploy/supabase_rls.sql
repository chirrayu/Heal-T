-- ==============================================================================
-- Heal-T: Supabase Row Level Security (RLS) & Access Control Policies
-- ==============================================================================
-- Safe, robust, and idempotent: Can be executed multiple times in the Supabase
-- SQL Editor without conflict.
--
-- COMPATIBILITY WITH EXISTING APP:
-- 1. Direct SQLAlchemy / FastAPI connections use the `postgres` database owner
--    role via DATABASE_URL. In PostgreSQL, table owners BYPASS RLS by default
--    (since FORCE ROW LEVEL SECURITY is not enabled).
--    The existing FastAPI backend and Android application will continue to work
--    with 100% normal functionality and zero downtime.
-- 2. `service_role` (used by Supabase administrative services, edge functions,
--    and server-side scripts) is granted full permissions on all tables.
-- 3. Sensitive authentication tables (`admin_credentials`, `user_credentials`)
--    are locked down completely against public (anon) and authenticated PostgREST
--    HTTP access to prevent password hash leakage.
-- 4. Operational tables (`squads`, `soldiers`, `vitals`, `alerts`, `locations`,
--    `suit_configs`, `esp32_devices`) permit authenticated read access (SELECT),
--    while enforcing that all state mutations flow through the FastAPI backend.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. Enable RLS on all 9 application tables
-- ------------------------------------------------------------------------------
ALTER TABLE IF EXISTS squads ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS soldiers ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS vitals ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS suit_configs ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS esp32_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS user_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE IF EXISTS admin_credentials ENABLE ROW LEVEL SECURITY;

-- ------------------------------------------------------------------------------
-- 2. Full Access for `service_role` on all tables
-- ------------------------------------------------------------------------------
DO $$
DECLARE
    tbl text;
BEGIN
    FOR tbl IN SELECT unnest(ARRAY[
        'squads', 'soldiers', 'vitals', 'alerts',
        'locations', 'suit_configs', 'esp32_devices',
        'user_credentials', 'admin_credentials'
    ])
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS "service_role_manage_all" ON %I', tbl);
        EXECUTE format('CREATE POLICY "service_role_manage_all" ON %I TO service_role USING (true) WITH CHECK (true)', tbl);
    END LOOP;
END $$;

-- ------------------------------------------------------------------------------
-- 3. Operational Tables: Allow read-only (SELECT) for `authenticated` users
--    (Enables Supabase Auth users / dashboards / Realtime subscriptions to read)
-- ------------------------------------------------------------------------------
DO $$
DECLARE
    tbl text;
BEGIN
    FOR tbl IN SELECT unnest(ARRAY[
        'squads', 'soldiers', 'vitals', 'alerts',
        'locations', 'suit_configs', 'esp32_devices'
    ])
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS "authenticated_read_operational" ON %I', tbl);
        EXECUTE format('CREATE POLICY "authenticated_read_operational" ON %I FOR SELECT TO authenticated USING (true)', tbl);
    END LOOP;
END $$;

-- ------------------------------------------------------------------------------
-- 4. Credential Tables: Strictly isolate `admin_credentials` & `user_credentials`
--    Ensure NO access is granted to `anon` or `authenticated` roles.
-- ------------------------------------------------------------------------------
DROP POLICY IF EXISTS "anon_no_access_admin_creds" ON admin_credentials;
DROP POLICY IF EXISTS "auth_no_access_admin_creds" ON admin_credentials;
DROP POLICY IF EXISTS "anon_no_access_user_creds" ON user_credentials;
DROP POLICY IF EXISTS "auth_no_access_user_creds" ON user_credentials;

-- (With RLS enabled and only service_role policy present, anon & authenticated
--  are rejected by default with 0 rows returned.)

-- ------------------------------------------------------------------------------
-- 5. Optional / Development: If you want anonymous read access during local testing
--    or for public web map previews via Supabase PostgREST, uncomment below:
-- ------------------------------------------------------------------------------
-- DO $$
-- DECLARE
--     tbl text;
-- BEGIN
--     FOR tbl IN SELECT unnest(ARRAY['squads', 'soldiers', 'vitals', 'alerts', 'locations'])
--     LOOP
--         EXECUTE format('DROP POLICY IF EXISTS "anon_read_public_data" ON %I', tbl);
--         EXECUTE format('CREATE POLICY "anon_read_public_data" ON %I FOR SELECT TO anon USING (true)', tbl);
--     END LOOP;
-- END $$;
