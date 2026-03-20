# MG4 Control

Application Android pour piloter les réglages de conduite de la MG4 EV directement depuis l'écran de bord.

> **Langues / Languages** : Français 🇫🇷 | English 🇬🇧

---

## Fonctionnalités

| Fonction | Détail |
|---|---|
| **Mode de conduite** | ECO / NORMAL / SPORT / NEIGE |
| **Régénération** | Faible / Standard / Fort / Adaptatif |
| **One Pedal** | Toggle ON/OFF |
| **Alerte dépassement de vitesse** | Toggle ON/OFF (son uniquement) |
| **Son changement de limite** | Toggle ON/OFF |
| **Profils de conduite** | 3 profils nommés et personnalisables |
| **Profil favori** | Appliqué automatiquement au démarrage de la voiture |
| **Démarrage automatique** | Service en arrière-plan lancé au boot Android |
| **Viewer de logs** | Intégré, sans ADB |
| **Multilingue** | Français / Anglais, choix au premier lancement |

---

## Architecture technique

```
UI (MainActivity)
  └── MainViewModel
        └── VehicleRepository  ─── réflexion Java ──→  VehiclePropertyManager
              └── ProfileRepository                     (ClassLoader du launcher SAIC)

Boot :
  BootReceiver → AutoStartService → VehicleRepository → profil favori
```

### Accès au SDK SAIC sans signature système

```kotlin
// Charger le SDK depuis le launcher SAIC (qui a les droits système)
val launcherCtx = appContext.createPackageContext(
    "com.saicmotor.hmi.launcher",
    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
)
val managerClass = launcherCtx.classLoader
    .loadClass("com.saicmotor.sdk.vehiclesettings.manager.VehiclePropertyManager")
```

**Aucune signature système requise.** Confirmé fonctionnel sur SWI133-29176-1300R30.

---

## Property IDs confirmés (SWI133-29176-1300R30)

| Réglage | Property ID | Valeurs |
|---|---|---|
| Mode de conduite | `0x2040001` | ECO=2, NORMAL=3, SPORT=4, NEIGE=6 |
| Niveau régénération | `0x5030001` | LOW=0, STD=1, HIGH=2, AUTO=3 |
| One Pedal | `0x5030003` | OFF=0, ON=1 |
| Alerte dépassement | `0x503004e` | OFF=0, ON=1 |
| Son changement limite | `0x503004f` | OFF=0, ON=1 |

---

## Installation sur la MG4

1. télécharger l'APK
2. Copier l'APK sur une clé USB **FAT32**
3. Brancher la clé sur la MG4
4. Ouvrir le gestionnaire de fichiers → taper sur l'APK → **Installer**
5. Autoriser les sources inconnues si demandé

---

## Premiers pas

1. Lancer l'app → choisir la langue (🇫🇷 / 🇬🇧)
2. Attendre **🟢 Connecté**
3. Configurer les réglages souhaités
4. **👤 Profils** → **💾 Sauvegarder état actuel** → choisir un profil
5. Renommer le profil (tap sur le nom)
6. Appuyer sur **☆ Favori** → le profil sera appliqué à chaque démarrage

---

## Démarrage automatique

Au démarrage d'Android (boot de la MG4) :

1. `BootReceiver` reçoit `BOOT_COMPLETED`
2. Lance `AutoStartService` en **foreground** avec WakeLock
3. Attend 15 secondes que le service SAIC démarre
4. Se connecte au SDK via réflexion
5. Applique le profil ⭐ favori
6. Se déconnecte et s'arrête

Les logs du service sont persistés dans un fichier et visibles dans **📋 Logs** après ouverture de l'app.

---

## Debug

```bash
# Logs en temps réel (si ADB disponible)
adb logcat -s "VehicleRepository" "AutoStartService" "BootReceiver" "MainViewModel"
```

Sans ADB : bouton **📋 Logs** dans l'app.

---

## Configuration requise

- Android 9 (API 28) minimum
- ROM MG4 avec `com.saicmotor.hmi.launcher` installé
- Firmware testé : **SWI133-29176-1300R30** (Trophy EU MY24)

---

## Structure du projet

```
app/src/main/java/com/mg4control/
├── model/
│   └── VehicleProfile.kt          ← Modèle de données profil
├── repository/
│   ├── VehiclePropertyIds.kt      ← Constantes property IDs
│   ├── VehicleRepository.kt       ← Accès SDK SAIC par réflexion
│   └── ProfileRepository.kt       ← Persistance des profils (SharedPreferences)
├── service/
│   ├── BootReceiver.kt            ← BroadcastReceiver boot
│   └── AutoStartService.kt        ← Service foreground d'application du profil
├── ui/
│   ├── MainActivity.kt            ← Activité principale
│   ├── AppLogger.kt               ← Logger mémoire + fichier
│   └── LanguageManager.kt         ← Gestion i18n FR/EN
└── viewmodel/
    └── MainViewModel.kt           ← Logique UI

res/
├── values/         ← Français (défaut)
├── values-fr/      ← Français
├── values-en/      ← Anglais
└── layout/
    ├── activity_main.xml
    ├── dialog_profiles.xml
    ├── dialog_settings.xml
    ├── dialog_logs.xml
    └── item_profile.xml
```

---

## Avertissement

Cette application est développée à titre personnel et n'est pas affiliée à SAIC ou MG Motor. Utilisation à vos risques et périls.
