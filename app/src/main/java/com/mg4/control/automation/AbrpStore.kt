package com.mg4.control.automation

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.control.BuildConfig
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.AbrpAccount
import com.mg4.control.model.AbrpCadence

/**
 * Comptes ABRP (issue #188) et réglages de l'envoi. Fichier de préférences PRIVÉ : les jetons n'en
 * sortent jamais — ni export des automatisations (elles ne citent que l'identifiant du compte), ni
 * diagnostic, ni journal (seuls le nom et les 4 derniers caractères y apparaissent).
 */
object AbrpStore {

    const val MAX_ACCOUNTS = 5

    private const val PREFS = "mg4_abrp"
    private const val KEY_ACCOUNTS = "accounts_json"
    private const val KEY_DRIVING = "cadence_driving_s"
    private const val KEY_CHARGING = "cadence_charging_s"
    private const val KEY_PARKED = "cadence_parked_min"
    private const val KEY_CONSENT = "position_consent"

    private val gson = Gson()
    private val type = object : TypeToken<List<AbrpAccount>>() {}.type
    private val LOCK = Any()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** La clé de MG4Control, injectée au build (`ABRP_API_KEY`) ; vide dans la version hors ligne ou sans clé. */
    val appKey: String get() = BuildConfig.ABRP_API_KEY

    /** La fonction n'existe que dans la version en ligne. */
    val available: Boolean get() = !BuildConfig.OFFLINE

    fun accounts(ctx: Context): List<AbrpAccount> {
        val json = prefs(ctx).getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<AbrpAccount>>(json, type) }
            .onFailure { AppLogger.w(AbrpStream.TAG, "comptes illisibles : ${it.message}") }
            .getOrNull().orEmpty().filterNotNull()
    }

    fun account(ctx: Context, id: String?): AbrpAccount? = id?.let { i -> accounts(ctx).firstOrNull { it.id == i } }

    /** Remplace le compte de même id, ou l'ajoute ; faux si la liste est pleine. */
    fun save(ctx: Context, a: AbrpAccount): Boolean = synchronized(LOCK) {
        val l = accounts(ctx)
        val i = l.indexOfFirst { it.id == a.id }
        val n = when {
            i >= 0 -> l.toMutableList().also { it[i] = a }
            l.size >= MAX_ACCOUNTS -> return false
            else -> l + a
        }
        prefs(ctx).edit().putString(KEY_ACCOUNTS, gson.toJson(n, type)).apply()
        true
    }

    fun delete(ctx: Context, id: String) = synchronized(LOCK) {
        prefs(ctx).edit().putString(KEY_ACCOUNTS, gson.toJson(accounts(ctx).filterNot { it.id == id }, type)).apply()
    }

    /** La clé qui part avec les relevés de ce compte : la sienne, sinon celle de MG4Control. */
    fun keyFor(a: AbrpAccount): String = a.apiKey.trim().ifEmpty { appKey }

    fun cadence(ctx: Context): AbrpCadence = prefs(ctx).let {
        AbrpCadence(
            it.getInt(KEY_DRIVING, AbrpCadence.DEFAULT_DRIVING_S),
            it.getInt(KEY_CHARGING, AbrpCadence.DEFAULT_CHARGING_S),
            it.getInt(KEY_PARKED, AbrpCadence.DEFAULT_PARKED_MIN),
        )
    }

    fun saveCadence(ctx: Context, c: AbrpCadence) {
        prefs(ctx).edit().putInt(KEY_DRIVING, c.drivingSeconds).putInt(KEY_CHARGING, c.chargingSeconds)
            .putInt(KEY_PARKED, c.parkedMinutes).apply()
    }

    /** L'utilisateur a accepté une fois que la position parte chez ABRP (fenêtre au premier compte). */
    fun consent(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CONSENT, false)
    fun setConsent(ctx: Context) = prefs(ctx).edit().putBoolean(KEY_CONSENT, true).apply()
}
