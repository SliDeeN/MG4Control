package com.mg4.control.hardware

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import com.mg4.control.debug.AppLogger
import com.mg4.control.util.FirmwareInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sonde des écrans, en **lecture seule** : de quoi décider si MG4Control peut afficher quelque
 * chose sur le combiné (étude du 2026-10-14). Elle n'écrit rien, ni sur le véhicule ni à l'écran.
 *
 * Ce que l'on sait des firmwares décompilés : le combiné est un calculateur à part, seule sa zone
 * centrale (260 × 260 px) vient de l'écran central. Sur SWI68 et SWI165, SystemUI y lance l'appli
 * système `com.saicmotor.hmi.clusterprojection` sur l'écran Android n° 1. Sur A9, l'APK manque à
 * la partition système décompilée, mais ses permissions y sont prévues. Quatre questions :
 *  1. **Quels écrans Android existent ?** Numéro, nom, taille, type, drapeaux : l'écran 1 est-il là,
 *     et a-t-il la taille du combiné ?
 *  2. **Qu'y a-t-il dessus ?** Les piles d'activités, écran par écran.
 *  3. **L'appli du combiné est-elle installée**, lancée, signée de la même clé que nous ?
 *  4. **Que demande le combiné ?** Les signaux d'échange de la zone centrale (page, prêt…).
 *
 * Un relevé par ouverture du Diagnostic, gardé dans [reports] pour le rapport.
 */
object DisplayProbe {

    const val TAG = "MG4_DISPLAY"

    /** Quelques ouvertures du Diagnostic : contact mis, Android Auto lancé, navigation en cours… */
    private const val MAX_RELEVES = 4

    private const val AREA_GLOBAL = 0x1000000
    /** Lignes du dump parfois très longues (tous les modes d'un écran) : on coupe au-delà. */
    private const val MAX_LIGNE = 700

    private const val APPLI_COMBINE = "com.saicmotor.hmi.clusterprojection"
    /** Autres paquets dont le nom évoque le combiné : on les signale, installés ou non. */
    private val MOTS_COMBINE = listOf("cluster", "ipk", "instrument", "projection", "meter")

    /** Propriétés système des connecteurs d'affichage (MT2712, relues dans le build.prop A9). */
    private val CONNECTEURS = listOf(
        "ro.disp.connector.primary", "ro.disp.connector.external", "ro.disp.connector.third",
    )

    /** [vpm] : identifiant de la table de l'ancien SDK (SWI133), 0 s'il n'y en a pas. */
    private data class Signal(val name: String, val id: Int, val vpm: Int)

    private val SIGNAUX = listOf(
        Signal("IPK_CNTR_AREA (page demandée par le combiné)", 0x21407b0a, 0x2030011),
        Signal("IPK_FOCUS_AREA", 0x21407b09, 0x2030010),
        Signal("IPK_CLUSTER_TAB_BTN_STS (onglets du combiné)", 0x21407b18, 0x2030013),
        Signal("FICM_CNTR_AREA_STS", 0x21407b04, 0x2040010),
        Signal("FICM_CNTR_AREA_STS_SEND", 0x21407b05, 0x2040011),
        Signal("FICM_APP_READY_STS", 0x21407b01, 0x204000b),
        Signal("FICM_NAVI_READY_STS", 0x21407b16, 0x204000c),
        Signal("FICM_PHONE_READY_STS", 0x21407b17, 0x204000d),
        Signal("FICM_CARD_DSP_MD", 0x21407b24, 0x204001c),
        Signal("FICM_CARD_DSP_TYP", 0x21407b22, 0),
    )

    private val historique = ArrayDeque<String>()

    /** Tous les relevés gardés, du plus ancien au plus récent. */
    val reports: List<String>
        @Synchronized get() = historique.toList()

    /** Relève tout et renvoie le texte du relevé. Hors du fil principal : lectures binder et dump. */
    fun run(origin: String): String {
        val ctx = MG4Hardware.appContext()
        val horloge = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val lignes = mutableListOf<String>()
        lignes += "══ Sonde écrans [$origin] · ${FirmwareInfo.getGeneration()} · horloge $horloge ══"
        if (ctx == null) {
            lignes += "Contexte absent — relevé impossible"
        } else {
            lignes += "Connecteurs : " + CONNECTEURS.joinToString(" · ") { "$it=${propriete(it)}" }
            lignes += "Écrans Android :"
            lignes += ecrans(ctx)
            lignes += "Périphériques d'affichage (dump du service display) :"
            lignes += peripheriques(ctx)
            lignes += "Activités, écran par écran :"
            lignes += piles()
            lignes += "Appli du combiné :"
            lignes += appliCombine(ctx)
            lignes += "Signaux de la zone centrale :"
            lignes += signaux()
        }
        val texte = lignes.joinToString("\n")
        garde(texte)
        AppLogger.i(TAG, texte)
        return texte
    }

