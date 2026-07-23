package com.mg4.control.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.mg4.control.R
import com.mg4.hardware.AppLogger
import com.mg4.hardware.MG4Hardware

class ConsoleFragment : Fragment() {

    private lateinit var textStatus: TextView
    private lateinit var textLog: TextView
    private lateinit var scrollView: ScrollView

    private val logListener: () -> Unit = {
        activity?.runOnUiThread { if (isAdded) renderLog() }
    }

    /** Total AppLogger déjà rendu — sert à n'ajouter que les nouvelles lignes. */
    private var renderedTotal = 0L

    // Auto-refresh périodique (status + logs) sans casser la position de scroll.
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            if (isAdded) {
                renderStatus()
                // totalCount et non size : la taille se fige dès que le buffer est plein.
                if (AppLogger.totalCount != renderedTotal) renderLog()
            }
            handler.postDelayed(this, 800L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_console, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textStatus = view.findViewById(R.id.console_status)
        textLog    = view.findViewById(R.id.console_text)
        scrollView = view.findViewById(R.id.console_scroll)

        view.findViewById<Button>(R.id.btn_clear_console).setOnClickListener {
            AppLogger.clear()
        }

        renderStatus()
        renderLog()
    }

    override fun onResume() {
        super.onResume()
        AppLogger.addListener(logListener)
        handler.post(refresh)
        renderStatus()
        renderLog()
    }

    override fun onPause() {
        super.onPause()
        AppLogger.removeListener(logListener)
        handler.removeCallbacks(refresh)
    }

    // ---- Status banner ----

    private fun renderStatus() {
        val sb = SpannableStringBuilder()

        fun appendStatus(label: String, ok: Boolean) {
            val line = if (ok) "  ✓  $label\n" else "  ✗  $label\n"
            val color = if (ok) Color.parseColor("#AAFFAA") else Color.parseColor("#FF5555")
            val start = sb.length
            sb.append(line)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, 0)
        }

        appendStatus("Katman1 — CarPropertyManager", MG4Hardware.isCarPropertyManagerReady())
        appendStatus("Katman1 — CarHvacManager",     MG4Hardware.isCarHvacManagerReady())
        appendStatus("Katman4 — VPM créé",           MG4Hardware.isKatman4VpmCreated())
        appendStatus("Katman4 — Service connecté",   MG4Hardware.isKatman4Ready())

        textStatus.text = sb
    }

    // ---- Log list ----

    /** True si l'utilisateur est (quasi) tout en bas → on suit les nouveaux logs ; sinon on le laisse. */
    private fun isAtBottom(): Boolean {
        val child = scrollView.getChildAt(0) ?: return true
        val marginPx = (24 * resources.displayMetrics.density).toInt()
        return child.bottom - (scrollView.height + scrollView.scrollY) <= marginPx
    }

    private fun renderLog() {
        // Mémorise la position AVANT de toucher au texte : on ne re-scrolle en bas
        // que si l'utilisateur suivait déjà le bas (sinon on préserve sa lecture).
        val followBottom = isAtBottom()

        // Rendu incrémental : on n'ajoute que ce qui est arrivé depuis le dernier rendu.
        // null = des entrées ont été évincées (ou le buffer a été vidé) → rendu complet.
        val newEntries = AppLogger.entriesSince(renderedTotal)
        val sb: SpannableStringBuilder
        if (newEntries == null) {
            sb = SpannableStringBuilder()
            AppLogger.entries.forEach { appendEntry(sb, it) }
        } else {
            if (newEntries.isEmpty()) return
            sb = SpannableStringBuilder(textLog.text)
            newEntries.forEach { appendEntry(sb, it) }
        }
        renderedTotal = AppLogger.totalCount
        textLog.text = sb
        if (followBottom) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    /** Ajoute une entrée colorée en fin de [sb]. */
    private fun appendEntry(sb: SpannableStringBuilder, entry: AppLogger.Entry) {
        val prefix = "[${entry.time}] "
        val tag    = "${entry.tag}: "
        val msg    = "${entry.msg}\n"
        val color  = when (entry.level) {
            AppLogger.Level.ERROR -> Color.parseColor("#FF5555")
            AppLogger.Level.WARN  -> Color.parseColor("#FFAA00")
            AppLogger.Level.DEBUG -> Color.parseColor("#888888")
            AppLogger.Level.INFO  -> Color.parseColor("#CCCCCC")
        }
        val start = sb.length
        sb.append(prefix).append(tag).append(msg)
        sb.setSpan(ForegroundColorSpan(Color.parseColor("#666666")), start, start + prefix.length, 0)
        sb.setSpan(ForegroundColorSpan(Color.parseColor("#AAAAFF")), start + prefix.length, start + prefix.length + tag.length, 0)
        sb.setSpan(ForegroundColorSpan(color), start + prefix.length + tag.length, sb.length, 0)
    }
}
