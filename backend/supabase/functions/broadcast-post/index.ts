import { serve } from "https://deno.land/std@0.192.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.33.1";
import { JWT } from "npm:google-auth-library@9.0.0";

interface WebhookPayload {
  type: "INSERT";
  table: string;
  record: {
    id: string;
    user_id: string; // The author's ID
    content?: string;
  };
}

serve(async (req) => {
  try {
    const payload: WebhookPayload = await req.json();
    const post = payload.record;
    const postText = post.content || "A new post was added!";

    // 1. Fetch the author's name from Supabase
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const supabaseKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const supabase = createClient(supabaseUrl, supabaseKey);

    const { data: profile } = await supabase
      .from("profiles")
      .select("first_name") // Change to 'name' or 'display_name' depending on your DB
      .eq("id", post.user_id)
      .single();

    const authorName = profile?.first_name || "A user";

    // 2. Authenticate with Google
    const serviceAccountJson = Deno.env.get("FIREBASE_SERVICE_ACCOUNT");
    const serviceAccount = JSON.parse(serviceAccountJson!);
    const jwtClient = new JWT({
      email: serviceAccount.client_email,
      key: serviceAccount.private_key.replace(/\\n/g, "\n"),
      scopes: ["https://www.googleapis.com/auth/firebase.messaging"],
    });
    const tokens = await jwtClient.authorize();

    // 3. Send formatted Topic Broadcast
    const fcmUrl = `https://fcm.googleapis.com/v1/projects/${serviceAccount.project_id}/messages:send`;
    const fcmPayload = {
      message: {
        topic: "new_posts",
        data: {
          title: `${authorName} just added a new post`,
          body: postText.substring(0, 40) + (postText.length > 40 ? "..." : ""),
          author_id: post.user_id,
          // Route the notification tap straight to this post (agora://post/{post_id})
          post_id: post.id
        },
        android: {
          // High priority so data-only messages are delivered promptly to
          // backgrounded apps (otherwise they are deferred by Android).
          priority: "high"
        }
      }
    };

    await fetch(fcmUrl, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tokens.access_token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(fcmPayload),
    });

    return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });

  } catch (error: any) {
    return new Response(JSON.stringify({ error: error.message }), { status: 500 });
  }
});