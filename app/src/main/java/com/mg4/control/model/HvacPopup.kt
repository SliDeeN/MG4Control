package com.mg4.control.model

import com.mg4.control.accessibility.JoystickFocus

/**
 * Pop-up HVAC (issues #120 et #125) — ce que fait le joystick droit, sans vue ni véhicule.
 *
 * Ici le joystick ne déplace aucun focus, contrairement aux popups de profils : chaque direction
 * agit directement, comme dans la fenêtre clim d'origine de la voiture.
 *
 * |          | Action                                   |
 * |----------|------------------------------------------|
 * | Haut     | température + 1                          |
 * | Bas      | température − 1                          |
 * | Droite   | ventilation + 1 (rallume la clim éteinte) |
 * | Gauche   | ventilation − 1 (au cran 1 : clim éteinte) |
 * | Clic     | mode AUTO, ou retour au réglage manuel   |
 *
 * La fenêtre se ferme au doigt, par le raccourci qui l'a ouverte, ou seule après son délai.
 *
 * ## Le mode AUTO
 *
 * En AUTO la voiture règle elle-même la ventilation (et le sens de l'air), et n'annonce plus un
 * cran mais **15** : c'est à ce 15 que les écrans d'origine reconnaissent AUTO et l'affichent
 * (`HvacControlDialog` et `AcCardView` de SWI133, `HvacView` de SWI165). Ces mêmes écrans ne
 * « coupent » jamais AUTO : ils en SORTENT en écrivant un niveau de ventilation — le dernier
 * réglé à la main, plus ou moins un cran. C'est la règle reprise ici, et partagée avec la page
 * Clim et les raccourcis « ventilation ± ».
 *
 * ## Clim éteinte (issue #147)
 *
 * La MG4 n'a pas de cran 0 : « éteint », c'est l'arrêt de la climatisation, un bouton à part
 * sur l'écran d'origine (`btn_power`), dont le « ventilation − » s'arrête au cran 1. Le pop-up
 * n'a pas ce bouton : au cran 1, gauche ÉTEINT la clim, et droite la rallume au cran 1. Éteinte,
 * la voiture annonce une ventilation à 0 ; gauche ne fait alors plus rien — avant, 0 − 1 ramené
 * au minimum écrivait le cran 1, et rallumait la clim.
 */
object HvacPopup {

    sealed class Action {
        data class Temperature(val cible: Int) : Action()

        /** Un niveau de ventilation écrit à la main — c'est aussi ce qui fait sortir d'AUTO. */
        data class Ventilation(val cible: Int) : Action()

        /** Passer en mode AUTO : le clic central du joystick, ou le bouton AUTO. */
        object Auto : Action()

        /** Éteindre la climatisation : gauche au cran le plus bas (issue #147). */
        object Arret : Action()
    }

    /** Ce qui est lu sur la voiture ; les bornes en viennent aussi. null = illisible. */
    data class Reglages(
        val temp: Int?, val tempMin: Int, val tempMax: Int,
        val fan: Int?, val fanMin: Int, val fanMax: Int,
        /** Mode AUTO annoncé par la voiture ; null = illisible. */
        val auto: Boolean? = null,
        /** Dernier niveau de ventilation réglé à la main, retenu avant AUTO ; null = jamais vu. */
        val dernierNiveau: Int? = null,
        /** Clim en marche, d'après la voiture ; null = illisible. */
        val allumee: Boolean? = null,
    ) {
        /**
         * Clim éteinte : la voiture le dit, ou sa ventilation annoncée est sous le premier cran
         * (0, constaté sur SWI68 — issue #147).
         */
        val eteinte: Boolean get() = allumee == false || (fan != null && fan < fanMin)

        /**
         * AUTO actif : la voiture le dit, ou son niveau de ventilation le trahit — au-delà du
         * maximum ce n'est plus un cran (15), et c'est à cela que les écrans d'origine s'y fient.
         */
        val enAuto: Boolean get() = auto == true || (fan != null && fan > fanMax)

        /** Niveau de ventilation à montrer et à régler ; null en AUTO, clim éteinte, ou s'il est illisible. */
        val ventilationReelle: Int? get() = fan?.takeIf { !enAuto && !eteinte }
    }

    /** Action portée par [commande], ou null s'il n'y a rien à écrire (butée, valeur illisible). */
    fun action(commande: JoystickFocus.Commande, reglages: Reglages): Action? = when (commande) {
        JoystickFocus.Commande.HAUT    -> pas(reglages.temp, reglages.tempMin, reglages.tempMax, +1)?.let { Action.Temperature(it) }
        JoystickFocus.Commande.BAS     -> pas(reglages.temp, reglages.tempMin, reglages.tempMax, -1)?.let { Action.Temperature(it) }
        JoystickFocus.Commande.DROITE  -> pasVentilation(reglages, +1)?.let { Action.Ventilation(it) }
        JoystickFocus.Commande.GAUCHE  -> when {
            // Au cran le plus bas, réglé à la main : on éteint (issue #147). Jamais depuis AUTO,
            // qui sort d'abord au dernier cran moins un.
            !reglages.eteinte && !reglages.enAuto && reglages.fan == reglages.fanMin -> Action.Arret
            else -> pasVentilation(reglages, -1)?.let { Action.Ventilation(it) }
        }
        JoystickFocus.Commande.VALIDER -> when {
            // Rendre la main : le dernier niveau, tel quel.
            reglages.enAuto        -> Action.Ventilation(niveauDeSortie(reglages))
            reglages.auto == false -> Action.Auto
            // Sans état lisible on n'appuie pas : sur l'ancien SDK la commande est un APPUI, et
            // rien ne dit qu'un second ne couperait pas le mode qu'on voulait activer.
            else                   -> null
        }
    }