    // ── 1. Écrans ────────────────────────────────────────────────────────────

    private fun ecrans(ctx: Context): List<String> {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return listOf("  DisplayManager absent")
        return try {
            val presentation = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .map { it.displayId }
            dm.displays.map { decrireEcran(it) } +
                "  utilisables pour une présentation : ${presentation.ifEmpty { listOf("aucun") }.joinToString()}"
        } catch (e: Exception) {
            listOf("  lecture impossible : ${court(e)}")
        }
    }

    private fun decrireEcran(d: Display): String {
        val taille = Point().also { d.getRealSize(it) }
        val mesures = DisplayMetrics().also { d.getRealMetrics(it) }
        val type = (appel(d, "getType") as? Int)?.let { nomType(it) } ?: "?"
        return "  #${d.displayId} « ${d.name} » ${taille.x} × ${taille.y} px · ${mesures.densityDpi} dpi · " +
            "${"%.0f".format(Locale.US, d.refreshRate)} Hz · état=${nomEtat(d.state)} · type=$type · " +
            "drapeaux=${nomsDrapeaux(d.flags)} · uniqueId=${appel(d, "getUniqueId")} · " +
            "adresse=${appel(d, "getAddress")} · propriétaire=${appel(d, "getOwnerPackageName")}"
    }

    // ── 2. Périphériques (dump) ──────────────────────────────────────────────

