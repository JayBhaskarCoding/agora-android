-- Agora-Backup dashboard configuration managed as schema.
--
-- Before inserting into notifications or posts, create the matching Vault secret:
--   SELECT vault.create_secret('<WEBHOOK_SECRET>', 'webhook_secret',
--     'Shared secret for trusted Agora database-to-function webhooks');
-- The companion setup_agora_config.ps1 script creates/rotates this secret locally.

CREATE EXTENSION IF NOT EXISTS supabase_vault WITH SCHEMA vault;

-- Public buckets are required because the Android client persists public URLs.
INSERT INTO storage.buckets (id, name, public)
VALUES
  ('avatars', 'avatars', true),
  ('post-media', 'post-media', true),
  ('post_images', 'post_images', true)
ON CONFLICT (id) DO UPDATE
SET
  name = EXCLUDED.name,
  public = EXCLUDED.public;

-- Object paths must start with the authenticated user's UUID, for example:
--   <auth.uid()>/<uuid>.webp
-- Public read is intentional because Android stores public URLs in profile/post records.
DROP POLICY IF EXISTS "agora_public_read_avatars" ON storage.objects;
DROP POLICY IF EXISTS "agora_public_read_post_media" ON storage.objects;
DROP POLICY IF EXISTS "agora_public_read_post_images" ON storage.objects;
DROP POLICY IF EXISTS "agora_insert_own_bucket_objects" ON storage.objects;
DROP POLICY IF EXISTS "agora_update_own_bucket_objects" ON storage.objects;
DROP POLICY IF EXISTS "agora_delete_own_bucket_objects" ON storage.objects;

CREATE POLICY "agora_public_read_avatars"
  ON storage.objects FOR SELECT TO public
  USING (bucket_id = 'avatars');

CREATE POLICY "agora_public_read_post_media"
  ON storage.objects FOR SELECT TO public
  USING (bucket_id = 'post-media');

CREATE POLICY "agora_public_read_post_images"
  ON storage.objects FOR SELECT TO public
  USING (bucket_id = 'post_images');

CREATE POLICY "agora_insert_own_bucket_objects"
  ON storage.objects FOR INSERT TO authenticated
  WITH CHECK (
    bucket_id IN ('avatars', 'post-media', 'post_images')
    AND (storage.foldername(name))[1] = (SELECT auth.uid()::text)
  );

CREATE POLICY "agora_update_own_bucket_objects"
  ON storage.objects FOR UPDATE TO authenticated
  USING (
    bucket_id IN ('avatars', 'post-media', 'post_images')
    AND (storage.foldername(name))[1] = (SELECT auth.uid()::text)
  )
  WITH CHECK (
    bucket_id IN ('avatars', 'post-media', 'post_images')
    AND (storage.foldername(name))[1] = (SELECT auth.uid()::text)
  );

CREATE POLICY "agora_delete_own_bucket_objects"
  ON storage.objects FOR DELETE TO authenticated
  USING (
    bucket_id IN ('avatars', 'post-media', 'post_images')
    AND (storage.foldername(name))[1] = (SELECT auth.uid()::text)
  );

-- Add the app's postgres_changes tables individually so the migration is idempotent.
DO $realtime$
DECLARE
  realtime_table text;
BEGIN
  FOREACH realtime_table IN ARRAY ARRAY['profiles', 'posts', 'post_likes', 'comments']
  LOOP
    IF NOT EXISTS (
      SELECT 1
      FROM pg_publication_tables
      WHERE pubname = 'supabase_realtime'
        AND schemaname = 'public'
        AND tablename = realtime_table
    ) THEN
      EXECUTE format(
        'ALTER PUBLICATION supabase_realtime ADD TABLE public.%I',
        realtime_table
      );
    END IF;
  END LOOP;
END;
$realtime$;

-- Recreate the existing trigger functions with a secret sourced at execution time from Vault.
-- This avoids committing a credential in SQL and ensures the secret can be rotated independently.
CREATE OR REPLACE FUNCTION public.broadcast_new_post()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = public, vault, pg_temp
  AS $function$
DECLARE
  webhook_secret text;
BEGIN
  SELECT decrypted_secret
    INTO webhook_secret
    FROM vault.decrypted_secrets
   WHERE name = 'webhook_secret'
   LIMIT 1;

  IF webhook_secret IS NULL OR webhook_secret = '' THEN
    RAISE EXCEPTION 'Vault secret webhook_secret must be configured before post webhooks can run';
  END IF;

  PERFORM net.http_post(
    url := 'https://sepvcatdqrnzjuvxabzh.supabase.co/functions/v1/broadcast-post',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-webhook-secret', webhook_secret
    ),
    body := jsonb_build_object(
      'type', 'INSERT',
      'table', TG_TABLE_NAME,
      'record', row_to_json(NEW)
    )
  );
  RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION public.send_push_notification()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = public, vault, pg_temp
  AS $function$
DECLARE
  webhook_secret text;
BEGIN
  SELECT decrypted_secret
    INTO webhook_secret
    FROM vault.decrypted_secrets
   WHERE name = 'webhook_secret'
   LIMIT 1;

  IF webhook_secret IS NULL OR webhook_secret = '' THEN
    RAISE EXCEPTION 'Vault secret webhook_secret must be configured before notification webhooks can run';
  END IF;

  PERFORM net.http_post(
    url := 'https://sepvcatdqrnzjuvxabzh.supabase.co/functions/v1/push-notification',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-webhook-secret', webhook_secret
    ),
    body := jsonb_build_object(
      'type', 'INSERT',
      'table', TG_TABLE_NAME,
      'record', row_to_json(NEW)
    )
  );
  RETURN NEW;
END;
$function$;
