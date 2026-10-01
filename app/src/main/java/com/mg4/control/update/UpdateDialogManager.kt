package com.mg4.control.update

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.mg4.control.util.QrCode
import com.mg4.control.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Gère l'affichage du dialog de mise à jour.
 * 3 phases distinctes :
 *   1. Info      : versions + boutons Télécharger / Manuel / Plus tard
 *   2. Progress  : téléchargement via DownloadManager (dossier Téléchargements public)
 *   3. Manuel    : instructions GitHub/QR code
 */
object UpdateDialogManager {

    private const val TAG = "UpdateDialogManager"
    private const val GITHUB_RELEASES_URL =
        "https://github.com/SliDeeN/MG4Control/releases/latest"

    fun show(activity: AppCompatActivity, info: UpdateInfo) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()

        // ── Références vues ──────────────────────────────────────────────────
        val groupInfo     = view.findViewById<View>(R.id.group_info)
        val groupProgress = view.findViewById<View>(R.id.group_progress)
        val groupManual   = view.findViewById<View>(R.id.group_manual)

        // Phase 1 — Info
        val tvFrom       = view.findViewById<TextView>(R.id.tv_version_from)
        val tvTo         = view.findViewById<TextView>(R.id.tv_version_to)
        val tvNotes      = view.findViewById<TextView>(R.id.tv_release_notes)
        val rowDataWarn  = view.findViewById<View>(R.id.row_data_warning)
        val btnAuto      = view.findViewById<MaterialButton>(R.id.btn_update_auto)
        val btnManual    = view.findViewById<MaterialButton>(R.id.btn_update_manual)
        val btnLater     = view.findViewById<MaterialButton>(R.id.btn_update_later)
        val btnSkip      = view.findViewById<MaterialButton>(R.id.btn_update_skip)

        // Phase 2 — Progress
        val tvStatus     = view.findViewById<TextView>(R.id.tv_progress_status)
        val progressBar  = view.findViewById<ProgressBar>(R.id.progress_bar)
        val btnCancel    = view.findViewById<MaterialButton>(R.id.btn_cancel_download)

        // Phase 3 — Manuel
        val ivQr         = view.findViewById<ImageView>(R.id.iv_update_qr)
        val tvGhLink     = view.findViewById<TextView>(R.id.tv_update_gh_link)
        val tvApkPath    = view.findViewById<TextView>(R.id.tv_apk_path)
        val tvManualInst = view.findViewById<TextView>(R.id.tv_manual_instructions)
        val btnClose     = view.findViewById<MaterialButton>(R.id.btn_close_manual)