    /**
     * Dump du service display, comme `dumpsys display`, mais depuis l'appli : on tourne en uid
     * système, la permission DUMP est acquise. Le dump passe par un fichier du cache — un tube
     * demanderait un second fil pour le vider pendant que le service écrit.
     */
    private fun peripheriques(ctx: Context): List<String> {
        val fichier = File(ctx.cacheDir, "display_probe.txt")
        return try {
            @SuppressLint("PrivateApi")   // ServiceManager : API cachée, lue par réflexion (uid système, voulu).
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "display") as? IBinder
                ?: return listOf("  service display introuvable")
            FileOutputStream(fichier).use { binder.dump(it.fd, emptyArray<String>()) }
            lignesUtiles(fichier.readLines()).ifEmpty { listOf("  dump vide ou format inattendu") }
        } catch (e: Exception) {
            listOf("  dump impossible : ${court(e)}")
        } finally {
            fichier.delete()
        }
    }

    /**
     * Ne garde du dump que la description de chaque périphérique (connecteur, taille, type,
     * drapeaux, propriétaire) et de chaque écran logique ; le reste se compte en centaines de lignes.
     */
    internal fun lignesUtiles(dump: List<String>): List<String> = dump
        .map { it.trim() }
        .filter { it.startsWith("DisplayDeviceInfo{") || it.startsWith("mBaseDisplayInfo=") }
        .map { "  " + if (it.length > MAX_LIGNE) it.take(MAX_LIGNE) + " …" else it }

    // ── 3. Piles d'activités ─────────────────────────────────────────────────

    /** `IActivityManager.getAllStackInfos()` (Android 9, caché) : ce qui tourne sur chaque écran. */
    private fun piles(): List<String> = try {
        @SuppressLint("PrivateApi")   // API cachée d'Android 9, lue par réflexion (uid système, voulu).
        val am = ActivityManager::class.java.getMethod("getService").invoke(null)!!
        val piles = am.javaClass.getMethod("getAllStackInfos").invoke(am) as List<*>
        piles.filterNotNull().map { p ->
            val haut = (champ(p, "topActivity") as? ComponentName)?.flattenToShortString()
            val taches = (champ(p, "taskNames") as? Array<*>)?.joinToString()
            "  écran ${champ(p, "displayId")} · pile ${champ(p, "stackId")} · visible=${champ(p, "visible")} · " +
                "cadre=${champ(p, "bounds")} · haut=$haut · tâches=$taches"
        }.ifEmpty { listOf("  aucune pile") }
    } catch (e: Exception) {
        listOf("  lecture impossible : ${court(e)}")
    }

    // ── 4. Appli du combiné ──────────────────────────────────────────────────

    private fun appliCombine(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val lignes = mutableListOf("  $APPLI_COMBINE : ${paquet(ctx, APPLI_COMBINE)}")
        val autres = try {
            pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { it.packageName }
                .filter { nom -> nom != APPLI_COMBINE && MOTS_COMBINE.any { nom.contains(it, ignoreCase = true) } }
                .sorted()
        } catch (e: Exception) {
            lignes += "  liste des applis illisible : ${court(e)}"
            emptyList()
        }
        autres.forEach { lignes += "  $it : ${paquet(ctx, it)}" }
        if (autres.isEmpty()) lignes += "  aucune autre appli dont le nom évoque le combiné"
        return lignes
    }

    private fun paquet(ctx: Context, nom: String): String {
        val pm = ctx.packageManager
        val info = try {
            pm.getPackageInfo(nom, PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: PackageManager.NameNotFoundException) {
            return "absente"
        } catch (e: Exception) {
            return "illisible (${court(e)})"
        }
        val app = info.applicationInfo
        val origine = when {
            app == null -> "?"
            app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 -> "système mise à jour"
            app.flags and ApplicationInfo.FLAG_SYSTEM != 0 -> "système"
            else -> "installée"
        }
        val cle = if (pm.checkSignatures(ctx.packageName, nom) == PackageManager.SIGNATURE_MATCH)
            "même clé que MG4Control" else "clé différente"
        val lancee = runCatching {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                ?.any { it.processName == nom || it.pkgList?.contains(nom) == true }
        }.getOrNull()
        return "v${info.versionName} (${info.longVersionCode}) · $origine · " +
            "${if (app?.enabled == false) "DÉSACTIVÉE" else "activée"} · $cle · " +
            "processus ${when (lancee) { true -> "lancé"; false -> "arrêté"; null -> "?" }} · ${app?.sourceDir}"
    }

    // ── 5. Signaux du combiné ────────────────────────────────────────────────

    private fun signaux(): List<String> {
        val cpm = MG4Hardware.carPropertyManager()
        val swi133 = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133
        return SIGNAUX.map { s ->
            val voies = mutableListOf("cpm@global=" + lireCpm(cpm, s.id, AREA_GLOBAL), "@0=" + lireCpm(cpm, s.id, 0))
            if (swi133 && s.vpm != 0) voies += "vpm 0x${hex(s.vpm)}=" + MG4Hardware.vpmIntForProbe(s.vpm)
            "  ${s.name} 0x${hex(s.id)} : ${voies.joinToString(" · ")}"
        }
    }

    // ── Noms (purs, testés) ──────────────────────────────────────────────────

    internal fun nomEtat(etat: Int): String = when (etat) {
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "inconnu($etat)"
    }

    /** Valeurs de `Display.TYPE_*`, cachées dans le SDK. */
    internal fun nomType(type: Int): String = when (type) {
        1 -> "BUILT_IN"
        2 -> "HDMI"
        3 -> "WIFI"
        4 -> "OVERLAY"
        5 -> "VIRTUAL"
        else -> "inconnu($type)"
    }

    /** Les deux derniers drapeaux sont cachés dans le SDK. */
    private val DRAPEAUX = listOf(
        Display.FLAG_SUPPORTS_PROTECTED_BUFFERS to "PROTECTED_BUFFERS",
        Display.FLAG_SECURE to "SECURE",
        Display.FLAG_PRIVATE to "PRIVATE",
        Display.FLAG_PRESENTATION to "PRESENTATION",
        Display.FLAG_ROUND to "ROUND",
        0x20 to "CAN_SHOW_WITH_INSECURE_KEYGUARD",
        0x40 to "SCALING_DISABLED",
    )

    internal fun nomsDrapeaux(drapeaux: Int): String {
        val noms = DRAPEAUX.filter { (bit, _) -> drapeaux and bit != 0 }.map { it.second }
        val inconnus = DRAPEAUX.fold(drapeaux) { reste, (bit, _) -> reste and bit.inv() }
        return (noms + if (inconnus != 0) listOf("0x${hex(inconnus)}") else emptyList())
            .ifEmpty { listOf("aucun") }.joinToString("|")
    }

    // ── Lectures ─────────────────────────────────────────────────────────────

    @Synchronized
    private fun garde(texte: String) {
        historique.addLast(texte)
        while (historique.size > MAX_RELEVES) historique.removeFirst()
    }

    private fun lireCpm(cpm: Any?, id: Int, area: Int): String {
        cpm ?: return "cpm absent"
        return try {
            cpm.javaClass.getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, id, area)?.toString() ?: "null"
        } catch (e: Exception) {
            court(e)
        }
    }

    private fun propriete(cle: String): String = try {
        @SuppressLint("PrivateApi")   // SystemProperties : API cachée d'Android, lue par réflexion : l'app tourne en uid système (voulu).
        val sp = Class.forName("android.os.SystemProperties")
        (sp.getMethod("get", String::class.java, String::class.java).invoke(null, cle, "") as? String)
            ?.ifBlank { "absente" } ?: "absente"
    } catch (e: Exception) {
        court(e)
    }

    /** Appel sans argument d'une méthode, cachée le plus souvent : `null` si elle manque. */
    private fun appel(o: Any, methode: String): Any? =
        runCatching { o.javaClass.getMethod(methode).invoke(o) }.getOrNull()

    private fun champ(o: Any, nom: String): Any? =
        runCatching { o.javaClass.getField(nom).get(o) }.getOrNull()

    private fun hex(id: Int) = Integer.toHexString(id)

    /** Message d'échec ramassé : le nom de l'exception suffit à distinguer absente de refusée. */
    private fun court(e: Throwable): String {
        val cause = (e.cause ?: e)
        return cause.javaClass.simpleName + (cause.message?.let { " ($it)" } ?: "")
    }
}
