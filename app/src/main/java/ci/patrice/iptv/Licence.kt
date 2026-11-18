package ci.patrice.iptv

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Résultat d'une vérification de code d'accès. */
sealed class LicenceResult {
    data class Ok(val label: String) : LicenceResult()
    /** reason : invalid | used | revoked | expired */
    data class Refused(val reason: String) : LicenceResult()
    data class NetworkError(val message: String) : LicenceResult()
}

/**
 * Codes d'accès uniques : 1 code = 1 appareil.
 * Vérifiés en ligne (Supabase, fonction iptv_activate_licence) à chaque ouverture de l'app.
 */
object Licence {
    private const val PREFS = "licence"
    private const val KEY_CODE = "code"
    private const val KEY_LAST_OK = "lastOk"
    private const val KEY_DEVICE = "deviceId"
    /** Tolérance hors ligne : l'app reste utilisable 7 jours sans pouvoir vérifier le code. */
    const val OFFLINE_GRACE_MS = 7L * 24 * 60 * 60 * 1000

    val enabled: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedCode(ctx: Context): String? = prefs(ctx).getString(KEY_CODE, null)
    fun lastOk(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_OK, 0L)

    fun saveOk(ctx: Context, code: String) {
        prefs(ctx).edit().putString(KEY_CODE, code).putLong(KEY_LAST_OK, System.currentTimeMillis()).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_CODE).remove(KEY_LAST_OK).apply()
    }

    /** Identifiant stable de l'appareil (ANDROID_ID), avec repli sur un identifiant aléatoire mémorisé. */
    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") return androidId
        val p = prefs(ctx)
        return p.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            p.edit().putString(KEY_DEVICE, it).apply()
        }
    }

    /** Version courte affichée à l'utilisateur pour le support (ex. 3F9A-C21B). */
    fun shortDeviceId(ctx: Context): String =
        deviceId(ctx).filter { it.isLetterOrDigit() }.takeLast(8).uppercase().chunked(4).joinToString("-")

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Appel réseau : à lancer hors du thread principal. */
    fun check(ctx: Context, code: String): LicenceResult {
        return try {
            val conn = URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/rpc/iptv_activate_licence")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
            val body = JSONObject()
                .put("p_code", code.trim())
                .put("p_device", deviceId(ctx))
                .put("p_device_name", deviceName())
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (status !in 200..299) return LicenceResult.NetworkError("HTTP $status")
            val json = JSONObject(text)
            if (json.optBoolean("ok")) LicenceResult.Ok(json.optString("label"))
            else LicenceResult.Refused(json.optString("reason", "invalid"))
        } catch (e: Exception) {
            LicenceResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }
}