    /**
     * Niveau de ventilation à écrire pour un cran de plus ([sens] = +1) ou de moins (−1) ; null
     * s'il n'y a rien à écrire (butée, niveau illisible).
     *
     * En AUTO on repart du dernier niveau réglé à la main — surtout pas du 15 annoncé, d'où un
     * « moins » écrirait le MAXIMUM — et on écrit MÊME en butée : c'est cette écriture qui rend
     * la main, et l'utilisateur vient de demander à régler lui-même.
     */
    fun pasVentilation(reglages: Reglages, sens: Int): Int? = when {
        // Éteinte : « plus » rallume au premier cran, « moins » n'a rien à faire (issue #147).
        reglages.eteinte -> if (sens > 0) reglages.fanMin else null
        reglages.enAuto  -> (niveauDeSortie(reglages) + sens).coerceIn(reglages.fanMin, reglages.fanMax)
        else             -> pas(reglages.fan, reglages.fanMin, reglages.fanMax, sens)
    }

    /**
     * Niveau à écrire pour quitter AUTO sans rien changer d'autre : le dernier réglé à la main.
     * Faute de l'avoir jamais vu (application neuve, voiture en AUTO depuis toujours), le milieu
     * de l'échelle — ni le minimum ni le maximum ne feraient un point de départ raisonnable.
     */
    fun niveauDeSortie(reglages: Reglages): Int =
        (reglages.dernierNiveau ?: ((reglages.fanMin + reglages.fanMax) / 2))
            .coerceIn(reglages.fanMin, reglages.fanMax)

    // ── Consignes vérifiées : température et ventilation (2026-10-23) ─────────
    //
    // Deux crans rapides (2 → 4) laissaient la ventilation sur 3 : la voiture semble ignorer un
    // niveau qui arrive trop tôt après le précédent. La consigne demandée fait donc foi jusqu'à ce
    // que la voiture l'ait prise : écritures espacées, relecture, renvoi si besoin.

    /** Écart minimal entre deux écritures du même réglage, en ms. */
    const val ESPACEMENT_MS = 500L
    /** Renvois au plus d'une consigne que la voiture n'a pas prise. */
    const val RENVOIS_MAX = 3

    /** Ce qu'il faut faire d'une consigne après relecture. */
    enum class Suite { CONFIRMEE, RENVOYER, ABANDON }

    /**
     * Après relecture : [vu] est la valeur de la voiture (`null` si illisible), [cible] la consigne,
     * [renvois] ceux déjà faits. Illisible : on n'insiste pas, l'écran reprendra la voiture.
     */
    fun suiteConsigne(vu: Int?, cible: Int, renvois: Int): Suite = when {
        vu == cible -> Suite.CONFIRMEE
        vu == null || renvois >= RENVOIS_MAX -> Suite.ABANDON
        else -> Suite.RENVOYER
    }

    /**
     * Attente avant d'envoyer une consigne : l'anti-rebond [antiRebondMs], et au moins
     * [ESPACEMENT_MS] après la dernière écriture du même réglage ([derniereMs], 0 = jamais).
     */
    fun attenteEnvoi(maintenantMs: Long, derniereMs: Long, antiRebondMs: Long): Long =
        if (derniereMs == 0L) antiRebondMs
        else maxOf(antiRebondMs, ESPACEMENT_MS - (maintenantMs - derniereMs))

    // ── Taille de la fenêtre ─────────────────────────────────────────────────
    const val LARGEUR_DP = 864f      // la carte de overlay_hvac_popup.xml, sans chauffages
    const val HAUTEUR_DP = 392f

    /** Ce que la colonne des chauffages ajoute à la largeur de la carte, sur les firmwares équipés. */
    const val CHAUFFAGES_DP = 244f
    const val PART_LARGEUR = 0.96f
    const val PART_HAUTEUR = 0.82f

