package com.mg4.control.model

import com.mg4.control.model.WindowCommand.Direction

/**
 * Durées d'une course complète, mesurées au doigt par l'assistant de calibration (onglet Vitres).
 * La montée est plus lente que la descente : les deux sont gardées.
 */
data class WindowCalibration(val downMs: Long, val upMs: Long) {

    /** Courses auto émulées : la course mesurée plus une marge, pour finir en butée malgré l'imprécision. */
    val emulatedOpenMs: Long get() = downMs + COURSE_MARGIN_MS
    val emulatedCloseMs: Long get() = upMs + COURSE_MARGIN_MS

    companion object {
        /** Plus court : appui manqué. Plus long : le garde-fou du maintien a coupé la vitre avant la butée. */
        const val MIN_MS = 800L
        const val MAX_MS = WindowCommand.HOLD_MAX_MS
        const val COURSE_MARGIN_MS = 500L

        fun isValidMeasure(ms: Long): Boolean = ms in MIN_MS..MAX_MS
    }
}

/**
 * Position estimée d'une vitre sans capteur, en % d'ouverture (0 = fermée, 100 = ouverte).
 *
 * Seules les commandes de l'app sont chronométrées : un interrupteur physique fausse l'estimation
 * sans que l'app le voie. D'où deux règles — la position part inconnue (et le redevient à chaque
 * démarrage de la voiture), et elle ne devient connue qu'après une course complète, qui amène la
 * vitre en butée quel que soit son point de départ. À afficher comme une estimation, jamais à
 * utiliser pour une décision de sécurité.
 */
class WindowEstimator(val calibration: WindowCalibration) {

    private var position: Float? = null
    private var moving: Direction? = null
    private var moveStartMs = 0L

    /** Début de mouvement. Une commande répétée dans le même sens ne relance pas le chrono. */
    fun start(direction: Direction, nowMs: Long) {
        if (moving == direction) return
        stop(nowMs)
        moving = direction
        moveStartMs = nowMs
    }

    fun stop(nowMs: Long) {
        val direction = moving ?: return
        position = positionAfter(direction, nowMs - moveStartMs)
        moving = null
    }

    /** Position à l'instant [nowMs], mouvement en cours compris ; null = inconnue. */
    fun current(nowMs: Long): Float? = moving?.let { positionAfter(it, nowMs - moveStartMs) } ?: position

    fun reset() {
        position = null
        moving = null
    }

    fun setClosed() {
        position = 0f
        moving = null
    }

    fun setOpen() {
        position = 100f
        moving = null
    }

    private fun positionAfter(direction: Direction, elapsedMs: Long): Float? {
        val elapsed = elapsedMs.coerceAtLeast(0L)
        val start = position
        return when (direction) {
            Direction.DOWN ->
                if (start == null) 100f.takeIf { elapsed >= calibration.downMs }
                else (start + elapsed * 100f / calibration.downMs).coerceAtMost(100f)
            Direction.UP ->
                if (start == null) 0f.takeIf { elapsed >= calibration.upMs }
                else (start - elapsed * 100f / calibration.upMs).coerceAtLeast(0f)
        }
    }
}
