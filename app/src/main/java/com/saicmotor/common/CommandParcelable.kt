package com.saicmotor.common

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * Commande que le launcher A9 envoie aux applis SAIC : `startService` avec l'extra
 * [EXTRA] — c'est ainsi qu'il fait passer l'appli Média sur une source (décompilé SWI69,
 * identique SWI131/132). Reproduite pour le cycle de sources audio (2026-10-15).
 *
 * ⚠️ Le NOM COMPLET de la classe et l'ORDRE d'écriture sont le contrat. L'appli qui reçoit
 * relit l'extra avec SA propre classe `com.saicmotor.common.CommandParcelable`, retrouvée par ce
 * nom, puis lit les champs dans l'ordre de [writeToParcel]. Ne pas la déplacer, la renommer ni
 * réordonner les champs. ProGuard la garde déjà (`-keep class com.saicmotor.** { *; }`).
 *
 * Valeurs par défaut reprises de la classe d'origine.
 */
class CommandParcelable(
    val event: Int = -1,
    val targetType: Int = -1,
    val targetPackage: String = "",
    val targetState: Int = -1,
    val param: Bundle = Bundle(),
) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(event)
        dest.writeString(targetPackage)
        dest.writeInt(targetType)
        dest.writeInt(targetState)
        dest.writeBundle(param)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** Nom de l'extra lu par les applis SAIC. */
        const val EXTRA = "command"

        @JvmField
        val CREATOR = object : Parcelable.Creator<CommandParcelable> {
            override fun createFromParcel(source: Parcel): CommandParcelable {
                val event = source.readInt()
                val pkg = source.readString() ?: ""
                val type = source.readInt()
                val state = source.readInt()
                val param = source.readBundle(CommandParcelable::class.java.classLoader) ?: Bundle()
                return CommandParcelable(event, type, pkg, state, param)
            }

            override fun newArray(size: Int): Array<CommandParcelable?> = arrayOfNulls(size)
        }
    }
}
