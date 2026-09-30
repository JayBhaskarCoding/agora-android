-- 🌟 Reaction timestamps — the reactors bottom sheet must list reactions
-- newest-first, but public.post_likes never carried a time column
-- (post_id, user_id, reaction_type only). Add it with a sane default so
-- every existing row is backfilled at apply time, and index the exact
-- access path used by FeedViewModel.loadReactors():
--   WHERE post_id = $1 ORDER BY created_at DESC LIMIT 50.

ALTER TABLE public.post_likes
  ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();

COMMENT ON COLUMN public.post_likes.created_at IS
  'When the reaction was left. Ordering key for the reactors sheet (newest first).';

CREATE INDEX IF NOT EXISTS post_likes_post_id_created_at_idx
  ON public.post_likes (post_id, created_at DESC);
