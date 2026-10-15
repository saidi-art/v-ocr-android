package app.vocr

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the app's own version and the last crash so a take can be matched to a build and a crash can be diagnosed without a debugger. */
class VocrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Pal.load(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            note("CRASH on thread ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        @Volatile var instance: VocrApp? = null
        private fun file() = instance?.let { File(it.filesDir, "last-error.log") }

        /** "0.10.6-t7 (build 64)" */
        fun version(): String = runCatching {
            val c = instance!!; val pi = c.packageManager.getPackageInfo(c.packageName, 0)
            "${pi.versionName} (build ${if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
        }.getOrDefault("unknown")

        /** Writes the newest error (overwrites; one entry is enough and nothing is sent anywhere). */
        fun note(what: String, e: Throwable?) {
            runCatching {
                val f = file() ?: return
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val trace = e?.let { android.util.Log.getStackTraceString(it).lines().take(14).joinToString("\n") } ?: ""
                f.writeText("$stamp  $what  ${version()}\n$trace\n")
            }
        }

        private fun stopFile() = instance?.let { File(it.filesDir, "last-stop.log") }
        /** Why the last auto-scroll ended (bottom, time cap, user, error), so a missed or early stop can be explained. Separate from the crash log. */
        fun noteStop(what: String) { runCatching { val f = stopFile() ?: return; f.writeText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "  " + what + "\n") } }
        fun lastStop(): String? = runCatching { stopFile()?.takeIf { it.exists() }?.readText()?.take(400) }.getOrNull()
        /** True when the system animation scale is 0 (Reduce motion / animations off): skip springs and slides. */
        fun reducedMotion(): Boolean = runCatching { android.provider.Settings.Global.getFloat(instance!!.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

        fun lastError(): String? = runCatching { file()?.takeIf { it.exists() }?.readText()?.take(1800) }.getOrNull()
    }
}
