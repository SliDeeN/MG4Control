package com.mg4.control.util

import android.content.Context
import com.mg4.control.model.AverageUnit
import com.mg4.control.model.ClusterConfig
import com.mg4.control.model.ClusterGaugeStyle
import com.mg4.control.model.ClusterLayout
import com.mg4.control.model.ClusterLayouts
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterModuleDef
import com.mg4.control.model.ClusterModuleDefs
import com.mg4.control.model.ClusterRotation
import com.mg4.control.model.ClusterPill
import com.mg4.control.model.ClusterTab
import com.mg4.control.model.ConsumptionUnit
import com.mg4.control.model.DistanceUnit
import com.mg4.control.model.PowerScale
import kotlin.math.roundToInt

/**
 * Réglage « Module sur le combiné » (Réglages › Interface) : un interrupteur, désactivé par
 * défaut, et un module par onglet du combiné — Navigation et Musique, les seuls qui montrent
 * l'écran central (2026-10-14). Relu à chaque relevé : en changer vaut tout de suite, sans
 * réafficher la fenêtre.
 *
 * Depuis le 2026-10-24, ces réglages appartiennent à chaque profil ([ClusterConfig]) : les lectures
 * à partir d'un `Context` rendent ceux du profil que suit le combiné ([ClusterProfiles.effective]).
 * Les clés ci-dessous ne gardent que les réglages communs d'avant : repris par chaque profil à la
 * mise à jour, ils ne valent plus que tant qu'il n'existe aucun profil.
 */
object ClusterModuleSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    const val KEY_ENABLED = "cluster_module_enabled"
    /** Ancien réglage unique, d'avant le choix par onglet : relu pour la navigation seulement. */
    const val KEY_MODULE = "cluster_module"
    const val KEY_TAB_NAVIGATION = "cluster_module_tab_navigation"
    const val KEY_TAB_MEDIA = "cluster_module_tab_media"
    /** Valeur enregistrée pour « Aucun » : l'onglet garde sa page d'origine. */
    const val NONE = "none"

    /** Onglets qui montrent l'écran central ; les autres sont dessinés par le combiné lui-même. */
    val TABS = listOf(ClusterTab.NAVIGATION, ClusterTab.MEDIA)

    /**
     * Firmwares où le combiné montre l'image de l'écran central : SWI133 (vu en voiture le
     * 2026-10-14), SWI68 et SWI165 (même appli SAIC du combiné, lancée par SystemUI). Acceptés
     * d'office ; ailleurs, c'est la présence de l'appli qui alimente le combiné qui décide ([IMAGE_APPS]).
     */
    val SUPPORTED = setOf(FirmwareInfo.Gen.SWI133, FirmwareInfo.Gen.SWI68, FirmwareInfo.Gen.SWI165)

    /**
     * Applis SAIC qui donnent au combiné l'image de l'écran central (issue #231, vérifié sur les
     * extractions le 2026-11-09) : `clusterprojection` sur SWI133, SWI68, SWI165 ; `caripk` sur SWI173
     * (A9 : elle annonce au combiné une image prête — ce qui manquait à l'essai SWI69). Absentes de
     * SWI69, SWI131 et SWI132, où rien ne s'affiche. Une finition inconnue qui en a une a donc les
     * onglets Navigation / Musique avec l'image de l'écran central.
     */
    val IMAGE_APPS = listOf("com.saicmotor.hmi.clusterprojection", "com.saicmotor.caripk")

    /** Appli trouvée, « » si aucune ; lue une fois (les applis système ne changent pas en route). */
    @Volatile private var appliImage: String? = null

    /** Le paquet de l'appli qui alimente le combiné, ou `null`. */
    fun imageApp(): String? {
        appliImage?.let { return it.ifEmpty { null } }
        val ctx = com.mg4.control.hardware.MG4Hardware.appContext() ?: return null
        val trouvee = IMAGE_APPS.firstOrNull { p -> runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isSuccess }
        appliImage = trouvee ?: ""
        com.mg4.control.debug.AppLogger.i("MG4_CLUSTER", "chaîne d'image du combiné : ${trouvee ?: "aucune appli SAIC"} " +
            "(firmware ${FirmwareInfo.getGeneration()}) → écran conducteur ${if (supportedOn(FirmwareInfo.getGeneration(), trouvee != null)) "proposé" else "non proposé"}")
        return trouvee
    }

    fun isSupported(): Boolean = supportedOn(FirmwareInfo.getGeneration(), imageApp() != null)
    const val KEY_REFRESH = "cluster_refresh_ms"
    /** Unité de la consommation (2026-10-15), Wh/km par défaut. */
    const val KEY_UNIT = "cluster_consumption_unit"
    /**
     * Unité de distance de tout le module : km, miles (2026-10-23) ou « auto » = comme la voiture
     * — le défaut quand rien n'est enregistré, depuis le même jour.
     */
    const val KEY_DISTANCE = "cluster_distance_unit"

    /**
     * Actualisation de la puissance, de la vitesse et de la jauge, en ms : 0,1 à 1 s par pas de
     * 0,1 s. Une seconde par défaut (2026-10-15) : le chiffre défile maintenant d'une valeur à
     * l'autre pendant 80 % de l'intervalle, et à 1 s on le suit bien (0,5 s du 2026-10-14 au 15).
     */
    const val MIN_REFRESH_MS = 100
    const val MAX_REFRESH_MS = 1000
    const val STEP_REFRESH_MS = 100
    const val DEFAULT_REFRESH_MS = 1000

    /** Style des modules (issue #153) : dynamique par défaut, l'affichage d'avant. */
    const val KEY_STYLE = "cluster_gauge_style"
    /** Unité de la moyenne du trajet (2026-10-17) : sans choix, celle de la jauge. */
    const val KEY_AVG_UNIT = "cluster_average_unit"

    /** Pastille en haut à gauche (issue #152) : profil actif par défaut. */
    const val KEY_PILL = "cluster_pill"

    /** Disposition du module (issue #229), en JSON ; absente = celle d'avant. Sans aucun profil seulement. */
    const val KEY_LAYOUT = "cluster_layout"
    /** Disposition de l'onglet Musique, sans profil (2026-11-20). */
    const val KEY_LAYOUT_MEDIA = "cluster_layout_media"
    /** Modules de chaque onglet, sans profil (2026-11-20) : identifiants séparés par des virgules. */
    const val KEY_NAV_MODULES = "cluster_nav_modules"
    const val KEY_MEDIA_MODULES = "cluster_media_modules"

    /**
     * Durée du glissement de la jauge d'une valeur à la suivante (issue #152), en ms : 0,2 à 2 s
     * par pas de 0,1 s, à part de l'actualisation. Avant, elle valait 80 % de celle-ci (50 à
     * 800 ms) : une actualisation rapide imposait une animation sèche. 0,8 s par défaut, ce que
     * donnait l'actualisation par défaut (1 s). Plus longue que l'actualisation, la jauge lisse :
     * chaque relevé repart de ce qui est à l'écran.
     */
    const val KEY_ANIMATION = "cluster_animation_ms"
    const val KEY_POWER_MAX = "cluster_power_max"
    const val MIN_ANIMATION_MS = 200
    const val MAX_ANIMATION_MS = 2000
    const val STEP_ANIMATION_MS = 100
    const val DEFAULT_ANIMATION_MS = 800

    // ── Réglages du profil que suit le combiné, relus à chaque relevé ────────

    fun isEnabled(ctx: Context?): Boolean = isEnabled(ClusterProfiles.effective(ctx))

    /**
     * Type du module montré sur [tab] (2026-11-20 : celui de la liste du profil, au rang du raccourci),
     * ou `null` sans module et pour un onglet dessiné par le combiné.
     */
    fun moduleFor(ctx: Context?, tab: ClusterTab): ClusterModule? = shownDef(ctx, tab)?.let(ClusterModuleDefs::module)

    /** Module montré sur [tab] pour le profil que suit le combiné. */
    fun shownDef(ctx: Context?, tab: ClusterTab): ClusterModuleDef? {
        val c = ctx ?: return null
        val liste = modulesFor(c, tab)
        if (liste.isEmpty()) return null
        val i = synchronized(rotation) { rotation.index(ClusterProfiles.shownProfileId(c), tab, liste.size) }
        return liste[i]
    }

    /** Modules de [tab] pour le profil que suit le combiné, dans l'ordre. */
    fun modulesFor(ctx: Context, tab: ClusterTab): List<ClusterModuleDef> =
        ClusterModuleDefs.listFor(ClusterProfiles.effective(ctx), tab, library(ctx))

    /** Rang du module montré, par profil et par onglet ; perdu au redémarrage de l'écran. */
    val rotation = ClusterRotation()

    /** Raccourci « Module suivant » : passe au module suivant de [tab] ; le nouveau module, ou `null`. */
    fun nextModule(ctx: Context, tab: ClusterTab): ClusterModuleDef? {
        val liste = modulesFor(ctx, tab)
        if (liste.isEmpty()) return null
        val i = synchronized(rotation) { rotation.next(ClusterProfiles.shownProfileId(ctx), tab, liste.size) }
        return liste[i]
    }

    /** Unité de consommation du module montré sur [tab]. */
    fun consumptionUnit(ctx: Context?, tab: ClusterTab): ConsumptionUnit =
        ConsumptionUnit.fromKey(shownDef(ctx, tab)?.unit)

    fun style(ctx: Context?, tab: ClusterTab): ClusterGaugeStyle = ClusterGaugeStyle.fromKey(shownDef(ctx, tab)?.style)

    /** Unité de la moyenne par défaut : celle du module montré. */
    fun averageUnit(ctx: Context?, tab: ClusterTab): AverageUnit = AverageUnit.of(consumptionUnit(ctx, tab))

    // ── Bibliothèque des modules (2026-11-20), commune à tous les profils ────

    const val KEY_LIBRARY = "cluster_module_library"
    @Volatile private var biblioBrute: String? = null
    @Volatile private var biblio: List<ClusterModuleDef> = emptyList()

    /** Les modules créés, dans l'ordre ; décodés une fois tant que rien ne change (relu à chaque relevé). */
    fun library(ctx: Context): List<ClusterModuleDef> {
        val brut = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIBRARY, null)
        if (brut == biblioBrute) return biblio
        val liste = brut?.let { j ->
            runCatching { com.google.gson.Gson().fromJson(j, Array<ClusterModuleDef>::class.java).toList() }.getOrNull()
        }.orEmpty().filter { it.id.isNotBlank() }
        biblio = liste
        biblioBrute = brut
        return liste
    }

    fun saveLibrary(ctx: Context, liste: List<ClusterModuleDef>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LIBRARY, com.google.gson.Gson().toJson(liste)).apply()
    }

    fun hasLibrary(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_LIBRARY)

    // ── Réglages communs (2026-11-20) : la voiture, pas un module ni un profil ──

    const val KEY_COMMON_REFRESH = "cluster_common_refresh_ms"
    const val KEY_COMMON_ANIMATION = "cluster_common_animation_ms"
    /** Puissance maxi choisie (kW) ; absente = Auto. */
    const val KEY_COMMON_POWER_MAX = "cluster_common_power_max"

    fun refreshMs(ctx: Context?): Int = sanitizeRefresh(
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.takeIf { it.contains(KEY_COMMON_REFRESH) }?.getInt(KEY_COMMON_REFRESH, DEFAULT_REFRESH_MS)
            ?: ClusterProfiles.effective(ctx).refreshMs)

    fun animationMs(ctx: Context?): Int = sanitizeAnimation(
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.takeIf { it.contains(KEY_COMMON_ANIMATION) }?.getInt(KEY_COMMON_ANIMATION, DEFAULT_ANIMATION_MS)
            ?: ClusterProfiles.effective(ctx).animationMs)

    /** Puissance maxi choisie, ou `null` pour Auto. */
    fun powerMaxChoice(ctx: Context?): Int? {
        val p = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return null
        val v = if (p.contains(KEY_COMMON_POWER_MAX)) p.getInt(KEY_COMMON_POWER_MAX, 0) else ClusterProfiles.effective(ctx).powerMax
        return v?.takeIf { it in PowerScale.CHOIX_KW }
    }

    fun setRefreshMs(ctx: Context, ms: Int) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_COMMON_REFRESH, sanitizeRefresh(ms)).apply()
    fun setAnimationMs(ctx: Context, ms: Int) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_COMMON_ANIMATION, sanitizeAnimation(ms)).apply()
    fun setPowerMax(ctx: Context, kw: Int?) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .apply { if (kw == null) putInt(KEY_COMMON_POWER_MAX, 0) else putInt(KEY_COMMON_POWER_MAX, kw) }.apply()

    /** Halo de freinage (issue #278) : coupé par défaut, intensité Moyenne. Tous modules, tous profils. */
    const val KEY_COMMON_BRAKE_GLOW = "cluster_common_brake_glow"
    const val KEY_COMMON_BRAKE_INTENSITY = "cluster_common_brake_intensity"

    fun brakeGlowOn(ctx: Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY_COMMON_BRAKE_GLOW, false) ?: false
    fun setBrakeGlowOn(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_COMMON_BRAKE_GLOW, on).apply()
    fun brakeIntensity(ctx: Context?): com.mg4.control.model.BrakeGlow.Intensity = com.mg4.control.model.BrakeGlow.Intensity.fromKey(
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_COMMON_BRAKE_INTENSITY, null))
    fun setBrakeIntensity(ctx: Context, i: com.mg4.control.model.BrakeGlow.Intensity) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_COMMON_BRAKE_INTENSITY, i.key).apply()

    /**
     * Unité de distance : le réglage général de l'appli depuis le 2026-11-10 ([DistanceSetting]),
     * qui a repris le choix fait ici. En Auto, relit la voiture (binder, au plus une fois par minute).
     */
    fun distanceUnit(ctx: Context?): DistanceUnit = DistanceSetting.unitReading(ctx)

    fun pill(ctx: Context?): ClusterPill = pill(ClusterProfiles.effective(ctx))

    fun layout(ctx: Context?): ClusterLayout = layout(ClusterProfiles.effective(ctx))

    /** Disposition de l'onglet [tab] (2026-11-20). */
    fun layout(ctx: Context?, tab: ClusterTab?): ClusterLayout = layout(ClusterProfiles.effective(ctx), tab)

    // ── Fin de l'échelle de puissance (issue #165) ───────────────────────────
    // Le CHOIX appartient au profil comme le reste de la carte ; le plafond APPRIS est celui de la
    // voiture, commun à tous les profils.

    const val KEY_POWER_LEARNED = "cluster_power_learned"
    const val KEY_POWER_PEAKS = "cluster_power_peaks"

    /** Fin de l'échelle : la puissance maxi commune, ou Auto (apprise). */
    fun fullScaleKw(ctx: Context?): Float = PowerScale.pleineEchelle(powerMaxChoice(ctx), learnedPowerKw(ctx))

    fun fullScaleKw(c: ClusterConfig, appris: Int): Float = PowerScale.pleineEchelle(powerMaxChoice(c), appris)

    /** Puissance maxi choisie, ou `null` pour Auto. */
    fun powerMaxChoice(c: ClusterConfig): Int? = c.powerMax?.takeIf { it in PowerScale.CHOIX_KW }

    fun learnedPowerKw(ctx: Context?): Int =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getInt(KEY_POWER_LEARNED, PowerScale.DEPART_KW)
            ?.takeIf { it > 0 } ?: PowerScale.DEPART_KW

    /** Pointes des derniers trajets, une par trajet. */
    fun powerPeaks(ctx: Context): List<Float> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_POWER_PEAKS, null).orEmpty()
            .split(',').mapNotNull { it.trim().toFloatOrNull() }

    fun saveLearnedPower(ctx: Context, plafond: Int, pointes: List<Float>? = null) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt(KEY_POWER_LEARNED, plafond)
            // Nombres pour machine : toujours le point décimal (piège de la virgule sur la voiture).
            pointes?.let { putString(KEY_POWER_PEAKS, it.joinToString(",") { p -> p.roundToInt().toString() }) }
        }.apply()
    }

    // ── Réglages communs d'avant les profils ─────────────────────────────────

    /** Les réglages communs, tels qu'enregistrés avant le 2026-10-24 (ou sans aucun profil). */
    fun legacy(ctx: Context): ClusterConfig {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return legacyConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            navigation = p.getString(KEY_TAB_NAVIGATION, null),
            media = p.getString(KEY_TAB_MEDIA, null),
            legacyModule = p.getString(KEY_MODULE, null),
            unit = p.getString(KEY_UNIT, null),
            distance = p.getString(KEY_DISTANCE, null),
            style = p.getString(KEY_STYLE, null),
            average = p.getString(KEY_AVG_UNIT, null),
            pill = p.getString(KEY_PILL, null),
            refreshMs = if (p.contains(KEY_REFRESH)) p.getInt(KEY_REFRESH, DEFAULT_REFRESH_MS) else null,
            animationMs = if (p.contains(KEY_ANIMATION)) p.getInt(KEY_ANIMATION, DEFAULT_ANIMATION_MS) else null,
        ).copy(
            // Puissance maxi (issue #165) : oubliée ici jusqu'au 2026-11-14 — sans profil, le choix revenait à Auto.
            powerMax = if (p.contains(KEY_POWER_MAX)) p.getInt(KEY_POWER_MAX, 0) else null,
            layout = p.getString(KEY_LAYOUT, null)?.let { j ->
            runCatching { com.google.gson.Gson().fromJson(j, ClusterLayout::class.java) }.getOrNull()
        },
            layoutMedia = p.getString(KEY_LAYOUT_MEDIA, null)?.let { j ->
                runCatching { com.google.gson.Gson().fromJson(j, ClusterLayout::class.java) }.getOrNull()
            },
            navModules = p.getString(KEY_NAV_MODULES, null)?.split(',')?.filter { it.isNotBlank() },
            mediaModules = p.getString(KEY_MEDIA_MODULES, null)?.split(',')?.filter { it.isNotBlank() })
    }

    /** Sans aucun profil, la carte règle les réglages communs : ils sont réécrits en entier. */
    fun saveLegacy(ctx: Context, c: ClusterConfig) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean(KEY_ENABLED, c.enabled == true)
            putString(KEY_TAB_NAVIGATION, c.navigation)
            putString(KEY_TAB_MEDIA, c.media)
            putString(KEY_UNIT, c.unit)
            putString(KEY_DISTANCE, c.distance)
            putString(KEY_STYLE, c.style)
            putString(KEY_AVG_UNIT, c.average)
            putString(KEY_PILL, c.pill)
            c.refreshMs?.let { putInt(KEY_REFRESH, it) } ?: remove(KEY_REFRESH)
            c.animationMs?.let { putInt(KEY_ANIMATION, it) } ?: remove(KEY_ANIMATION)
            c.powerMax?.let { putInt(KEY_POWER_MAX, it) } ?: remove(KEY_POWER_MAX)
            c.layout?.let { putString(KEY_LAYOUT, com.google.gson.Gson().toJson(it)) } ?: remove(KEY_LAYOUT)
            c.layoutMedia?.let { putString(KEY_LAYOUT_MEDIA, com.google.gson.Gson().toJson(it)) } ?: remove(KEY_LAYOUT_MEDIA)
            c.navModules?.let { putString(KEY_NAV_MODULES, it.joinToString(",")) } ?: remove(KEY_NAV_MODULES)
            c.mediaModules?.let { putString(KEY_MEDIA_MODULES, it.joinToString(",")) } ?: remove(KEY_MEDIA_MODULES)
        }.apply()
    }

    // ── Sans Android ─────────────────────────────────────────────────────────

    /** Firmware validé, ou appli SAIC du combiné présente ([hasImageApp]) — issue #231. */
    fun supportedOn(gen: FirmwareInfo.Gen, hasImageApp: Boolean = false): Boolean = gen in SUPPORTED || hasImageApp

    fun isEnabled(c: ClusterConfig): Boolean = c.enabled == true

    fun moduleFor(c: ClusterConfig, tab: ClusterTab): ClusterModule? = when (tab) {
        ClusterTab.NAVIGATION -> resolveTab(tab, c.navigation, null)
        ClusterTab.MEDIA -> resolveTab(tab, c.media, null)
        else -> null
    }

    fun consumptionUnit(c: ClusterConfig): ConsumptionUnit = ConsumptionUnit.fromKey(c.unit)

    fun refreshMs(c: ClusterConfig): Int = sanitizeRefresh(c.refreshMs)

    fun style(c: ClusterConfig): ClusterGaugeStyle = ClusterGaugeStyle.fromKey(c.style)

    fun averageUnit(c: ClusterConfig): AverageUnit = AverageUnit.resolve(c.average, consumptionUnit(c))

    fun pill(c: ClusterConfig): ClusterPill = ClusterPill.fromKey(c.pill)

    /** Disposition du module (issue #229) : celle enregistrée, sinon celle d'avant. */
    fun layout(c: ClusterConfig): ClusterLayout = ClusterLayouts.of(c)

    fun layout(c: ClusterConfig, tab: ClusterTab?): ClusterLayout = ClusterLayouts.of(c, tab)

    /**
     * Onglet dont on montre le module ([moduleToShow]) : sa disposition suit. Onglet illisible : la
     * navigation si elle a un module, sinon la musique.
     */
    fun tabShown(tabValue: Int?, navigation: ClusterModule?): ClusterTab? = when (tabValue) {
        null -> if (navigation != null) ClusterTab.NAVIGATION else ClusterTab.MEDIA
        ClusterTab.NAVIGATION.value -> ClusterTab.NAVIGATION
        ClusterTab.MEDIA.value -> ClusterTab.MEDIA
        else -> null
    }

    fun animationMs(c: ClusterConfig): Int = sanitizeAnimation(c.animationMs)

    /** [c] avec [module] pour [tab] (`null` = Aucun) ; un onglet sans module possible ne change rien. */
    fun withModule(c: ClusterConfig, tab: ClusterTab, module: ClusterModule?): ClusterConfig = when (tab) {
        ClusterTab.NAVIGATION -> c.copy(navigation = module?.key ?: NONE)
        ClusterTab.MEDIA -> c.copy(media = module?.key ?: NONE)
        else -> c
    }

    /**
     * Réglages communs d'avant les profils, rendus dans la forme d'un profil. Le module de chaque
     * onglet est résolu une fois pour toutes, l'ancien réglage unique ([legacyModule]) compris :
     * le profil n'a pas à le connaître.
     */
    fun legacyConfig(
        enabled: Boolean, navigation: String?, media: String?, legacyModule: String?,
        unit: String?, distance: String?, style: String?, average: String?, pill: String?,
        refreshMs: Int?, animationMs: Int?,
    ): ClusterConfig = ClusterConfig(
        enabled = enabled,
        navigation = resolveTab(ClusterTab.NAVIGATION, navigation, legacyModule)?.key ?: NONE,
        media = resolveTab(ClusterTab.MEDIA, media, null)?.key ?: NONE,
        unit = unit, distance = distance, style = style, average = average, pill = pill,
        refreshMs = refreshMs, animationMs = animationMs,
    )

    /**
     * Module d'un onglet d'après ce qui est enregistré. Sans choix pour lui : l'ancien réglage
     * unique ([legacy]) vaut pour la navigation, où le module s'affichait jusque-là, et rien pour
     * la musique.
     */
    fun resolveTab(tab: ClusterTab, stored: String?, legacy: String?): ClusterModule? = when {
        tab !in TABS -> null
        stored == NONE -> null
        stored != null -> ClusterModule.entries.firstOrNull { it.key == stored }
        tab == ClusterTab.NAVIGATION -> ClusterModule.fromKey(legacy)
        else -> null
    }

    /**
     * Module à montrer pour l'onglet [tabValue] choisi au volant (`IPK_CLUSTER_TAB_BTN_STS`), ou
     * `null` pour laisser la page d'origine — ou le combiné dessiner la sienne. Onglet illisible :
     * celui de la navigation, à défaut celui de la musique, plutôt qu'un module qui disparaît.
     */
    /**
     * Onglet à suivre quand la lecture vient d'échouer (issue #245, 2026-11-13) : le dernier onglet
     * lu, s'il date de moins de [ONGLET_GARDE_MS]. Sans lui, le secours de [moduleToShow] posait le
     * module de la navigation sur la page affichée, Musique comprise. Au-delà (onglet jamais lu, ou
     * illisible depuis longtemps), `null` : le secours reprend, pour qu'un module ne disparaisse pas
     * sur un combiné dont l'onglet ne se lit pas.
     */
    fun tabToFollow(read: Int?, lastRead: Int?, lastReadAgeMs: Long): Int? =
        read ?: lastRead?.takeIf { lastReadAgeMs in 0..ONGLET_GARDE_MS }

    /** Durée pendant laquelle le dernier onglet lu tient lieu d'onglet illisible. */
    const val ONGLET_GARDE_MS = 30_000L

    fun moduleToShow(tabValue: Int?, navigation: ClusterModule?, media: ClusterModule?): ClusterModule? =
        when (tabValue) {
            null -> navigation ?: media
            ClusterTab.NAVIGATION.value -> navigation
            ClusterTab.MEDIA.value -> media
            else -> null
        }

    /** Valeur utilisable : le défaut si rien n'est enregistré, sinon calée sur un pas et ramenée dans la plage. */
    fun sanitizeRefresh(stored: Int?): Int {
        val ms = stored ?: DEFAULT_REFRESH_MS
        val cale = Math.floorDiv(ms + STEP_REFRESH_MS / 2, STEP_REFRESH_MS) * STEP_REFRESH_MS
        return cale.coerceIn(MIN_REFRESH_MS, MAX_REFRESH_MS)
    }

    /** Le curseur compte en dixièmes de seconde (1 à 10) : un pas entier, sans erreur d'arrondi. */
    fun refreshFromSlider(value: Float): Int = sanitizeRefresh((value * STEP_REFRESH_MS).roundToInt())

    fun refreshToSlider(ms: Int): Float = (sanitizeRefresh(ms) / STEP_REFRESH_MS).toFloat()

    /** Comme [sanitizeRefresh], pour la durée de l'animation. */
    fun sanitizeAnimation(stored: Int?): Int {
        val ms = stored ?: DEFAULT_ANIMATION_MS
        val cale = Math.floorDiv(ms + STEP_ANIMATION_MS / 2, STEP_ANIMATION_MS) * STEP_ANIMATION_MS
        return cale.coerceIn(MIN_ANIMATION_MS, MAX_ANIMATION_MS)
    }

    /** Le curseur compte en dixièmes de seconde (2 à 20). */
    fun animationFromSlider(value: Float): Int = sanitizeAnimation((value * STEP_ANIMATION_MS).roundToInt())

    fun animationToSlider(ms: Int): Float = (sanitizeAnimation(ms) / STEP_ANIMATION_MS).toFloat()
}
