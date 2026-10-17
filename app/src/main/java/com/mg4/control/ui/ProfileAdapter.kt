package com.mg4.control.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mg4.control.R
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.ProfileSections
import com.mg4.control.util.FirmwareInfo

class ProfileAdapter(
    private var profiles: MutableList<DrivingProfile>,
    private var defaultId: String?,
    private val onApply: (DrivingProfile) -> Unit,
    private val onSetDefault: (DrivingProfile) -> Unit,
    private val onEdit: (DrivingProfile) -> Unit,
    private val onDelete: (DrivingProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView    = view.findViewById(R.id.tv_profile_name)
        val tvSummary: TextView = view.findViewById(R.id.tv_profile_summary)
        val tvDefault: TextView = view.findViewById(R.id.tv_default_badge)
        val btnApply: Button    = view.findViewById(R.id.btn_apply)
        val btnDefault: Button  = view.findViewById(R.id.btn_set_default)
        val btnEdit: Button     = view.findViewById(R.id.btn_edit)
        val btnDelete: Button   = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val profile = profiles[position]
        val isDefault = profile.id == defaultId
        val context = holder.itemView.context

        val adasLabel = when (FirmwareInfo.getGeneration()) {
            FirmwareInfo.Gen.SWI133 -> when (profile.adasMode) {
                0 -> "ADAS Off"
                1 -> "Lim."
                2 -> "Auto"
                3 -> "ACC"
                4 -> "ICA"
                else -> ""
            }
            // SWI68/SWI69/SWI131 : mêmes modes ADAS (ACC / TJA / Off)
            else -> if (!FirmwareInfo.isVsmBased()) "" else when (profile.swi68AdasMode) {
                Swi68Mode.OFF -> "ADAS Off"
                Swi68Mode.ACC -> "ACC"
                Swi68Mode.TJA -> "TJA"
                else -> ""
            }
        }

        holder.tvName.text    = profile.name
        // Seulement ce que le profil applique : une catégorie coupée n'est pas annoncée.
        holder.tvSummary.text = ": " + ProfileSections.summary(
            profile, adasLabel,
            context.getString(R.string.profile_cat_drive),
            context.getString(R.string.profile_cat_safety),
            context.getString(R.string.profile_cat_comfort),
            regenUnchanged = context.getString(R.string.regen_unchanged_short)
        )

        holder.tvDefault.visibility = if (isDefault) View.VISIBLE else View.GONE
        holder.tvName.setTextColor(
            if (isDefault) context.getColor(R.color.accent_normal)
            else           context.getColor(R.color.text_primary)
        )

        holder.btnApply.setOnClickListener   { if (!clicsBloques) onApply(profile) }
        holder.btnDefault.setOnClickListener { if (!clicsBloques) onSetDefault(profile) }
        holder.btnEdit.setOnClickListener    { if (!clicsBloques) onEdit(profile) }
        holder.btnDelete.setOnClickListener  { if (!clicsBloques) onDelete(profile) }
    }

    override fun getItemCount() = profiles.size

    /**
     * Vrai pendant un glisser-déposer : un appui long relâché sans bouger sur un bouton ne doit
     * pas le déclencher (le bouton reçoit encore ce relâcher).
     */
    var clicsBloques = false

    /** Glisser-déposer en cours : la liste affichée suit le doigt. */
    fun move(from: Int, to: Int) {
        if (from !in profiles.indices || to !in profiles.indices) return
        profiles.add(to, profiles.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun ids(): List<String> = profiles.map { it.id }

    fun nomA(position: Int): String? = profiles.getOrNull(position)?.name

    fun update(newProfiles: List<DrivingProfile>, newDefaultId: String?) {
        profiles.clear()
        profiles.addAll(newProfiles)
        defaultId = newDefaultId
        notifyDataSetChanged()
    }
}
