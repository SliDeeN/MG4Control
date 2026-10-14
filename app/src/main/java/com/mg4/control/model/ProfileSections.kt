package com.mg4.control.model

/**
 * Catégories d'un profil (Conduite, Sécurité, Confort) — ce que l'éditeur et la liste des
 * profils en disent. Sans Android ni véhicule ; l'état lui-même est dans [DrivingProfile].
 */
object ProfileSections {

    /** Un profil dont aucune catégorie n'est active n'écrirait rien : il n'est pas enregistrable. */
    fun anyEnabled(drive: Boolean, safety: Boolean, comfort: Boolean): Boolean = drive || safety || comfort

    /**
     * Résumé d'un profil dans la liste : mode de conduite, régénération, aide à la conduite —
     * chacun seulement si sa catégorie est appliquée, pour ne pas annoncer un réglage que le
     * profil n'écrira pas. [adasLabel] est vide sur les firmwares sans libellé. Quand il ne
     * reste rien à citer, le résumé nomme les catégories actives.
     */
    fun summary(
        profile: DrivingProfile, adasLabel: String,
        driveName: String, safetyName: String, comfortName: String
    ): String {
        val reglages = buildList {
            if (profile.appliesDrive) {
                add(profile.driveMode.label)
                add(profile.regenLevel.label)
            }
            if (profile.appliesSafety && adasLabel.isNotEmpty()) add(adasLabel)
        }
        val parts = reglages.ifEmpty {
            listOfNotNull(
                driveName.takeIf { profile.appliesDrive },
                safetyName.takeIf { profile.appliesSafety },
                comfortName.takeIf { profile.appliesComfort }
            )
        }
        return parts.joinToString(" · ")
    }

    /** Texte d'un bouton du rail : le nom de la catégorie, suivi de [offTag] si elle est coupée. */
    fun railLabel(name: String, enabled: Boolean, offTag: String): String =
        if (enabled) name else "$name\n$offTag"
}
