package com.mg4.control.model

/**
 * Fusion de `Settings.Global "policy_control"` entre MG4Dash et MG4Control (issue #248) — Kotlin
 * pur, sans Android : la même fonction dans les deux applis, mêmes tests (règle commune convenue
 * avec le développeur de MG4Dash le 2026-11-15, v2 après la revue du même jour).
 *
 * Android 9 (`PolicyControl`) découpe la valeur sur `:`, chaque section sur le premier `=`, les
 * paquets sur `,` (nettoyés). `immersive.full` règle les filtres bandeau (status) ET barre
 * (navigation) au moment où il est lu : une section `.status` / `.navigation` placée AVANT est
 * sans effet, une placée après remplace le filtre — vide, elle dit « aucune appli ».
 * `.preconfirms` vaut le filtre de `full` / `.navigation` tant qu'elle est absente ; vide, elle
 * remet le message « plein écran ». `-paquet` exclut, `*` / `apps` visent large.
 *
 * Chaque propriétaire ([OWNER_KEYS]) garde dans sa propre clé Global les paquets qu'il réclame :
 * `paquet` (tout cacher), `paquet/status` (bandeau seul), `paquet/navigation` (barre seule). La
 * clé commune [MANAGED] garde ce que l'un de nous a pu écrire : ce qu'elle contient et que plus
 * personne ne réclame est retiré, même si celui qui l'a ajouté ne passe plus (deux retraits
 * simultanés). Un paquet réclamé par un propriétaire installé n'est jamais retiré, et chaque
 * passage remet ce que tous réclament. Android Auto et CarPlay ne sont jamais ajoutés, toujours
 * retirés, et exclus (`-paquet`) d'une section qui vise large.
 *
 * L'appelant ne passe que si [hasStake], puis applique [Plan] dans l'ordre : sa clé =
 * [Plan.keyBefore], [MANAGED] = [Plan.managedBefore], `policy_control` = [Plan.policy] si
 * [Plan.policyChanged], sa clé = [Plan.keyAfter], [MANAGED] = [Plan.managedAfter], puis efface
 * [Plan.clearKeys]. 5 s après une écriture, il refait un passage et réécrit une seule fois si la
 * valeur doit encore changer (pas de lecture-écriture atomique). Personne n'observe les clés.
 */
enum class ImmersiveMode { FULL, STATUS, NAVIGATION }

object ImmersivePolicy {

    const val SETTING = "policy_control"
    /** Clé commune : tout ce que les propriétaires ont pu écrire dans [SETTING], au format des clés. */
    const val MANAGED = "immersive_managed"
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
    /** Nos exclusions, posées à côté de `*` / `apps` et retirées quand il n'y en a plus. */
    private val EXCLUSIONS = listOf("-$ANDROID_AUTO", "-$CARPLAY")

