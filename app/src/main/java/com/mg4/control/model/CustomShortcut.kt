package com.mg4.control.model

/**
 * Raccourci perso (issue #172, 2026-10-25) : une action définie par l'utilisateur, à mettre sur un
 * bouton du volant, un raccourci avancé ou un bouton flottant — ouvrir une appli, lancer une
 * fonction qu'une autre appli propose, envoyer une diffusion, ouvrir un lien ou appeler un webhook.
 *
 * L'idée de l'issue : plutôt que d'écrire un bouton pour chaque appli tierce (dashcam…), laisser
 * leurs développeurs exposer leurs fonctions — par un raccourci Android standard ([CustomKind.APP_SHORTCUT])
 * ou une action déclarée pour MG4Control ([CustomKind.FUNCTION], voir [CustomShortcuts.DISCOVERY_ACTION]).
 *
 * Tout est en chaînes facultatives, pour Gson ; seuls les champs du type choisi servent.
 */
data class CustomShortcut(
    val id: String,
    val name: String,
    /** Clé de [CustomKind]. */
    val kind: String,
    /** Appli visée : à ouvrir, dont on lance un raccourci ou une fonction, ou qui reçoit la diffusion. */
    val packageName: String? = null,
    /** Fonction déclarée : le récepteur de l'appli qui la porte. */
    val className: String? = null,
    /** Raccourci Android standard : son identifiant dans l'appli. */
    val shortcutId: String? = null,
    /** Nom de l'appli et de la fonction, pour l'affichage (l'appli peut disparaître). */
    val targetLabel: String? = null,
    /** Diffusion ou fonction déclarée : l'action envoyée. */
    val action: String? = null,
    /** Diffusion : « clé=valeur », une par ligne, envoyées en chaînes. */
    val extras: String? = null,
    /** Lien : l'adresse à ouvrir (waze://…, https://…). */
    val uri: String? = null,
    /** Webhook : adresse, méthode (GET ou POST) et corps. */
    val url: String? = null,
    val method: String? = null,
    val body: String? = null,
)

enum class CustomKind(val key: String) {
    /** Ouvrir une appli. */
    APP("app"),
    /** Un raccourci que l'appli publie pour les launchers (appui long sur son icône). */
    APP_SHORTCUT("app_shortcut"),
    /** Une fonction que l'appli déclare pour MG4Control : une action reçue en arrière-plan. */
    FUNCTION("function"),
    /** Une diffusion saisie à la main (`am broadcast -a … -p …`). */
    BROADCAST("broadcast"),
    LINK("link"),
    /** Version online seulement : la version offline n'a pas accès à Internet. */
    WEBHOOK("webhook");

    companion object {
        fun fromKey(key: String?): CustomKind? = entries.firstOrNull { it.key == key }
    }
}

object CustomShortcuts {

    const val MAX = 20
    const val NAME_MAX = 40

    /**
     * Convention pour les développeurs : un récepteur exporté dont le filtre porte CETTE action, en
     * plus de la sienne, est proposé dans « Fonction d'une appli » ; son `android:label` en est le
     * nom. MG4Control n'envoie jamais cette action : il ne s'en sert que pour trouver le récepteur,
     * puis lui envoie l'autre action de son filtre.
     */
    const val DISCOVERY_ACTION = "com.mg4.control.action.SHORTCUT"

    /**
     * MG4Control tourne avec les droits du système : une diffusion qu'il envoie peut déclencher ce
     * qui est réservé au système (jusqu'à la remise à zéro). Ces préfixes sont donc refusés, le
     * nôtre compris (l'API externe a ses propres verrous, ne pas les contourner).
     */
    val RESERVED_PREFIXES = listOf("android.", "com.android.", "com.saicmotor.", "com.mg4.control.")

    fun kindOf(c: CustomShortcut): CustomKind? = CustomKind.fromKey(c.kind)

    fun actionAllowed(action: String?): Boolean {
        val a = action?.trim().orEmpty()
        return a.isNotEmpty() && RESERVED_PREFIXES.none { a.startsWith(it) }
    }

