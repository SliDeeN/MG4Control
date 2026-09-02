package com.mg4.control.model

enum class RegenLevel(val value: Int, val label: String) {
    LOW(0, "Low"),
    MEDIUM(1, "Medium"),
    HIGH(2, "High"),
    ADAPTIVE(3, "Adaptive"),
    OFF(5, "Off"),
    ONE_PEDAL(6, "One Pedal");

    companion object {
        fun fromValue(v: Int): RegenLevel = values().firstOrNull { it.value == v } ?: MEDIUM

        /**
         * Ordre d'USAGE des niveaux — celui du launcher d'origine, et surtout PAS l'ordre de
         * déclaration ci-dessus, où [OFF] (5) est coincé entre [ADAPTIVE] (3) et [ONE_PEDAL]
         * (6) : un cycle arithmétique traverserait Off puis 1 Pédale à chaque tour.
         *
         * [ONE_PEDAL] reste volontairement hors du cycle. C'est un mode de conduite à part
         * entière, il a déjà son propre raccourci, et l'y inclure imposerait de le traverser
         * à chaque tour de molette.
         */
        val CYCLE_ORDER = listOf(LOW, MEDIUM, HIGH, ADAPTIVE)

        /**
         * Modes qu'on accepte de voir figurer dans un cycle composé par l'utilisateur.
         *
         * [ONE_PEDAL] en fait partie : il reste hors du cycle PAR DÉFAUT ci-dessus, mais rien
         * n'interdit à quelqu'un de le vouloir dans sa séquence — c'est un mode de conduite qui
         * s'écrit exactement comme les autres.
         *
         * [OFF] non : couper la régénération n'est pas un cran de dosage, le launcher d'origine
         * ne le propose pas davantage, et le traverser à chaque tour serait subi plus que choisi.
         */
        val CYCLE_SELECTABLE = listOf(LOW, MEDIUM, HIGH, ADAPTIVE, ONE_PEDAL)

        /**
         * Niveau suivant dans le cycle, en rebouclant à la fin.
         *
         * [order] est la séquence à parcourir — [CYCLE_ORDER] par défaut, ou celle que
         * l'utilisateur a composée (voir `RegenCycle`). Une séquence vide retombe sur l'ordre
         * d'origine : un cycle sans mode ne pourrait produire aucun niveau à écrire.
         *
         * Un niveau absent du cycle (parce qu'il n'y figure pas, ou plus) fait entrer par le
         * premier cran : sur une touche de volant, ne rien faire passerait pour une panne.
         */
        fun nextInCycle(
            current: RegenLevel,
            order: List<RegenLevel> = CYCLE_ORDER
        ): RegenLevel {
            val cycle = if (order.isEmpty()) CYCLE_ORDER else order
            val idx = cycle.indexOf(current)
            return if (idx < 0) cycle.first() else cycle[(idx + 1) % cycle.size]
        }
    }
}
