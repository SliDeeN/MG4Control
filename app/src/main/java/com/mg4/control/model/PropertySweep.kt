package com.mg4.control.model

/**
 * Balayage des propriétés du véhicule pour la sonde clé ([com.mg4.control.hardware.KeyProbe]) —
 * la partie sans véhicule : quoi relire, et quoi signaler entre deux relevés.
 */
object PropertySweep {

    private const val GROUPE = 0xf0000000.toInt()
    private const val GROUPE_CONSTRUCTEUR = 0x20000000
    private const val TYPE = 0x00ff0000
    private const val TYPE_ENTIER = 0x00400000
    private const val TYPE_BOOLEEN = 0x00200000

    /**
     * Vrai pour une propriété que le balayage relit : entier ou booléen du groupe constructeur.
     *
     * Le groupe « système » est laissé de côté — on y trouve les commandes d'usine et
     * d'alimentation, que personne ne relit en usage normal. Les flottants bougent tout seuls
     * (vitesse, énergie) et les blocs d'octets sont des codes d'équipement : ni les uns ni les
     * autres ne diraient si une clé est là.
     */
    fun sweepable(id: Int): Boolean =
        (id and GROUPE) == GROUPE_CONSTRUCTEUR &&
            ((id and TYPE) == TYPE_ENTIER || (id and TYPE) == TYPE_BOOLEEN)

    /** Clé d'une valeur dans un relevé : identifiant et zone, en hexadécimal. */
    fun key(id: Int, area: Int): String = "0x" + Integer.toHexString(id) + "@0x" + Integer.toHexString(area)

    /**
     * Valeurs qui diffèrent entre deux relevés, triées par clé. Une propriété lue une seule des
     * deux fois est ignorée : une lecture qui échoue par intermittence n'apprend rien.
     */
    fun changes(before: Map<String, String>, after: Map<String, String>): List<String> =
        before.keys.sorted().mapNotNull { cle ->
            val avant = before[cle]
            val apres = after[cle] ?: return@mapNotNull null
            if (avant == apres) null else "$cle : $avant \u2192 $apres"
        }

    /** Début d'un bloc d'octets en hexadécimal — assez pour comparer deux relevés. */
    fun hex(bytes: ByteArray, max: Int = 16): String {
        if (bytes.isEmpty()) return "(vide)"
        val debut = bytes.take(max).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
        return if (bytes.size > max) "$debut\u2026 (${bytes.size} octets)" else debut
    }
}
