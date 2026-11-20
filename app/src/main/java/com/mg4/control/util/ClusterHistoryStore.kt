package com.mg4.control.util

import android.content.Context
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ClusterHistory
import java.io.File

/**
 * Historique des derniers kilomètres (issue #217), gardé d'un trajet à l'autre dans un petit fichier
 * de l'application. Écrit par le combiné ([com.mg4.control.service.ClusterModuleOverlay]) à chaque
 * tranche de route, lu à chaque relevé pour le module « Graphique ». Enregistré toutes les
 * [ENREGISTRER_TOUTES] tranches (1 km) : une coupure brutale perd au plus un kilomètre.
 *
 * Écriture atomique ([android.util.AtomicFile], 2026-11-20) : une coupure pendant l'écriture laisse
 * l'ancien fichier intact, au lieu d'un fichier abîmé qui ferait repartir l'historique de zéro.
 */
object ClusterHistoryStore {

    private const val TAG = "MG4_CLUSTER"
    private const val FICHIER = "cluster_history.json"
    private const val ENREGISTRER_TOUTES = 5

    @Volatile private var bins: List<ClusterHistory.Bin>? = null
    private var nonEnregistrees = 0

    /** Les tranches gardées, de la plus ancienne à la plus récente. */
    @Synchronized
    fun bins(ctx: Context): List<ClusterHistory.Bin> =
        bins ?: charger(ctx).also { bins = it }

    @Synchronized
    fun add(ctx: Context, b: ClusterHistory.Bin) {
        bins = ClusterHistory.append(bins(ctx), b)
        if (++nonEnregistrees >= ENREGISTRER_TOUTES) enregistrer(ctx)
    }

    /** Fin de trajet : ce qui n'est pas encore écrit. */
    @Synchronized
    fun flush(ctx: Context) {
        if (nonEnregistrees > 0) enregistrer(ctx)
    }

    private fun enregistrer(ctx: Context) {
        val liste = bins ?: return
        val f = fichier(ctx)
        var flux: java.io.FileOutputStream? = null
        runCatching {
            flux = f.startWrite()
            flux!!.write(com.google.gson.Gson().toJson(liste).toByteArray(Charsets.UTF_8))
            f.finishWrite(flux)
        }.onFailure {
            flux?.let { o -> f.failWrite(o) }
            AppLogger.w(TAG, "historique du graphique non enregistré : ${it.message}")
        }
        nonEnregistrees = 0
    }

    private fun fichier(ctx: Context) = android.util.AtomicFile(File(ctx.filesDir, FICHIER))

    private fun charger(ctx: Context): List<ClusterHistory.Bin> = runCatching {
        val f = fichier(ctx)
        // AtomicFile relit la copie de secours si une écriture a été interrompue.
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) emptyList()
        else com.google.gson.Gson().fromJson(String(f.readFully(), Charsets.UTF_8), Array<ClusterHistory.Bin>::class.java)
            .orEmpty().toList().filter { it.km > 0f && it.s >= 0f }
    }.getOrElse { AppLogger.w(TAG, "historique du graphique illisible : ${it.message}"); emptyList() }
}
