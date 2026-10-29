-- The automatic-RLS event trigger is an internal DDL hook, never a client RPC.
-- Revoking its RPC grants leaves automatic RLS and authenticated state saves intact.
DO $$ BEGIN
  IF to_regprocedure('public.rls_auto_enable()') IS NOT NULL THEN
    REVOKE ALL ON FUNCTION public.rls_auto_enable() FROM PUBLIC, anon, authenticated;
  END IF;
END $$;
