package app.vocr.sphere

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject

/**
 * The account service scaffold (v1 requirement: the account is the foundation for the personalised service).
 * Firebase Auth + Google sign-in, intentionally WITHOUT the google-services gradle plugin: the development build
 * stays fully usable with no configuration, and Firebase initialises only when a local, never-committed
 * `app/src/main/assets/google-services.json` exists. Until then every surface honestly reports [Status.NotConnected].
 *
 * What the backend sees once connected: account identifiers only (email / UID / provider tokens). Captures never
 * leave the phone. No Analytics product is enabled. (To be verified against the real integration once approved.)
 */
object AccountService {
    sealed interface Status {
        /** No configuration: this build has no account backend wired in. */
        object NotConnected : Status
        /** Configured, nobody signed in. */
        object SignedOut : Status
        class SignedIn(val label: String) : Status
    }

    class Config(val projectId: String, val appId: String, val apiKey: String, val webClientId: String?)

    @Volatile private var app: FirebaseApp? = null
    @Volatile private var config: Config? = null

    /** Parses a google-services.json body; null when required fields are absent. Pure: unit-tested without Firebase. */
    fun parseConfig(json: String): Config? = runCatching {
        val root = JSONObject(json)
        val projectId = root.getJSONObject("project_info").getString("project_id")
        val client = root.getJSONArray("client").getJSONObject(0)
        val appId = client.getJSONObject("client_info").getString("mobilesdk_app_id")
        val apiKey = client.getJSONArray("api_key").getJSONObject(0).getString("current_key")
        var webId: String? = null
        val oauth = client.optJSONArray("oauth_client")
        if (oauth != null) for (i in 0 until oauth.length()) {
            val o = oauth.getJSONObject(i)
            if (o.optInt("client_type") == 3) webId = o.optString("client_id").ifEmpty { null }
        }
        Config(projectId, appId, apiKey, webId)
    }.getOrNull()

    private fun config(ctx: Context): Config? {
        config?.let { return it }
        val parsed = runCatching { ctx.assets.open("google-services.json").bufferedReader().readText() }.getOrNull()
            ?.let { parseConfig(it) } ?: return null
        config = parsed
        return parsed
    }

    @Synchronized private fun ensureApp(ctx: Context): FirebaseApp? {
        app?.let { return it }
        val c = config(ctx) ?: return null
        val existing = FirebaseApp.getApps(ctx).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        val created = existing ?: runCatching {
            FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder().setProjectId(c.projectId).setApplicationId(c.appId).setApiKey(c.apiKey).build())
        }.getOrNull() ?: return null
        app = created
        return created
    }

    fun auth(ctx: Context): FirebaseAuth? = ensureApp(ctx)?.let { runCatching { FirebaseAuth.getInstance(it) }.getOrNull() }

    /** The web (server) client ID Google sign-in needs to mint a Firebase credential; null when not configured. */
    fun webClientId(ctx: Context): String? = config(ctx)?.webClientId

    fun status(ctx: Context): Status {
        val a = auth(ctx) ?: return Status.NotConnected
        val u = a.currentUser ?: return Status.SignedOut
        return Status.SignedIn(u.email ?: u.displayName ?: "Signed in")
    }

    fun signOut(ctx: Context) { auth(ctx)?.signOut() }
}
