import { serve } from "https://deno.land/std@0.192.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.33.1";
import { JWT } from "npm:google-auth-library@9.0.0";

// Interface matching the Supabase Webhook payload
interface WebhookPayload {
  type: "INSERT";
  table: string;
  record: {
    id: string;
    recipient_id: string;
    title: string;
    body: string;
    data: any;
  };
}

serve(async (req) => {
  try {
    // 1. Parse the incoming webhook payload from Supabase
    const payload: WebhookPayload = await req.json();
    const notification = payload.record;

    // 2. Initialize Supabase Client (uses internal admin key for security bypass)
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const supabase = createClient(supabaseUrl, supabaseServiceKey);

    // 3. Fetch the recipient's FCM token from the profiles table
    const { data: profile, error: profileError } = await supabase
      .from("profiles")
      .select("fcm_token")
      .eq("id", notification.recipient_id)
      .single();

    if (profileError || !profile?.fcm_token) {
      console.log(`No FCM token found for user: ${notification.recipient_id}`);
      return new Response("No token found, skipped.", { status: 200 });
    }

    // 4. Authenticate with Google using your Firebase Service Account JSON
    const serviceAccountJson = Deno.env.get("FIREBASE_SERVICE_ACCOUNT");
    if (!serviceAccountJson) {
      throw new Error("FIREBASE_SERVICE_ACCOUNT environment variable is missing.");
    }
    
    const serviceAccount = JSON.parse(serviceAccountJson);
    const jwtClient = new JWT({
      email: serviceAccount.client_email,
      key: serviceAccount.private_key.replace(/\\n/g, "\n"),
      scopes: ["https://www.googleapis.com/auth/firebase.messaging"],
    });

    const tokens = await jwtClient.authorize();
    const accessToken = tokens.access_token;

    // 5. Construct the FCM v1 API Request
    const fcmUrl = `https://fcm.googleapis.com/v1/projects/${serviceAccount.project_id}/messages:send`;
    
    // Safely extract variables from the Supabase webhook 'data' column
    const customData = notification.data || {};
    const commentId = customData.comment_id || "";

    const fcmPayload = {
      message: {
        token: profile.fcm_token,
        // Keep the top-level notification block so Android automatically creates the system tray UI
        notification: {
          title: notification.title,
          body: notification.body,
        },
        // Interactive data payload parsed for MainActivity interception
        data: {
          title: notification.title,
          body: notification.body,
          post_id: customData.post_id || "",
          comment_id: commentId,
          action: commentId ? "open_comment" : "open_post",
          author_id: customData.author_id || "",
          // Forwarded so the Android client can suppress self-action notifications
          sender_id: customData.sender_id || "",
          reporter_id: customData.reporter_id || ""
        },
        android: {
          priority: "high",
          notification: {
            // Raw HTTP v1 API requires snake_case "channel_id" 
            channel_id: "agora_notifications_channel"
          }
        }
      },
    };

    // 6. Send the Push Notification
    const fcmResponse = await fetch(fcmUrl, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(fcmPayload),
    });

    const fcmResult = await fcmResponse.json();

    if (!fcmResponse.ok) {
      console.error("FCM Delivery Failed:", fcmResult);
      throw new Error("Failed to send notification via FCM");
    }

    console.log("Notification sent successfully!", fcmResult);
    return new Response(JSON.stringify({ success: true, result: fcmResult }), {
      headers: { "Content-Type": "application/json" },
    });

  } catch (error: any) {
    console.error("Error in Edge Function:", error.message);
    return new Response(JSON.stringify({ error: error.message }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });
  }
});