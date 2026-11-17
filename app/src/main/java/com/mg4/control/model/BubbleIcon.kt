package com.mg4.control.model

import com.mg4.control.shortcut.ShortcutAction

/**
 * Icônes des bulles flottantes (2026-11-17, demande d'un utilisateur ; maquette validée le même
 * jour) — les règles, sans Android.
 *
 * Trois contenus pour une bulle : Titre (comme avant), Information ([BubbleInfo]) ou **Icône**.
 * - Bulle-icône : l'icône seule, ou l'icône et le titre ([FloatingButton.iconTitle]). Par défaut,
 *   l'icône de l'appli que lance la bulle s'il y en a une, sinon un symbole neutre.
 * - Information en « icône + valeur » : l'icône de l'information comme avant, ou une autre choisie.
 *
 * Une icône s'enregistre en texte : [APP_PREFIX] + paquet (l'icône de l'appli, en couleurs) ou
 * [SYM_PREFIX] + nom d'un symbole (teinté comme le texte de la bulle). Les bulles d'avant n'ont
 * aucun de ces champs : elles restent Titre ou Information.
 */
object BubbleIcon {

    const val APP_PREFIX = "app:"
    const val SYM_PREFIX = "sym:"

    /** Bulle-icône sans appli à montrer : un doigt qui touche. */
    const val DEFAULT_SYMBOL = "touch_app"

    enum class Content { TITLE, INFO, ICON }

    /** L'information l'emporte (une bulle ne peut être les deux) ; puis l'icône ; sinon le titre. */
    fun content(b: FloatingButton): Content = when {
        b.info != null -> Content.INFO
        b.iconContent == true -> Content.ICON
        else -> Content.TITLE
    }

    fun isIcon(b: FloatingButton): Boolean = content(b) == Content.ICON

    /** L'icône s'affiche : bulle-icône, ou information en « icône + valeur ». */
    fun showsIcon(b: FloatingButton): Boolean =
        isIcon(b) || (content(b) == Content.INFO && BubbleInfo.style(b) == BubbleInfo.STYLE_ICON)

    /** Le titre s'affiche à côté de l'icône d'une bulle-icône. */
    fun showsTitle(b: FloatingButton): Boolean = !isIcon(b) || b.iconTitle == true

    fun app(pkg: String): String = APP_PREFIX + pkg
    fun symbol(name: String): String = SYM_PREFIX + name

    fun appPackage(ref: String?): String? = ref?.takeIf { it.startsWith(APP_PREFIX) }?.removePrefix(APP_PREFIX)?.takeIf { it.isNotBlank() }
    fun symbolName(ref: String?): String? = ref?.takeIf { it.startsWith(SYM_PREFIX) }?.removePrefix(SYM_PREFIX)?.takeIf { it.isNotBlank() }

    /**
     * L'icône à montrer, ou `null` = celle de l'information (bulle d'information sans choix).
     * [apps] : les applis que lance la bulle, la première par défaut. Une information ne prend
     * qu'un symbole : une icône d'appli n'y a pas de sens.
     */
    fun resolve(b: FloatingButton, apps: List<String>): String? = when (content(b)) {
        Content.ICON -> b.icon?.takeIf { appPackage(it) != null || symbolName(it) != null }
            ?: apps.firstOrNull()?.let(::app) ?: symbol(DEFAULT_SYMBOL)
        Content.INFO -> b.icon?.takeIf { symbolName(it) != null }
        Content.TITLE -> null
    }

    /**
     * Applis que lance une bulle, pour son icône par défaut et la catégorie « Application » du
     * choix : l'appli d'un raccourci perso ([customApp]), Android Auto et CarPlay pour la
     * projection, MG4Control pour « Ouvrir MG4Control ».
     */
    fun apps(action: String?, customApp: String?, selfPackage: String, projection: List<String>): List<String> = when (action) {
        ShortcutAction.CUSTOM.name -> listOfNotNull(customApp)
        ShortcutAction.PROJECTION.name -> projection
        ShortcutAction.OPEN_APP.name -> listOf(selfPackage)
        else -> emptyList()
    }
}
