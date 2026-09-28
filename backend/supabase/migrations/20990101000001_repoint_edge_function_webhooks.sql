-- Repoint pg_net trigger callbacks from the retired Agora project to Agora-Backup.
-- The target Edge Functions are configured with verify_jwt = false because they are invoked
-- from database triggers. Do not put project-specific anon keys in these function definitions.
-- If webhook authentication is added later, read a secret from Vault and validate it in the
-- corresponding Edge Functions instead of committing a bearer token here.

CREATE OR REPLACE FUNCTION public.broadcast_new_post()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  AS $function$
BEGIN
  PERFORM net.http_post(
    url := 'https://sepvcatdqrnzjuvxabzh.supabase.co/functions/v1/broadcast-post',
    headers := jsonb_build_object(
      'Content-Type', 'application/json'
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
  AS $function$
BEGIN
  PERFORM net.http_post(
    url := 'https://sepvcatdqrnzjuvxabzh.supabase.co/functions/v1/push-notification',
    headers := jsonb_build_object(
      'Content-Type', 'application/json'
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
