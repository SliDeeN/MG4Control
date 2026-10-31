package com.mg4.control.model

/**
 * Raccourci « Android Auto / CarPlay » (2026-10-23) : la règle, sans Android.
 *
 * L'appareil actif vient d'AllGo (`IRemoteUIService.getActiveRemoteDevice`, voir
 * [com.mg4.control.hardware.ProjectionReader]) : rien tant qu'aucune session n'est ouverte, sinon
 * son type — 1 CarPlay, 2 Android Auto, mêmes valeurs dans les lanceurs SWI133 et A9. Les écrans
 * ouverts sont ceux que la carte du lanceur SWI133 ouvre (`activity_uri_android_auto` /
 * `activity_uri_car_play`) ; Android Auto par ce chemin est validé sur SWI133 (2026-07-16).
 */
enum class ProjectionKind(val packageName: String, val activity: String, val label: String) {
    ANDROID_AUTO("com.allgo.app.androidauto", "com.allgo.app.androidauto.ProjectionActivity", "Android Auto"),
    CARPLAY("com.allgo.carplay.service", "com.allgo.carplay.service.CarPlayActivity", "CarPlay");

    companion object {
        fun fromType(type: Int?): ProjectionKind? = when (type) {
            1 -> CARPLAY
            2 -> ANDROID_AUTO
            else -> null
        }

        fun isProjection(packageName: String?): Boolean = entries.any { it.packageName == packageName }
    }
}

/** Ce que fait un appui. */
sealed class ProjectionStep {
    /** Ouvrir la projection du téléphone connecté. */
    data class Open(val kind: ProjectionKind) : ProjectionStep()
    /** La projection est déjà à l'écran : revenir à l'écran précédent (sa décision, 2026-10-23). */
    object Return : ProjectionStep()
    /** Aucune session : un message, comme la carte du lanceur d'origine. */
    object NoPhone : ProjectionStep()
}

object ProjectionRule {

    /** [foreground] : paquet de l'activité au premier plan ; [active] : la projection en session. */
    fun step(active: ProjectionKind?, foreground: String?): ProjectionStep = when {
        ProjectionKind.isProjection(foreground) -> ProjectionStep.Return
        active != null -> ProjectionStep.Open(active)
        else -> ProjectionStep.NoPhone
    }

    /**
     * Tâche où revenir : la plus récente après la projection, dans l'ordre d'Android (la plus
     * récente d'abord). [tasks] = (identifiant, paquet de son activité du haut). `null` : aucune,
     * l'accueil fera l'affaire.
     */
    fun returnTask(tasks: List<Pair<Int, String?>>): Int? =
        tasks.firstOrNull { (_, pkg) -> pkg != null && !ProjectionKind.isProjection(pkg) }?.first
}