    /** Ce qui empêche d'enregistrer [c] ; `null` s'il est complet. */
    enum class Problem { NAME, TARGET, TARGET_SYSTEM, ACTION, ACTION_RESERVED, LINK, URL, KIND }

    /**
     * Vérifie [c]. [isUserApp] dit si un paquet est une appli installée par l'utilisateur (ni
     * système, ni MG4Control) : seules celles-là reçoivent une diffusion ou une fonction.
     */
    fun problem(c: CustomShortcut, isUserApp: (String) -> Boolean): Problem? {
        if (c.name.isBlank()) return Problem.NAME
        return when (kindOf(c)) {
            null -> Problem.KIND
            CustomKind.APP, CustomKind.APP_SHORTCUT ->
                if (c.packageName.isNullOrBlank() || (kindOf(c) == CustomKind.APP_SHORTCUT && c.shortcutId.isNullOrBlank()))
                    Problem.TARGET else null
            CustomKind.FUNCTION, CustomKind.BROADCAST -> when {
                c.packageName.isNullOrBlank() || (kindOf(c) == CustomKind.FUNCTION && c.className.isNullOrBlank()) -> Problem.TARGET
                !isUserApp(c.packageName) -> Problem.TARGET_SYSTEM
                c.action.isNullOrBlank() -> Problem.ACTION
                !actionAllowed(c.action) -> Problem.ACTION_RESERVED
                else -> null
            }
            CustomKind.LINK -> if (linkAllowed(c.uri)) null else Problem.LINK
            CustomKind.WEBHOOK -> if (urlAllowed(c.url)) null else Problem.URL
        }
    }

    /**
     * Un lien : un schéma et la suite (`waze://…`, `https://…`, `geo:…`). `intent:` est refusé — il
     * pourrait désigner un composant précis, que nos droits système laisseraient ouvrir.
     */
    fun linkAllowed(uri: String?): Boolean {
        val u = uri?.trim().orEmpty()
        val schema = u.substringBefore(':', "").lowercase()
        return schema.isNotEmpty() && schema != "intent" && schema.all { it.isLetterOrDigit() || it in "+-." } &&
            u.length > schema.length + 1
    }

    /**
     * HTTPS seulement : l'appli vise Android 14, qui refuse le HTTP en clair par défaut ; l'ouvrir
     * pour tous les échanges de l'appli pour un webhook ne vaudrait pas le risque.
     */
    fun urlAllowed(url: String?): Boolean {
        val u = url?.trim().orEmpty().lowercase()
        return u.startsWith("https://") && u.substringAfter("://").isNotBlank()
    }

    /** « clé=valeur » par ligne ; les lignes sans clé ou sans « = » sont ignorées. */
    fun parseExtras(text: String?): List<Pair<String, String>> =
        text.orEmpty().lines().mapNotNull { ligne ->
            val i = ligne.indexOf('=')
            if (i <= 0) null else ligne.substring(0, i).trim().takeIf { it.isNotEmpty() }?.let { it to ligne.substring(i + 1).trim() }
        }

    /** Webhook : POST par défaut s'il y a un corps, GET sinon. */
    fun methodOf(c: CustomShortcut): String =
        c.method?.uppercase()?.takeIf { it == "GET" || it == "POST" } ?: if (c.body.isNullOrBlank()) "GET" else "POST"

    /** Type du corps envoyé : JSON s'il en a l'air, du texte sinon. */
    fun contentType(body: String?): String {
        val b = body?.trim().orEmpty()
        return if (b.startsWith("{") || b.startsWith("[")) "application/json; charset=utf-8" else "text/plain; charset=utf-8"
    }

    /** L'adresse d'un webhook pour le journal : l'hôte seulement, le chemin porte souvent un jeton. */
    fun maskUrl(url: String?): String {
        val u = url?.trim().orEmpty()
        val schema = u.substringBefore("://", "")
        if (schema.isEmpty()) return "•••"
        val hote = u.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@')
        return "$schema://$hote/•••"
    }
}
