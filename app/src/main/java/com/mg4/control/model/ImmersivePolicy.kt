package com.mg4.control.model

/**
 * Fusion de `Settings.Global "policy_control"` entre MG4Dash et MG4Control (issue #248) — Kotlin
 * pur, sans Android : la même fonction dans les deux applis, mêmes tests (règle commune convenue
 * avec le développeur de MG4Dash le 2026-11-15).
 *
 * Android 9 (`PolicyControl`) découpe la valeur sur `:`, chaque section sur le premier `=`, les
 * paquets sur `,` (nettoyés). `immersive.full` règle les filtres bandeau (status) ET barre
 * (navigation) au moment où il est lu : une section `.status` / `.navigation` placée AVANT est
 * sans effet, une placée après remplace le filtre. `-paquet` exclut, `*` / `apps` visent large.
 *
 * Chaque propriétaire ([OWNER_KEYS]) garde dans sa propre clé Global les paquets qu'il réclame :
 * `paquet` (tout cacher), `paquet/status` (bandeau seul), `paquet/navigation` (barre seule). À
 * chaque passage, il ne retire que ses anciens paquets et ceux des propriétaires désinstallés,
 * jamais ceux qu'un autre réclame, et n'ajoute que les siens. Android Auto et CarPlay ne sont
 * jamais ajoutés et toujours retirés.
 *
 * L'appelant applique [Plan] dans l'ordre : sa clé = [Plan.keyBefore], puis `policy_control` =
 * [Plan.policy] si [Plan.policyChanged], puis sa clé = [Plan.keyAfter], puis efface
 * [Plan.clearKeys]. 5 s après une écriture, il refait un passage et réécrit une seule fois si la
 * valeur doit encore changer (pas de lecture-écriture atomique). Personne n'observe la clé.
 */
enum class ImmersiveMode { FULL, STATUS, NAVIGATION }

object ImmersivePolicy {

    const val SETTING = "policy_control"
    const val MG4DASH = "app.mg4dash"
    const val ANDROID_AUTO = "com.allgo.app.androidauto"
    const val CARPLAY = "com.allgo.carplay.service"

    /** Propriétaire (paquet) → sa clé Global. Une clé par variante de MG4Control. */
    val OWNER_KEYS: Map<String, String> = linkedMapOf(
        MG4DASH to "mg4dash_immersive",
        "com.mg4.control" to "mg4control_immersive",
        "com.mg4.control.offline" to "mg4control_offline_immersive",
    )

    private const val FULL = "immersive.full"
    private const val STATUS = "immersive.status"
    private const val NAV = "immersive.navigation"
    private const val PRE = "immersive.preconfirms"
    private val KNOWN = listOf(FULL, STATUS, NAV, PRE)
    private val NEVER = setOf(ANDROID_AUTO, CARPLAY)

    /** Ce qu'on a le droit de réclamer : ni Android Auto, ni CarPlay, ni `*`, `apps`, `-paquet`. */
    fun isClaimable(pkg: String): Boolean =
        pkg.isNotEmpty() && pkg !in NEVER && pkg != "*" && pkg != "apps" && !pkg.startsWith("-")

    /** Lecture d'une clé : paquet → modes. Suffixe inconnu : entrée ignorée. */
    fun parseKey(value: String?): Map<String, Set<ImmersiveMode>> {
        val out = linkedMapOf<String, MutableSet<ImmersiveMode>>()
        value?.split(',')?.forEach { raw ->
            val e = raw.trim()
            if (e.isEmpty()) return@forEach
            val slash = e.indexOf('/')
            val pkg = if (slash < 0) e else e.substring(0, slash).trim()
            val mode = if (slash < 0) ImmersiveMode.FULL else when (e.substring(slash + 1).trim()) {
                "status" -> ImmersiveMode.STATUS
                "navigation" -> ImmersiveMode.NAVIGATION
                else -> null
            }
            if (mode == null || !isClaimable(pkg)) return@forEach
            out.getOrPut(pkg) { linkedSetOf() } += mode
        }
        return out
    }