    /** Ce qu'on a le droit de réclamer : ni Android Auto, ni CarPlay, ni `*`, `apps`, `-paquet`. */
    fun isClaimable(pkg: String): Boolean =
        pkg.isNotEmpty() && pkg !in NEVER && !isWildcard(pkg) && !pkg.startsWith("-")

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
     * Ce que fait un passage. [keyBefore] = anciens + voulus (écrit d'abord), [managedBefore] =
     * [MANAGED] + toutes les réclamations (écrit avant `policy_control`), [keyAfter] = voulus et
     * [managedAfter] = toutes les réclamations (écrits après), [clearKeys] = clés des
     * propriétaires désinstallés à effacer.
     */
    data class Plan(
        val policy: String?,
        val policyChanged: Boolean,
        val keyBefore: String?,
        val keyAfter: String?,
        val managedBefore: String?,
        val managedAfter: String?,
        val clearKeys: List<String>,
    )

    /**
     * [self] : le propriétaire qui passe ; [wanted] : ce qu'il veut maintenant ; [policy] : la
     * valeur actuelle ; [keys] : propriétaire → contenu de sa clé ; [installed] : propriétaires
     * installés ([self] l'est forcément) ; [managed] : contenu de [MANAGED].
     */
    fun plan(
        self: String,
        wanted: Map<String, Set<ImmersiveMode>>,
        policy: String?,
        keys: Map<String, String?>,
        installed: Set<String>,
        managed: String?,
    ): Plan {
        val voulus = wanted.filterKeys { isClaimable(it) }.filterValues { it.isNotEmpty() }
        val anciens = keys[self]?.let { parseKey(it) } ?: legacy(self, policy)
        val orphelins = OWNER_KEYS.keys.filter { it != self && it !in installed }.map { parseKey(keys[it]) }
        val geres = parseKey(managed)
        val tous = reclamations(self, voulus, keys, installed)

        fun retirer(section: String): Set<String> =
            (claims(anciens, section) + orphelins.flatMap { claims(it, section) } + claims(geres, section)) -
                claims(tous, section)

        val sections = parse(policy)

        // 1. Sections mortes : .status / .navigation placées avant le dernier immersive.full.
        val dernierFull = sections.indexOfLast { it.name == FULL }
        val vivantes = sections.filterIndexed { i, s -> !(i < dernierFull && (s.name == STATUS || s.name == NAV)) }
            .toMutableList()

        // 2. Retraits, Android Auto, CarPlay, doublons et vides, section par section ; nos
        //    exclusions à côté de * / apps, retirées quand il n'y en a plus.
        for (s in vivantes) {
            val e = s.entries ?: continue
            val moins = retirer(s.name!!)
            val propres = e.map { it.trim() }.filter { it.isNotEmpty() && it !in NEVER && it !in moins }.distinct()
                .toMutableList()
            if (propres.any { isWildcard(it) }) EXCLUSIONS.forEach { if (it !in propres) propres += it }
            else propres.removeAll(EXCLUSIONS)
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

        // 4. Ajout dans immersive.full (créée si besoin) de ce que tous réclament.
        val ajoutFull = claims(tous, FULL)
        var full = vivantes.firstOrNull { it.name == FULL }
        if (full == null && ajoutFull.isNotEmpty()) {
            full = Section(FULL, mutableListOf())
            vivantes.add(0, full)
        }
        full?.let { ajouter(it, ajoutFull) }

        // 5. .status puis .navigation : création (copie de immersive.full) si un paquet réclamé n'y
        //    est pas, ajout, suppression si identique à immersive.full.
        for (nom in listOf(STATUS, NAV)) {
            val reclames = claims(tous, nom)
            val dansFull = full?.entries.orEmpty()
            var sec = vivantes.firstOrNull { it.name == nom }
            if (sec == null && reclames.any { it !in dansFull }) {
                sec = Section(nom, dansFull.toMutableList())
                vivantes += sec
            }
            if (sec == null) continue
            ajouter(sec, reclames)
            if (full != null && sec.entries!!.toSet() == full.entries!!.toSet()) vivantes.remove(sec)
        }

        // 6. Ajout dans .preconfirms si elle existe.
        vivantes.firstOrNull { it.name == PRE }?.let { ajouter(it, claims(tous, PRE)) }

        // 7. Sections vides supprimées (une section de seuls `-paquet` est vide), sauf celles qui
        //    disent quelque chose à Android : .status / .navigation vides si immersive.full ne
        //    l'est pas, .preconfirms vide si immersive.full ou .navigation ne l'est pas. Écriture
        //    dans l'ordre full, status, navigation, preconfirms, inconnues.
        fun pleine(nom: String) = vivantes.firstOrNull { it.name == nom }?.let { !vide(it) } ?: false
        val garder = mapOf(
            FULL to false,
            STATUS to pleine(FULL),
            NAV to pleine(FULL),
            PRE to (pleine(FULL) || pleine(NAV)),
        )
        val ordonnees = KNOWN.mapNotNull { nom ->
            vivantes.firstOrNull { it.name == nom && (!vide(it) || garder.getValue(nom)) }
        } + vivantes.filter { it.entries == null }
        val valeur = ordonnees.joinToString(":") { it.text() }.ifEmpty { null }

        return Plan(
            policy = valeur,
            policyChanged = valeur != policy?.ifEmpty { null },
            keyBefore = formatKey(fusion(anciens, voulus)),
            keyAfter = formatKey(voulus),
            managedBefore = formatKey(fusion(geres, tous)),
            managedAfter = formatKey(tous),
            clearKeys = OWNER_KEYS.filter { (o, _) -> o != self && o !in installed && keys[o] != null }.values.toList(),
        )
    }

    /**
     * Un passage a-t-il quelque chose à faire ? Oui s'il y a à ajouter, à soi à retirer, une clé
     * d'un propriétaire désinstallé, Android Auto ou CarPlay en clair dans une section, ou dans
     * [MANAGED] un paquet que plus personne ne réclame. Sinon on ne touche pas à la valeur, même
     * pour la « ranger » (elle peut appartenir à une autre appli).
     */
    fun hasStake(
        self: String,
        wanted: Map<String, Set<ImmersiveMode>>,
        policy: String?,
        keys: Map<String, String?>,
        installed: Set<String>,
        managed: String?,
    ): Boolean {
        val voulus = wanted.filterKeys { isClaimable(it) }.filterValues { it.isNotEmpty() }
        if (voulus.isNotEmpty() || !keys[self].isNullOrEmpty()) return true
        if (OWNER_KEYS.keys.any { it != self && it !in installed && !keys[it].isNullOrEmpty() }) return true
        if (parse(policy).any { s -> s.entries.orEmpty().any { it.trim() in NEVER } }) return true
        val geres = parseKey(managed)
        val tous = reclamations(self, voulus, keys, installed)
        return KNOWN.any { (claims(geres, it) - claims(tous, it)).isNotEmpty() }
    }

    /** Ce que réclament [self] (ses voulus) et les autres propriétaires installés (leurs clés). */
    private fun reclamations(
        self: String,
        voulus: Map<String, Set<ImmersiveMode>>,
        keys: Map<String, String?>,
        installed: Set<String>,
    ): Map<String, Set<ImmersiveMode>> =
        fusion(voulus, *OWNER_KEYS.keys.filter { it != self && it in installed }.map { parseKey(keys[it]) }.toTypedArray())

    private fun fusion(vararg cs: Map<String, Set<ImmersiveMode>>): Map<String, Set<ImmersiveMode>> {
        val out = linkedMapOf<String, MutableSet<ImmersiveMode>>()
        cs.forEach { c -> c.forEach { (p, m) -> out.getOrPut(p) { linkedSetOf() } += m } }
        return out
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

    private fun isWildcard(e: String) = e == "*" || e == "apps"

    /** Une section sans paquet ni joker (vide, ou seulement des `-paquet`) ne vise rien. */
    private fun vide(s: Section) = s.entries!!.all { it.startsWith("-") }

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
