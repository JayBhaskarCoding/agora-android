SET local check_function_bodies = off;

CREATE EXTENSION "pg_net" SCHEMA "public";

CREATE TABLE "public"."comments" (
  "id"         uuid                     NOT NULL DEFAULT gen_random_uuid(),
  "post_id"    uuid                     NOT NULL,
  "user_id"    uuid                     NOT NULL,
  "content"    text                     NOT NULL,
  "created_at" timestamp with time zone DEFAULT now(),
  CONSTRAINT "comments_pkey" PRIMARY KEY (id)
);

ALTER TABLE "public"."comments"
  ENABLE ROW LEVEL SECURITY;

CREATE TABLE "public"."notifications" (
  "id"           uuid                     NOT NULL DEFAULT gen_random_uuid(),
  "created_at"   timestamp with time zone DEFAULT now(),
  "recipient_id" uuid                     NOT NULL,
  "title"        text                     NOT NULL,
  "body"         text                     NOT NULL,
  "data"         jsonb                    DEFAULT '{}'::jsonb,
  CONSTRAINT "notifications_pkey" PRIMARY KEY (id)
);

ALTER TABLE "public"."notifications"
  ENABLE ROW LEVEL SECURITY;

CREATE TABLE "public"."post_likes" (
  "post_id"       uuid NOT NULL,
  "user_id"       uuid NOT NULL,
  "reaction_type" text NOT NULL DEFAULT '❤️'::text,
  CONSTRAINT "post_likes_pkey" PRIMARY KEY (post_id, user_id)
);

ALTER TABLE "public"."post_likes"
  ENABLE ROW LEVEL SECURITY;

CREATE TABLE "public"."posts" (
  "id"         uuid                     NOT NULL DEFAULT gen_random_uuid(),
  "user_id"    uuid                     NOT NULL,
  "content"    text                     NOT NULL,
  "likes"      integer                  DEFAULT 0,
  "comments"   integer                  DEFAULT 0,
  "created_at" timestamp with time zone DEFAULT now(),
  "image_urls" text[]                   DEFAULT '{}'::text[],
  "media_urls" text[],
  CONSTRAINT "posts_pkey" PRIMARY KEY (id)
);

ALTER TABLE "public"."posts"
  ENABLE ROW LEVEL SECURITY;

CREATE TABLE "public"."profiles" (
  "id"                  uuid                     NOT NULL,
  "handle"              text,
  "avatar_url"          text,
  "first_name"          text                     DEFAULT 'User'::text,
  "last_name"           text                     DEFAULT ''::text,
  "gender"              text,
  "dob"                 date,
  "active_session_id"   text,
  "password_changed_at" timestamp with time zone,
  "email"               text,
  "fcm_token"           text,
  "current_device_id"   text,
  CONSTRAINT "profiles_handle_key" UNIQUE (handle),
  CONSTRAINT "profiles_pkey" PRIMARY KEY (id)
);

ALTER TABLE "public"."profiles"
  ENABLE ROW LEVEL SECURITY;

ALTER TABLE "public"."profiles"
  REPLICA IDENTITY FULL;

CREATE TABLE "public"."reports" (
  "id"          uuid                     NOT NULL DEFAULT gen_random_uuid(),
  "post_id"     uuid                     NOT NULL,
  "reporter_id" uuid                     NOT NULL,
  "reason"      text                     NOT NULL,
  "created_at"  timestamp with time zone DEFAULT now(),
  CONSTRAINT "reports_pkey" PRIMARY KEY (id)
);

ALTER TABLE "public"."reports"
  ENABLE ROW LEVEL SECURITY;