        // ── Remplissage initial ──────────────────────────────────────────────
        val currentVersion = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }

        tvFrom.text  = "v$currentVersion"
        tvTo.text    = "v${info.versionName}"
        tvNotes.text = info.releaseNotes.ifBlank { "—" }

        val onWifi = isOnWifi(activity)
        rowDataWarn.visibility = if (onWifi) View.GONE else View.VISIBLE


        // ── Bouton NE PLUS ME RAPPELER ───────────────────────────────────────
        btnSkip.setOnClickListener {
            UpdateChecker.skipVersion(activity, info.versionName)
            dialog.dismiss()
        }

        // ── Bouton PLUS TARD ─────────────────────────────────────────────────
        btnLater.setOnClickListener { dialog.dismiss() }

        // ── Panneau Manuel ───────────────────────────────────────────────────
        tvGhLink.setOnClickListener {
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL))
                )
            }
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        QrCode.generate(GITHUB_RELEASES_URL, 152)?.let { ivQr.setImageBitmap(it) }
        tvGhLink.text = GITHUB_RELEASES_URL
        tvGhLink.paintFlags = tvGhLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG

        fun showManualPanel(inDownloads: Boolean = false) {
            if (inDownloads) {
                tvManualInst.text = activity.getString(R.string.update_downloaded_instructions)
                tvApkPath.visibility = View.GONE
            }
            switchTo(groupInfo, groupProgress, groupManual, showGroup = groupManual)
        }

        // ── Bouton INSTALLATION MANUELLE ─────────────────────────────────────
        btnManual.setOnClickListener { showManualPanel() }

        // ── Bouton TÉLÉCHARGER L'APK ──────────────────────────────────────────
        btnAuto.setOnClickListener {
            if (!onWifi) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.update_data_warn_title)
                    .setMessage(R.string.update_data_warn_message)
                    .setPositiveButton(R.string.update_continue) { _, _ ->
                        switchTo(groupInfo, groupProgress, groupManual, showGroup = groupProgress)
                        launchDownload(activity, info, dialog, tvStatus, progressBar, btnCancel) {
                            showManualPanel(inDownloads = true)
                        }
                    }
                    .setNegativeButton(R.string.update_cancel, null)
                    .show()
            } else {
                switchTo(groupInfo, groupProgress, groupManual, showGroup = groupProgress)
                launchDownload(activity, info, dialog, tvStatus, progressBar, btnCancel) {
                    showManualPanel(inDownloads = true)
                }
            }
        }

        dialog.show()
    }

    // ── Téléchargement via DownloadManager → dossier Téléchargements public ───

    private fun launchDownload(
        activity: AppCompatActivity,
        info: UpdateInfo,
        dialog: AlertDialog,
        tvStatus: TextView,
        progressBar: ProgressBar,
        btnCancel: MaterialButton,
        onDownloaded: () -> Unit
    ) {
        // Deuxième barrière : UpdateChecker a déjà filtré l'URL, on ne fait jamais
        // confiance à une URL distante au point de la passer telle quelle au système.
        if (!ApkUrlPolicy.isAllowedLogged(info.apkUrl, "Téléchargement")) {
            tvStatus.setText(R.string.update_error_download)
            btnCancel.setText(R.string.update_close)
            return
        }

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // On conserve le nom d'origine du fichier tel qu'il est sur GitHub
        val fileName = info.apkUrl
            .substringAfterLast('/')
            .substringBefore('?')
            .ifBlank { "MG4Control-${info.versionName}.apk" }

        // DownloadManager n'écrase JAMAIS un fichier existant : si "…-2.6.5.apk" est déjà là
        // (téléchargement précédent de la même version), il écrit "…-2.6.5-1.apk". Or le code
        // relit ensuite le nom d'origine → il vérifierait/installerait l'ANCIEN fichier.
        // On supprime donc la cible avant d'enfiler la requête, pour garantir que le fichier
        // téléchargé, le fichier vérifié et le fichier installé soient bien le même.
        val target = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        if (target.exists()) {
            val deleted = target.runCatching { delete() }.getOrDefault(false)
            Log.i(TAG, "Ancien APK homonyme trouvé (${target.name}) → suppression=$deleted")
        }

        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("MG4Control ${info.versionName}")
            setDescription(activity.getString(R.string.update_downloading))
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType("application/vnd.android.package-archive")
        }

        val downloadId = dm.enqueue(request)
        Log.i(TAG, "DownloadManager enqueued id=$downloadId → $fileName")

        btnCancel.setOnClickListener {
            dm.remove(downloadId)
            dialog.dismiss()
        }

        tvStatus.setText(R.string.update_downloading)
        progressBar.isIndeterminate = false
        progressBar.progress = 0

        activity.lifecycleScope.launch {
            while (true) {
                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                if (!cursor.moveToFirst()) { cursor.close(); break }

                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                cursor.close()

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        progressBar.progress = 100
                        // [RÉACTIVÉ 2026-08-18] Contrôle de signature (T-901) — voir ApkSecurity.
                        // L'APK est dans un dossier public : on vérifie qu'il est signé par NOTRE
                        // clé avant d'inviter l'utilisateur à l'installer.
                        val downloaded = File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS), fileName)
                        if (!ApkSignatureVerifier.matchesRunningApp(activity, downloaded)) {
                            downloaded.runCatching { delete() }
                            tvStatus.setText(R.string.update_error_signature)
                            btnCancel.setText(R.string.update_close)
                            break
                        }
                        // Nettoie les anciens APK dans Téléchargements (garde les 5 plus récents)
                        ApkCleanup.cleanIfNeeded()
                        // Ouvre le dossier Téléchargements dans le gestionnaire AAOS
                        openDownloadsFolder(activity)
                        onDownloaded()
                        break
                    }
                    DownloadManager.STATUS_FAILED -> {
                        tvStatus.setText(R.string.update_error_download)
                        btnCancel.setText(R.string.update_close)
                        break
                    }
                    else -> {
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            progressBar.progress = pct
                            tvStatus.text = activity.getString(
                                R.string.update_downloading_pct, pct)
                        }
                    }
                }
                delay(500)
            }
        }
    }

    // ── Ouvre le dossier Téléchargements dans le gestionnaire AAOS ───────────

    private fun openDownloadsFolder(context: Context) {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Dossier Téléchargements ouvert")
        } catch (e: Exception) {
            Log.w(TAG, "Impossible d'ouvrir Téléchargements : ${e.message}")
        }
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    private fun switchTo(vararg allGroups: View, showGroup: View) {
        allGroups.forEach { it.visibility = View.GONE }
        showGroup.visibility = View.VISIBLE
    }

    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // ACCESS_NETWORK_STATE n'est déclarée que dans la variante online, la seule qui arrive ici ;
        // et l'uid système la détient de toute façon.
        @SuppressLint("MissingPermission")
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