    /**
     * Agrandissement à appliquer à un pop-up dessiné pour une carte de [largeurDp] × [hauteurDp]
     * dp, pour qu'il prenne la même part de l'ÉCRAN quelle que soit sa densité : au plus
     * [partLargeur] de la largeur et [partHauteur] de la hauteur. Par défaut, la carte du pop-up
     * HVAC sans sa colonne des chauffages ; le sélecteur de profils passe par ici avec la sienne.
     *
     * Sur la voiture c'est la hauteur qui limite : 82 % de l'écran, presque toute celle que la
     * barre d'état laisse (réglé après l'essai du 2026-10-13, pour des commandes plus grandes).
     * Les 96 % de largeur ne servent que de garde sur un écran étroit.
     *
     * On raisonne en part d'écran, pas en dp fixes : la voiture affiche 1920 × 720 px à 1 px par
     * dp (SWI133, mesuré sur une photo le 2026-10-09 — une première fenêtre de 600 dp n'y
     * couvrait que 31 % de la largeur), mais la densité des firmwares A9 n'est pas connue.
     *
     * ⚠️ L'écran annoncé n'est pas la place réellement accordée à la fenêtre : la barre d'état et
     * le rail de navigation en prennent une part (voir [reduction]).
     */
    fun echelle(
        largeurPx: Int, hauteurPx: Int, densite: Float,
        largeurDp: Float = LARGEUR_DP, hauteurDp: Float = HAUTEUR_DP,
        partLargeur: Float = PART_LARGEUR, partHauteur: Float = PART_HAUTEUR,
    ): Float {
        if (largeurPx <= 0 || hauteurPx <= 0 || !(densite > 0f)) return 1f
        val parLargeur = partLargeur * largeurPx / (largeurDp * densite)
        val parHauteur = partHauteur * hauteurPx / (hauteurDp * densite)
        return minOf(parLargeur, parHauteur).coerceIn(0.75f, 2f)
    }

    /**
     * Filet : de combien réduire la carte pour qu'elle tienne dans la fenêtre RÉELLEMENT accordée,
     * marges comprises ; 1 si elle tient, ou sans mesures fiables.
     *
     * [echelle] part de l'écran annoncé, mais la fenêtre du pop-up s'arrête à la barre d'état et
     * au rail de navigation : sur SWI133 elle ne fait qu'environ 1754 × 650 px sur 1920 × 720
     * (photo du 2026-10-13, le pop-up y est centré à droite et en bas du centre de l'écran). Cette
     * place n'est connue qu'une fois la fenêtre posée, et rien ne dit qu'elle soit la même sur
     * les autres firmwares : plutôt qu'une carte coupée, une carte un peu plus petite.
     */
    fun reduction(fenetreLargeur: Int, fenetreHauteur: Int, carteLargeur: Int, carteHauteur: Int, marge: Int): Float {
        if (carteLargeur <= 0 || carteHauteur <= 0) return 1f
        val placeLargeur = fenetreLargeur - 2 * marge
        val placeHauteur = fenetreHauteur - 2 * marge
        if (placeLargeur <= 0 || placeHauteur <= 0) return 1f
        return minOf(1f, placeLargeur.toFloat() / carteLargeur, placeHauteur.toFloat() / carteHauteur)
    }

    // ── Jauge (présentation « moderne ») ─────────────────────────────────────

    /**
     * Où se trouve [valeur] entre [min] et [max], de 0 à 1 : la part de l'arc à remplir. Une
     * valeur hors bornes reste sur la jauge, à son extrémité ; null — jauge vide — sans valeur
     * lue ou sans étendue à parcourir.
     */
    fun part(valeur: Int?, min: Int, max: Int): Float? {
        if (valeur == null || max <= min) return null
        return ((valeur - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }

    /**
     * On CLAMPE, on ne boucle pas : arriver à 32 °C et repartir à 16 en poussant sur le volant
     * serait une très mauvaise surprise. Une cible égale à la valeur lue ne s'écrit pas.
     */
    private fun pas(actuel: Int?, min: Int, max: Int, sens: Int): Int? {
        actuel ?: return null
        return (actuel + sens).coerceIn(min, max).takeIf { it != actuel }
    }

    // ── Débit en mode AUTO (issue #178) ───────────────────────────────────────
    // Variante A, choisie le 2026-10-31 sur la proposition de l'issue : un appui COURT sur AUTO
    // l'allume ou l'éteint, et rien d'autre — l'éteindre reste aussi rapide qu'avant. Un appui
    // MAINTENU avance le débit d'un cran (Faible → Moyen → Fort → Faible) ; AUTO éteint, il
    // l'allume au débit déjà réglé. Trois crans sous le bouton montrent le débit en permanence.
    // Remplace la maquette B (rangée Faible | Moyen | Fort à la place de « − AUTO + »).

    /** Appui maintenu sur AUTO au-delà duquel le débit avance d'un cran. */
    const val APPUI_LONG_MS = 1_000L

    /**
     * Débits de gauche à droite, comme à l'écran : faible, moyen, fort — dans l'encodage de la
     * voiture (MG4Hardware.AutoVolume : 2 = faible, 1 = moyen, 0 = fort).
     */
    val DEBITS = listOf(2, 1, 0)

    /** Débit suivant, en boucle : faible → moyen → fort → faible. Inconnu → faible. */
    fun debitSuivant(debit: Int): Int = DEBITS[(DEBITS.indexOf(debit) + 1) % DEBITS.size]

    /** Crans allumés sous AUTO : 1 = faible, 2 = moyen, 3 = fort ; 0 pour un débit inconnu. */
    fun crans(debit: Int?): Int = debit?.let { DEBITS.indexOf(it) + 1 } ?: 0
}
