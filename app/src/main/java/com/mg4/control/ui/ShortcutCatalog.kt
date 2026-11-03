package com.mg4.control.ui

import android.content.Context
import com.mg4.control.R
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.util.FirmwareInfo

/**
 * Les actions qu'un raccourci peut lancer, avec leur nom, dans l'ordre des listes de choix —
 * seulement celles que ce firmware sait faire. Partagé par l'onglet Raccourcis et l'éditeur de
 * Mes automatisations (issue #201, étape 2) : une seule liste, les mêmes filtres partout.
 */
object ShortcutCatalog {

    data class Item(val label: String, val action: ShortcutAction)

    fun items(ctx: Context): List<Item> {
        val gen        = FirmwareInfo.getGeneration()
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isSWI132   = gen == FirmwareInfo.Gen.SWI132

        return buildList {
            add(Item(ctx.getString(R.string.shortcuts_action_none),           ShortcutAction.NONE))
            add(Item(ctx.getString(R.string.shortcuts_action_one_pedal),      ShortcutAction.ONE_PEDAL))
            add(Item(ctx.getString(R.string.shortcuts_action_regen_cycle),    ShortcutAction.REGEN_CYCLE))
            add(Item(ctx.getString(R.string.shortcuts_action_drive_cycle),    ShortcutAction.DRIVE_MODE_CYCLE))
            if (isKnown) {
                add(Item(ctx.getString(R.string.shortcuts_action_aeb),        ShortcutAction.AEB_CYCLE))
            }
            // SWI68/69/131/165 : une seule alerte sonore VSM
            if (isVsmBased && !isSWI132) {
                add(Item(ctx.getString(R.string.shortcuts_action_sound),      ShortcutAction.SOUND_WARNING))
            }
            // SWI133 + SWI132 : deux alertes indépendantes (survitesse + ton limite)
            if ((!isVsmBased || isSWI132) && isKnown) {
                add(Item(ctx.getString(R.string.shortcuts_action_overspeed),  ShortcutAction.OVERSPEED_ALARM))
                add(Item(ctx.getString(R.string.shortcuts_action_speed_limit),ShortcutAction.SPEED_LIMIT_TONE))
            }
            if (isKnown) {
                add(Item(ctx.getString(R.string.shortcuts_action_adas),       ShortcutAction.ADAS_CYCLE))
            }
            if (isKnown) {
                add(Item(ctx.getString(R.string.shortcuts_action_energy_saving), ShortcutAction.ENERGY_SAVING_TOGGLE))
            }
            if (isKnown) {
                add(Item(ctx.getString(R.string.shortcuts_action_tsr), ShortcutAction.TSR_TOGGLE))
            }
            // ESC + somnolence : même condition que la carte du Dashboard, sinon on
            // proposerait un raccourci qui échouerait en silence sur les firmwares non câblés.
            if (MG4Hardware.hasDrowsinessAndEsc()) {
                add(Item(ctx.getString(R.string.shortcuts_action_esc),             ShortcutAction.ESC_TOGGLE))
                add(Item(ctx.getString(R.string.shortcuts_action_drowsiness),      ShortcutAction.DROWSINESS_TOGGLE))
                add(Item(ctx.getString(R.string.shortcuts_action_drowsiness_sen),  ShortcutAction.DROWSINESS_SEN_CYCLE))
            }
            // Chauffages : aucun filtre firmware, exactement comme la carte du Dashboard qui les
            // affiche partout. Ils passent par des propriétés véhicule dont la lecture nullable
            // tranche avant d'agir — au pire le raccourci reste sans effet, jamais une écriture
            // à l'aveugle.
            add(Item(ctx.getString(R.string.shortcuts_action_seat_heat_left),  ShortcutAction.SEAT_HEAT_LEFT_CYCLE))
            add(Item(ctx.getString(R.string.shortcuts_action_seat_heat_right), ShortcutAction.SEAT_HEAT_RIGHT_CYCLE))
            add(Item(ctx.getString(R.string.shortcuts_action_steering_heat),   ShortcutAction.STEERING_HEAT_TOGGLE))
            if (MG4Hardware.hasClimateControl()) {
                add(Item(ctx.getString(R.string.shortcuts_action_hvac_toggle),     ShortcutAction.HVAC_TOGGLE))
                add(Item(ctx.getString(R.string.shortcuts_action_hvac_ac_toggle),  ShortcutAction.HVAC_AC_TOGGLE))
                add(Item(ctx.getString(R.string.shortcuts_action_hvac_temp_up),    ShortcutAction.HVAC_TEMP_UP))
                add(Item(ctx.getString(R.string.shortcuts_action_hvac_temp_down),  ShortcutAction.HVAC_TEMP_DOWN))
                add(Item(ctx.getString(R.string.shortcuts_action_hvac_fan_up),     ShortcutAction.HVAC_FAN_UP))
                add(Item(ctx.getString(R.string.shortcuts_action_hvac_fan_down),   ShortcutAction.HVAC_FAN_DOWN))
                add(Item(ctx.getString(R.string.shortcuts_action_defrost_front),   ShortcutAction.DEFROST_FRONT_TOGGLE))
                add(Item(ctx.getString(R.string.shortcuts_action_defrost_rear),    ShortcutAction.DEFROST_REAR_TOGGLE))
                add(Item(ctx.getString(R.string.shortcuts_action_hvac_recirc),     ShortcutAction.HVAC_RECIRC_CYCLE))
                add(Item(ctx.getString(R.string.shortcuts_action_hvac_autovol),    ShortcutAction.HVAC_AUTO_VOLUME_CYCLE))
                add(Item(ctx.getString(R.string.shortcuts_action_hvac_popup),      ShortcutAction.HVAC_POPUP))
            }
            // Feux de route automatiques : même condition que la carte Éclairage du Dashboard.
            if (MG4Hardware.hasAutoHighBeam()) {
                add(Item(ctx.getString(R.string.shortcuts_action_auto_high_beam),  ShortcutAction.AUTO_HIGH_BEAM_TOGGLE))
            }
            // Chauffage de la batterie : même condition que sa carte du Dashboard.
            if (MG4Hardware.hasBatteryHeating()) {
                add(Item(ctx.getString(R.string.shortcuts_action_battery_heat),    ShortcutAction.BATTERY_HEAT_TOGGLE))
            }
            if (MG4Hardware.hasBrightnessControl()) {
                add(Item(ctx.getString(R.string.shortcuts_action_brightness_up),   ShortcutAction.BRIGHTNESS_UP))
                add(Item(ctx.getString(R.string.shortcuts_action_brightness_down), ShortcutAction.BRIGHTNESS_DOWN))
            }
            // Média : aucun filtre firmware — ça ne passe pas par le véhicule mais par la
            // session média d'Android, identique sur les six firmwares.
            add(Item(ctx.getString(R.string.shortcuts_action_media_next),      ShortcutAction.MEDIA_NEXT))
            add(Item(ctx.getString(R.string.shortcuts_action_media_prev),      ShortcutAction.MEDIA_PREVIOUS))
            add(Item(ctx.getString(R.string.shortcuts_action_media_play_pause), ShortcutAction.MEDIA_PLAY_PAUSE))
            // Volume : pas de filtre non plus — la voie SAIC est tentée d'abord, AudioManager
            // prend le relais ailleurs, donc il y a toujours un chemin.
            add(Item(ctx.getString(R.string.shortcuts_action_volume_up),       ShortcutAction.VOLUME_UP))
            add(Item(ctx.getString(R.string.shortcuts_action_volume_down),     ShortcutAction.VOLUME_DOWN))
            // Vitres : même propriété véhicule sur les six firmwares, donc aucun filtre. Les
            // vitres sans capteur suivent la calibration de l'onglet Automatisation.
            add(Item(ctx.getString(R.string.shortcuts_action_windows_open),    ShortcutAction.WINDOWS_OPEN_ALL))
            add(Item(ctx.getString(R.string.shortcuts_action_windows_close),   ShortcutAction.WINDOWS_CLOSE_ALL))
            add(Item(ctx.getString(R.string.shortcuts_action_apply_profile),   ShortcutAction.APPLY_PROFILE))
            add(Item(ctx.getString(R.string.shortcuts_action_profile_picker), ShortcutAction.PROFILE_PICKER))
            add(Item(ctx.getString(R.string.shortcuts_action_profile_cycle),  ShortcutAction.PROFILE_CYCLE))
            // Sources audio : aucun filtre firmware — chaque firmware a sa voie (service média
            // SAIC, lecteurs Android sur A9, allgo pour la projection), à valider sur la voiture.
            add(Item(ctx.getString(R.string.shortcuts_action_audio_cycle),    ShortcutAction.AUDIO_SOURCE_CYCLE))
            // Pop-up de charge : toujours proposé (2026-10-17) ; option coupée, il dit où l'activer.
            add(Item(ctx.getString(R.string.shortcuts_action_charge_popup),  ShortcutAction.CHARGE_POPUP))
            // Pré-climatisation (issue #130) : verrouillage, clim et extinction existent sur tout
            // firmware connu.
            if (isKnown) add(Item(ctx.getString(R.string.shortcuts_action_preclim), ShortcutAction.PRE_CLIMATE))
            // Android Auto / CarPlay : le service AllGo est sur les six firmwares ; vérifié quand même,
            // un raccourci qui ne trouverait personne ne dirait que « aucun téléphone ».
            if (allgoPresent(ctx)) add(Item(ctx.getString(R.string.shortcuts_action_projection), ShortcutAction.PROJECTION))
            add(Item(ctx.getString(R.string.shortcuts_action_open_app),       ShortcutAction.OPEN_APP))
            add(Item(ctx.getString(R.string.shortcuts_action_open_custom_app),ShortcutAction.OPEN_CUSTOM_APP))
            add(Item(ctx.getString(R.string.shortcuts_action_android_settings), ShortcutAction.ANDROID_SETTINGS))
            // Raccourci perso (issue #172) : appli, fonction d'une autre appli, diffusion, lien, webhook.
            add(Item(ctx.getString(R.string.shortcuts_action_custom),         ShortcutAction.CUSTOM))
            // Mes automatisations : lancer celle choisie pour l'emplacement (étape 3).
            add(Item(ctx.getString(R.string.shortcuts_action_run_rule),       ShortcutAction.RUN_RULE))
            if (MG4Hardware.hasVehiclePowerOff()) {
                add(Item(ctx.getString(R.string.shortcuts_action_vehicle_power_off), ShortcutAction.VEHICLE_POWER_OFF))
            }
        }
    }

    /** Le nom d'une action ; `null` si ce firmware ne la propose pas. */
    fun label(ctx: Context, action: ShortcutAction): String? =
        items(ctx).firstOrNull { it.action == action }?.label

    private fun allgoPresent(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo("com.allgo.rui", 0)
        true
    }.getOrDefault(false)
}
