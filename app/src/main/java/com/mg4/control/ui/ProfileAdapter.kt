package com.mg4.control.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mg4.control.R
import com.mg4.hardware.MG4Hardware.Swi68Mode
import com.mg4.hardware.model.DrivingProfile
import com.mg4.hardware.FirmwareInfo

class ProfileAdapter(
    private var profiles: MutableList<DrivingProfile>,
    private var defaultId: String?,
    private val onApply: (DrivingProfile) -> Unit,
    private val onSetDefault: (DrivingProfile) -> Unit,
    private val onEdit: (DrivingProfile) -> Unit,
    private val onDelete: (DrivingProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
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
        holder.tvSummary.text = buildString {
            append(": ")
            append(profile.driveMode.label)
            append(" · ")
            append(profile.regenLevel.label)
            if (adasLabel.isNotEmpty()) { append(" · "); append(adasLabel) }
        }

        holder.tvDefault.visibility = if (isDefault) View.VISIBLE else View.GONE
        holder.tvName.setTextColor(
            if (isDefault) context.getColor(R.color.accent_normal)
            else           context.getColor(R.color.text_primary)
        )

        holder.btnApply.setOnClickListener   { onApply(profile) }
        holder.btnDefault.setOnClickListener { onSetDefault(profile) }
        holder.btnEdit.setOnClickListener    { onEdit(profile) }
        holder.btnDelete.setOnClickListener  { onDelete(profile) }
    }

    override fun getItemCount() = profiles.size

    fun update(newProfiles: List<DrivingProfile>, newDefaultId: String?) {
        profiles.clear()
        profiles.addAll(newProfiles)
        defaultId = newDefaultId
        notifyDataSetChanged()
    }
}