    /** Écriture d'une clé ; `null` si vide. « Tout » l'emporte sur un mode partiel du même paquet. */
    fun formatKey(claims: Map<String, Set<ImmersiveMode>>): String? = claims.flatMap { (pkg, modes) ->
        when {
            !isClaimable(pkg) || modes.isEmpty() -> emptyList()
            ImmersiveMode.FULL in modes -> listOf(pkg)
            else -> listOfNotNull(
                if (ImmersiveMode.STATUS in modes) "$pkg/status" else null,
                if (ImmersiveMode.NAVIGATION in modes) "$pkg/navigation" else null,
            )
        }
    }.joinToString(",").ifEmpty { null }

    /**
     * Ce que fait un passage. [keyBefore] = anciens + voulus (écrit d'abord), [keyAfter] = voulus
     * (écrit après `policy_control`), [clearKeys] = clés des propriétaires désinstallés à effacer.
     */
    data class Plan(
        val policy: String?,
        val policyChanged: Boolean,
        val keyBefore: String?,
        val keyAfter: String?,
        val clearKeys: List<String>,
    )

    /**
     * [self] : le propriétaire qui passe ; [wanted] : ce qu'il veut maintenant ; [policy] : la
     * valeur actuelle ; [keys] : propriétaire → contenu de sa clé ; [installed] : propriétaires
     * installés ([self] l'est forcément).
     */
    fun plan(
        self: String,
        wanted: Map<String, Set<ImmersiveMode>>,
        policy: String?,
        keys: Map<String, String?>,
        installed: Set<String>,
    ): Plan {
        val voulus = wanted.filterKeys { isClaimable(it) }.filterValues { it.isNotEmpty() }
        val anciens = keys[self]?.let { parseKey(it) } ?: legacy(self, policy)
        val autres = OWNER_KEYS.keys.filter { it != self && it in installed }.map { parseKey(keys[it]) }
        val orphelins = OWNER_KEYS.keys.filter { it != self && it !in installed }.map { parseKey(keys[it]) }

        fun retirer(section: String): Set<String> =
            (claims(anciens, section) + orphelins.flatMap { claims(it, section) }) -
                claims(voulus, section) - autres.flatMap { claims(it, section) }.toSet()

        val sections = parse(policy)

        // 1. Sections mortes : .status / .navigation placées avant le dernier immersive.full.
        val dernierFull = sections.indexOfLast { it.name == FULL }
        val vivantes = sections.filterIndexed { i, s -> !(i < dernierFull && (s.name == STATUS || s.name == NAV)) }
            .toMutableList()

        // 2. Retraits, Android Auto, CarPlay, doublons et vides, section par section.
        for (s in vivantes) {
            val e = s.entries ?: continue
            val moins = retirer(s.name!!)
            val propres = e.map { it.trim() }.filter { it.isNotEmpty() && it !in NEVER && it !in moins }.distinct()
            e.clear(); e += propres
        }

        // 3. Sections en double réunies dans la première, pour les quatre noms.
        for (nom in KNOWN) {
            val memes = vivantes.filter { it.name == nom }
            if (memes.size < 2) continue
            val premiere = memes.first()
            memes.drop(1).forEach { autre ->
                autre.entries!!.forEach { if (it !in premiere.entries!!) premiere.entries += it }
                vivantes.remove(autre)
            }
        }

        // 4. Ajout dans immersive.full (créée si besoin).
        val ajoutFull = claims(voulus, FULL)
        var full = vivantes.firstOrNull { it.name == FULL }
        if (full == null && ajoutFull.isNotEmpty()) {
            full = Section(FULL, mutableListOf())
            vivantes.add(0, full)
        }
        full?.let { ajouter(it, ajoutFull) }

        // 5. .status puis .navigation : création (copie de immersive.full), ajout, suppression si identique.
        for (nom in listOf(STATUS, NAV)) {
            val miens = claims(voulus, nom)
            val dansFull = full?.entries.orEmpty()
            var sec = vivantes.firstOrNull { it.name == nom }
            if (sec == null && miens.any { it !in dansFull }) {
                sec = Section(nom, dansFull.toMutableList())
                vivantes += sec
            }
            if (sec == null) continue
            ajouter(sec, miens)
            if (full != null && sec.entries!!.toSet() == full.entries!!.toSet()) vivantes.remove(sec)
        }

        // 6. Ajout dans .preconfirms si elle existe.
        vivantes.firstOrNull { it.name == PRE }?.let { ajouter(it, claims(voulus, PRE)) }

        // 7. Sections vides supprimées, écriture dans l'ordre full, status, navigation, preconfirms, inconnues.
        val ordonnees = KNOWN.mapNotNull { nom -> vivantes.firstOrNull { it.name == nom && it.entries!!.isNotEmpty() } } +
            vivantes.filter { it.entries == null }
        val valeur = ordonnees.joinToString(":") { it.text() }.ifEmpty { null }

        val union = linkedMapOf<String, MutableSet<ImmersiveMode>>()
        (anciens.entries + voulus.entries).forEach { (p, m) -> union.getOrPut(p) { linkedSetOf() } += m }
        return Plan(
            policy = valeur,
            policyChanged = valeur != policy?.ifEmpty { null },
            keyBefore = formatKey(union),
            keyAfter = formatKey(voulus),
            clearKeys = OWNER_KEYS.filter { (o, _) -> o != self && o !in installed && keys[o] != null }.values.toList(),
        )
    }

