package com.mg4.control.model

/**
 * MG4Control seul (issue #248, revue du 2026-11-15) : nettoyage de `policy_control` quand un
 * MG4Dash qui ne suit pas encore la règle commune est installé ([ImmersivePolicy] n'est alors pas
 * appliquée). La valeur peut être la sienne : seules les entrées que notre propre clé identifie
 * partent, et rien d'autre ne bouge (Android Auto, CarPlay, doublons, sections mortes, jokers,
 * ordre). Une section que ce retrait vide (ou ne laisse qu'avec des `-paquet`) est supprimée.
 * Hors de la fonction commune : MG4Dash n'a pas de pause.
 */
object ImmersiveCleanup {

    /**
     * [own] : notre clé ; [policy] : la valeur actuelle ; [others] : clés des autres propriétaires
     * installés (un paquet qu'ils réclament reste). Renvoie la nouvelle valeur (`null` si vide),
     * identique à [policy] s'il n'y a rien à nous.
     */
    fun clean(own: String?, policy: String?, others: Collection<String?>): String? {
        val miens = ImmersivePolicy.parseKey(own)
        if (miens.isEmpty() || policy == null) return policy
        val autres = others.map { ImmersivePolicy.parseKey(it) }
        var change = false
        val parts = policy.split(':').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i < 0) return@mapNotNull part
            val nom = part.substring(0, i)
            val moins = vises(miens, nom) - autres.flatMap { vises(it, nom) }.toSet()
            if (moins.isEmpty()) return@mapNotNull part
            val e = part.substring(i + 1).split(',')
            if (e.none { it.trim() in moins }) return@mapNotNull part
            change = true
            val reste = e.filter { it.trim() !in moins }
            if (reste.map { it.trim() }.filter { it.isNotEmpty() }.all { it.startsWith("-") }) null
            else "$nom=${reste.joinToString(",")}"
        }
        if (!change) return policy
        return parts.joinToString(":").ifEmpty { null }
    }

    /** Paquets d'une clé présents, d'après leur mode, dans la section [nom] (vide si inconnue). */
    private fun vises(c: Map<String, Set<ImmersiveMode>>, nom: String): Set<String> = c.filter { (_, m) ->
        when (nom) {
            "immersive.full" -> ImmersiveMode.FULL in m
            "immersive.status" -> ImmersiveMode.FULL in m || ImmersiveMode.STATUS in m
            "immersive.navigation" -> ImmersiveMode.FULL in m || ImmersiveMode.NAVIGATION in m
            "immersive.preconfirms" -> m.isNotEmpty()
            else -> false
        }
    }.keys
}
