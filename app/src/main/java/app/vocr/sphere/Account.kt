package app.vocr.sphere

import android.content.Context

/**
 * Account and plans scaffold (brief layer "Account and plans"). The account is intentional from day one: it is the
 * foundation for the personalised service Sphere grows into, and every v1 user is on the free plan - all v1 features
 * are free, and the launch says so. The sign-in backend is an open, user-owned decision; until it lands this object
 * holds only local state and the plan rule, so the shell and the folder can be built against a stable shape.
 *
 * The token meter is designed in from the start but charges nothing in v1: [TokenMeter] keeps privacy-respecting
 * local counters only (never leaves the phone, no analytics tool chosen yet - an open brief decision).
 */
object Account {
    enum class Plan { FREE }

    class State(val signedIn: Boolean, val plan: Plan, val label: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sphere", Context.MODE_PRIVATE)

    /** v1: nobody is signed in until the account service exists; the plan rule is already true. */
    fun current(ctx: Context): State =
        State(prefs(ctx).getBoolean("signed_in", false), Plan.FREE, if (prefs(ctx).getBoolean("signed_in", false)) "Free plan" else "Not signed in")
}

/**
 * The usage counts the brief's success measures need (hand-offs per user per week; usage by capture type, format and
 * theme) kept as local counters only. The analytics tool is an open brief decision; until it is chosen and disclosed,
 * these never leave the phone.
 */
object Usage {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sphere_usage", Context.MODE_PRIVATE)

    fun count(ctx: Context, key: String) {
        val p = prefs(ctx)
        p.edit().putLong(key, p.getLong(key, 0) + 1).apply()
        p.edit().putLong("week_" + key + "_" + week(), p.getLong("week_" + key + "_" + week(), 0) + 1).apply()
    }

    fun handoff(ctx: Context) = count(ctx, "handoffs")

    fun all(ctx: Context): Map<String, Long> = prefs(ctx).all.mapValues { it.value as? Long ?: 0 }

    private fun week(): String {
        val c = java.util.Calendar.getInstance()
        return "${c.get(java.util.Calendar.YEAR)}w${c.get(java.util.Calendar.WEEK_OF_YEAR)}"
    }
}