CREATE OR REPLACE FUNCTION public.broadcast_new_post()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  AS $function$
BEGIN
  PERFORM net.http_post(
    url := 'https://gbvwcvsmgxtpjqrocnrq.supabase.co/functions/v1/broadcast-post',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImdidndjdnNtZ3h0cGpxcm9jbnJxIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODc2ODg4ODYsImV4cCI6MjEwMzI2NDg4Nn0.tfw0_B2lCgBiIJVmKcb9c2BYZ3bjhI59t40fIF89hTk' 
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

CREATE OR REPLACE FUNCTION public.delete_abandoned_user()
  RETURNS void
  LANGUAGE plpgsql
  SECURITY DEFINER
  AS $function$
BEGIN
  -- Deletes the active user from auth.users (cascades automatically to public.profiles)
  DELETE FROM auth.users
  WHERE id = auth.uid();
END;
$function$;

CREATE OR REPLACE FUNCTION public.handle_new_user()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path TO ''
  AS $function$
begin
  insert into public.profiles (id, first_name, last_name, handle)
  values (
    new.id,
    new.raw_user_meta_data ->> 'first_name',
    new.raw_user_meta_data ->> 'last_name',
    new.raw_user_meta_data ->> 'handle'
  );
  return new;
end;
$function$;

CREATE OR REPLACE FUNCTION public.send_push_notification()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  AS $function$
BEGIN
  PERFORM net.http_post(
    url := 'https://gbvwcvsmgxtpjqrocnrq.supabase.co/functions/v1/push-notification',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer sb_publishable_A180oobjeJBbwDQoMeOyWw_eAYr2cub'
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

CREATE OR REPLACE FUNCTION public.update_post_comment_count()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  AS $function$
begin
  if (TG_OP = 'INSERT') then
    update posts set comments = comments + 1 where id = new.post_id;
    return new;
  elsif (TG_OP = 'DELETE') then
    update posts set comments = comments - 1 where id = old.post_id;
    return old;
  end if;
end;
$function$;

CREATE OR REPLACE FUNCTION public.update_post_counts()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  AS $function$
begin
  if (TG_TABLE_NAME = 'post_likes') then
    if (TG_OP = 'INSERT') then
      update posts set likes = likes + 1 where id = new.post_id;
      return new;
    elsif (TG_OP = 'DELETE') then
      update posts set likes = likes - 1 where id = old.post_id;
      return old;
    end if;
  elsif (TG_TABLE_NAME = 'comments') then
    if (TG_OP = 'INSERT') then
      update posts set comments = comments + 1 where id = new.post_id;
      return new;
    elsif (TG_OP = 'DELETE') then
      update posts set comments = comments - 1 where id = old.post_id;
      return old;
    end if;
  end if;
  return null;
end;
$function$;

CREATE OR REPLACE FUNCTION public.update_post_like_count()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  AS $function$
begin
  if (TG_OP = 'INSERT') then
    update posts set likes = likes + 1 where id = new.post_id;
    return new;
  elsif (TG_OP = 'DELETE') then
    update posts set likes = likes - 1 where id = old.post_id;
    return old;
  end if;
end;
$function$;

ALTER TABLE "public"."comments"
  ADD CONSTRAINT "comments_post_id_fkey" FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE CASCADE;

ALTER TABLE "public"."post_likes"
  ADD CONSTRAINT "post_likes_post_id_fkey" FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE CASCADE;

ALTER TABLE "public"."profiles"
  ADD CONSTRAINT "profiles_id_fkey" FOREIGN KEY (id) REFERENCES auth.users(id) ON DELETE CASCADE;

ALTER TABLE "public"."comments"
  ADD CONSTRAINT "comments_user_id_fkey" FOREIGN KEY (user_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

ALTER TABLE "public"."notifications"
  ADD CONSTRAINT "notifications_recipient_id_fkey" FOREIGN KEY (recipient_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

ALTER TABLE "public"."post_likes"
  ADD CONSTRAINT "post_likes_user_id_fkey" FOREIGN KEY (user_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

ALTER TABLE "public"."posts"
  ADD CONSTRAINT "fk_posts_user_id" FOREIGN KEY (user_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

ALTER TABLE "public"."posts"
  ADD CONSTRAINT "posts_user_id_fkey" FOREIGN KEY (user_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

ALTER TABLE "public"."reports"
  ADD CONSTRAINT "reports_post_id_fkey" FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE CASCADE;

ALTER TABLE "public"."reports"
  ADD CONSTRAINT "reports_reporter_id_fkey" FOREIGN KEY (reporter_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

CREATE INDEX idx_comments_post_id ON public.comments USING btree (post_id);

CREATE INDEX idx_likes_post_id ON public.post_likes USING btree (post_id);

CREATE INDEX idx_posts_created_at ON public.posts USING btree (created_at DESC);

CREATE INDEX idx_profiles_active_session_id ON public.profiles USING btree (active_session_id);

CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW
  EXECUTE FUNCTION public.handle_new_user();

CREATE TRIGGER trigger_comments_count
  AFTER INSERT OR DELETE ON public.comments
  FOR EACH ROW
  EXECUTE FUNCTION public.update_post_counts();

CREATE TRIGGER on_notification_created
  AFTER INSERT ON public.notifications
  FOR EACH ROW
  EXECUTE FUNCTION public.send_push_notification();

CREATE TRIGGER trigger_likes_count
  AFTER INSERT OR DELETE ON public.post_likes
  FOR EACH ROW
  EXECUTE FUNCTION public.update_post_counts();

CREATE TRIGGER on_post_created
  AFTER INSERT ON public.posts
  FOR EACH ROW
  EXECUTE FUNCTION public.broadcast_new_post();

CREATE POLICY "Auth users insert comments" ON "public"."comments"
  FOR INSERT
  TO "authenticated"
  WITH CHECK ((auth.uid() = user_id));

CREATE POLICY "Public comments access" ON "public"."comments"
  FOR SELECT
  TO PUBLIC
  USING (true);

CREATE POLICY "Allow authenticated users to insert notifications" ON "public"."notifications"
  FOR INSERT
  TO "authenticated"
  WITH CHECK (true);

CREATE POLICY "Auth users manage likes" ON "public"."post_likes"
  FOR ALL
  TO "authenticated"
  USING ((auth.uid() = user_id));

CREATE POLICY "Public likes access" ON "public"."post_likes"
  FOR SELECT
  TO PUBLIC
  USING (true);

CREATE POLICY "Auth users insert posts" ON "public"."posts"
  FOR INSERT
  TO "authenticated"
  WITH CHECK ((auth.uid() = user_id));

CREATE POLICY "Public posts access" ON "public"."posts"
  FOR SELECT
  TO PUBLIC
  USING (true);

CREATE POLICY "Users can update own posts" ON "public"."posts"
  FOR UPDATE
  TO PUBLIC
  USING ((auth.uid() = user_id));

CREATE POLICY "Users delete own posts" ON "public"."posts"
  FOR DELETE
  TO PUBLIC
  USING ((auth.uid() = user_id));

CREATE POLICY "Profiles are viewable by everyone" ON "public"."profiles"
  FOR SELECT
  TO PUBLIC
  USING (true);

CREATE POLICY "Public profiles" ON "public"."profiles"
  FOR SELECT
  TO PUBLIC
  USING (true);

CREATE POLICY "Users can update own profile" ON "public"."profiles"
  FOR UPDATE
  TO PUBLIC
  USING ((auth.uid() = id));

CREATE POLICY "Users can update their own profile" ON "public"."profiles"
  FOR UPDATE
  TO PUBLIC
  USING ((auth.uid() = id));

CREATE POLICY "Users can view their own profile" ON "public"."profiles"
  FOR SELECT
  TO PUBLIC
  USING ((auth.uid() = id));

CREATE POLICY "Auth users can insert reports" ON "public"."reports"
  FOR INSERT
  TO "authenticated"
  WITH CHECK ((auth.uid() = reporter_id));

CREATE POLICY "Allow authenticated updates to avatars" ON "storage"."objects"
  FOR UPDATE
  TO "authenticated"
  USING ((bucket_id = 'avatars'::text));

CREATE POLICY "Allow authenticated uploads to avatars" ON "storage"."objects"
  FOR INSERT
  TO "authenticated"
  WITH CHECK ((bucket_id = 'avatars'::text));

CREATE POLICY "Allow authenticated uploads to post_images" ON "storage"."objects"
  FOR INSERT
  TO "authenticated"
  WITH CHECK ((bucket_id = 'post_images'::text));

ALTER PUBLICATION "supabase_realtime" ADD TABLE "public"."comments";

ALTER PUBLICATION "supabase_realtime" ADD TABLE "public"."notifications";

ALTER PUBLICATION "supabase_realtime" ADD TABLE "public"."post_likes";

ALTER PUBLICATION "supabase_realtime" ADD TABLE "public"."posts";

ALTER PUBLICATION "supabase_realtime" ADD TABLE "public"."profiles";

COMMENT ON EXTENSION "pg_net" IS 'Async HTTP';

GRANT EXECUTE ON FUNCTION "public"."broadcast_new_post"() TO PUBLIC, "anon", "authenticated", "postgres", "service_role";

GRANT EXECUTE ON FUNCTION "public"."delete_abandoned_user"() TO PUBLIC, "anon", "authenticated", "postgres", "service_role";

GRANT EXECUTE ON FUNCTION "public"."handle_new_user"() TO PUBLIC, "anon", "authenticated", "postgres", "service_role";

GRANT EXECUTE ON FUNCTION "public"."send_push_notification"() TO PUBLIC, "anon", "authenticated", "postgres", "service_role";

GRANT EXECUTE ON FUNCTION "public"."update_post_comment_count"() TO PUBLIC, "anon", "authenticated", "postgres", "service_role";

GRANT EXECUTE ON FUNCTION "public"."update_post_counts"() TO PUBLIC, "anon", "authenticated", "postgres", "service_role";

GRANT EXECUTE ON FUNCTION "public"."update_post_like_count"() TO PUBLIC, "anon", "authenticated", "postgres", "service_role";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."comments" TO "anon", "authenticated", "postgres", "service_role";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."notifications" TO "anon", "authenticated", "postgres", "service_role";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."post_likes" TO "anon", "authenticated", "postgres", "service_role";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."posts" TO "anon", "authenticated", "postgres", "service_role";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."profiles" TO "anon", "authenticated", "postgres", "service_role";

GRANT DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE ON TABLE "public"."reports" TO "anon", "authenticated", "postgres", "service_role";

