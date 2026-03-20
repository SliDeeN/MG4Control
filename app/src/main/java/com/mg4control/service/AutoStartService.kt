package com.mg4control.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mg4control.repository.ProfileRepository
import com.mg4control.repository.VehicleRepository
import com.mg4control.ui.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private const val TAG = "AutoStartService"
private const val DELAY_MS = 15000L
private const val TIMEOUT_MS = 30000L
private const val NOTIF_CHANNEL = "mg4control_boot"
private const val NOTIF_ID = 1

class AutoStartService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var vehicleRepo: VehicleRepository
    private lateinit var profileRepo: ProfileRepository
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        vehicleRepo = VehicleRepository(applicationContext)
        profileRepo = ProfileRepository(applicationContext)

        // WakeLock — empêche Android de suspendre le CPU pendant le délai d'attente
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MG4Control:BootWakeLock"
        ).apply { acquire(60_000) } // max 60s

        startForegroundNotification()
        AppLogger.i(TAG, "=== AutoStartService démarré (foreground + wakelock) ===")
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIF_CHANNEL,
            "MG4 Control — Démarrage auto",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Application du profil favori au démarrage" }
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("MG4 Control")
            .setContentText("Application du profil favori...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            applyFavoriteProfile()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun applyFavoriteProfile() {
        try {
            val favorite = profileRepo.getFavorite()
            if (favorite == null) {
                AppLogger.i(TAG, "Aucun profil favori défini — rien à appliquer")
                return
            }
            AppLogger.i(TAG, "Profil favori : '${favorite.name}'")

            AppLogger.i(TAG, "Attente ${DELAY_MS/1000}s pour laisser SAIC démarrer...")
            delay(DELAY_MS)

            var attempt = 0
            var connected = false
            while (attempt < 3 && !connected) {
                attempt++
                AppLogger.i(TAG, "Tentative connexion #$attempt...")
                withContext(Dispatchers.Main) { vehicleRepo.connect() }

                val state = withTimeoutOrNull(TIMEOUT_MS) {
                    vehicleRepo.connectionState.first {
                        it == VehicleRepository.ConnectionState.Connected ||
                        it is VehicleRepository.ConnectionState.Error
                    }
                }

                if (state == VehicleRepository.ConnectionState.Connected) {
                    connected = true
                    AppLogger.i(TAG, "Connexion OK à la tentative $attempt")
                } else {
                    AppLogger.w(TAG, "Tentative $attempt échouée : $state")
                    if (attempt < 3) {
                        withContext(Dispatchers.Main) { vehicleRepo.disconnect() }
                        delay(5000)
                    }
                }
            }

            if (!connected) {
                AppLogger.w(TAG, "Échec connexion — profil non appliqué")
                return
            }

            AppLogger.i(TAG, "Application du profil '${favorite.name}'...")
            delay(500)
            vehicleRepo.setDrivingMode(favorite.driveMode)
            delay(200)
            vehicleRepo.setRegenLevel(favorite.regenLevel)
            delay(200)
            vehicleRepo.setOnePedal(favorite.onePedal)
            delay(200)
            vehicleRepo.setIntProperty(
                com.mg4control.repository.VehiclePropertyIds.OVERSPEED_SOUND_ALARM,
                if (favorite.overspeedAlarm) 1 else 0
            )
            delay(200)
            vehicleRepo.setIntProperty(
                com.mg4control.repository.VehiclePropertyIds.SPEED_LIMIT_CHANGE_TONE,
                if (favorite.speedLimitChangeTone) 1 else 0
            )

            AppLogger.i(TAG, "✅ Profil '${favorite.name}' appliqué !")
            AppLogger.i(TAG, "  Mode: ${favorite.driveModeLabel()}")
            AppLogger.i(TAG, "  Régén: ${favorite.regenLabel()}")

        } catch (e: Exception) {
            AppLogger.e(TAG, "Erreur : ${e.message}", e)
        } finally {
            vehicleRepo.disconnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        AppLogger.i(TAG, "Service terminé")
    }
}