    /** Ce qu'un propriétaire réclame dans une section. */
    private fun claims(c: Map<String, Set<ImmersiveMode>>, section: String): Set<String> = c.filter { (_, m) ->
        when (section) {
            FULL -> ImmersiveMode.FULL in m
            STATUS -> ImmersiveMode.FULL in m || ImmersiveMode.STATUS in m
            NAV -> ImmersiveMode.FULL in m || ImmersiveMode.NAVIGATION in m
            else -> m.isNotEmpty()
        }
    }.keys

    /** Ajout en fin de section, sans doublon ni paquet exclu par `-paquet`. */
    private fun ajouter(s: Section, pkgs: Collection<String>) {
        val e = s.entries!!
        pkgs.forEach { p -> if (p !in e && "-$p" !in e) e += p }
    }

    /**
     * Reprise de l'existant, une seule fois, pour MG4Dash sans clé : ses paquets sont ceux de
     * `immersive.full` si la valeur commence par `immersive.full=app.mg4dash,`, ou si elle ne
     * contient que CarPlay (1.7.3 beta 2).
     */
    private fun legacy(self: String, policy: String?): Map<String, Set<ImmersiveMode>> {
        if (self != MG4DASH || policy == null) return emptyMap()
        if (!policy.startsWith("$FULL=$MG4DASH,") && policy != "$FULL=$CARPLAY") return emptyMap()
        return parseKey(policy.substringBefore(':').substringAfter('='))
    }

    /** Section connue ([entries] non nul) ou inconnue / sans `=` (gardée telle quelle dans [raw]). */
    private class Section(val name: String?, val entries: MutableList<String>?, val raw: String = "") {
        fun text() = if (entries == null) raw else "$name=${entries.joinToString(",")}"
    }

    private fun parse(policy: String?): List<Section> = policy.orEmpty().split(':').filter { it.isNotEmpty() }.map { part ->
        val i = part.indexOf('=')
        val name = if (i < 0) null else part.substring(0, i)
        if (name in KNOWN) Section(name, part.substring(i + 1).split(',').toMutableList())
        else Section(null, null, part)
    }
}
