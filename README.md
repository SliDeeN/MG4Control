![image info](mg4control_github_banner.svg)

[![Security](https://github.com/SliDeeN/MG4Control/actions/workflows/security.yml/badge.svg)](https://github.com/SliDeeN/MG4Control/actions/workflows/security.yml)
[![Release](https://github.com/SliDeeN/MG4Control/actions/workflows/release.yml/badge.svg)](https://github.com/SliDeeN/MG4Control/actions/workflows/release.yml)

> Application Android Automotive pour le contrôle avancé des paramètres de conduite du MG4 électrique.
> Android Automotive app for advanced driving settings control on the MG4 electric vehicle.

> Vous appréciez MG4Control et souhaitez soutenir son développement ?  
You enjoy MG4Control and want to support its development ?  
[![PayPal](https://img.shields.io/badge/Donate-PayPal-blue?logo=paypal)](https://www.paypal.com/paypalme/pfauquembergue)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ffdd00?logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/slideen)
---

<details open>
<summary><strong>🇫🇷 Français</strong></summary>

## Table des matières
1. [Présentation](#présentation)
2. [Fonctionnalités](#fonctionnalités)
3. [Compatibilité](#compatibilité)
4. [Architecture](#architecture)
5. [Structure du projet](#structure-du-projet)
6. [Couches matérielles](#couches-matérielles)
7. [Système de profils](#système-de-profils)
8. [Interface utilisateur](#interface-utilisateur)
9. [API externe](#api-externe-keymapper-tasker)
10. [Compilation et installation](#compilation-et-installation)
11. [Permissions requises](#permissions-requises)
12. [Licence](#licence)

---

## Présentation

**MG4Control** est une application système conçue pour Android Automotive OS, destinée à fonctionner sur les écrans de bord des véhicules MG4 équipés du SoC **SAIC MT2712**. Elle offre un accès direct et unifié aux réglages de conduite qui ne sont pas accessibles — ou difficilement accessibles — via l'interface constructeur.

L'application communique avec le véhicule via le SDK propriétaire SAIC, en accédant aux services Android Automotive (`CarPropertyManager`, `CarHvacManager`) ainsi qu'aux services de bas niveau exposés par le firmware du véhicule.

> **Important :** Cette application nécessite des privilèges système (`sharedUserId="android.uid.system"`) et doit être signée avec la clé de la ROM. Elle ne peut pas fonctionner sur un appareil standard débloqué.

> [!WARNING]
> **MG4Control est un projet communautaire indépendant. Il n'est en aucun cas affilié, approuvé ou soutenu par MG Motor, SAIC Motor ou l'une de leurs filiales.**
> L'utilisation de cette application se fait entièrement à vos risques. Des réglages incorrects peuvent affecter le comportement du véhicule. Procédez avec précaution.

---

## Fonctionnalités

### Paramètres de conduite
- **Mode de conduite** : ECO / NORMAL / SPORT / SNOW / CUSTOM
- **Régénération** : Off / Faible / Moyen / Fort / Adaptatif / 1 Pédale

### Sécurité
- **ESC** : ON / OFF
- **Avertissement de somnolence** : ON / OFF, avec sensibilité Faible / Standard / Élevé
- **Anti-collision avant (AEB)** : ON / OFF, mode Alerte seule ou Alerte + Freinage, sensibilité
- **Assistant de sortie de voie (ELK)** : Alerte / Aider / Maintien d'urgence, sensibilité, et sur
  SWI132 l'alerte sonore et la vibration

> [!WARNING]
> Couper l'ESC coupe aussi l'anti-collision avant — c'est le véhicule qui l'impose. Les deux
> reviennent sur ON à chaque démarrage. Dans un profil, ESC et somnolence ne sont écrits **que si
> le profil les configure** : un profil créé avant ces réglages n'y touche pas.

### Confort
- **Volant chauffant** : On / Off
- **Sièges chauffants gauche et droit** : Off / Niveau 1 / 2 / 3
- **Climatisation** : consigne de température, ventilation, marche/arrêt, A/C, AUTO,
  recirculation (intérieur / extérieur / auto), dégivrage avant et arrière
- **Luminosité de l'écran**
- **Volume à l'ouverture de porte** : baisse le volume média quand une porte avant s'ouvre, avec
  choix des portes déclencheuses et restauration à la fermeture
- **Audio** (firmwares A9) : type de son Bose, balance, fader, volume selon la vitesse

### ADAS (Assistance à la conduite)
- **SWI133** : Off / Limiteur / Auto / ACC / ICA + alertes excès de vitesse / changement de limite
- **SWI68** : Désactiver / ACC / TJA + avertissement sonore On / Off
- **SWI69 / SWI131** : Anti-collision avant (AEB) — On / Off + mode Alerte uniquement / Alerte + Freinage
- **SWI165** : Désactiver / ACC / TJA + Anti-collision avant (AEB) On/Off + mode Alerte / Alerte+Freinage + avertissement sonore

### Raccourcis sur boutons physiques

Deux systèmes coexistent, réglés dans le même écran :

- **Raccourcis classiques** — boutons ★ gauche et droit, en appui simple ou long. Ils reposent sur
  le broadcast émis par le véhicule : si le launcher officiel utilise déjà le bouton, il reste
  prioritaire.
- **Raccourcis avancés** — n'importe quelle touche physique, au volant **comme sur la façade
  sous l'écran**, en appui **court, long ou double**, via un **service d'accessibilité** que
  l'utilisateur active lui-même dans Android. MG4Control voit alors la touche **avant** le
  launcher et peut la consommer.

L'appui long part **dès le seuil de 500 ms atteint**, sans attendre le relâchement : l'action se
déclenche toujours au même moment, la touche encore enfoncée. Le double appui, lui, a un coût à
connaître — sur une touche qui en porte un, l'appui court ne peut plus partir au relâchement, il
attend 300 ms de plus pour s'assurer qu'aucun second appui n'arrive. Les touches sans double appui
ne paient rien.

> [!WARNING]
> Une touche enregistrée en raccourci avancé est réclamée **en bloc**. Pour intercepter un appui
> long il faut consommer l'appui dès son début, or à cet instant on ignore encore s'il sera court,
> long ou double. Les types d'appui laissés libres ne retombent donc pas sur le launcher : ils ne
> font rien.

Actions disponibles — celles qui dépendent du firmware n'apparaissent pas sur les autres :

| Catégorie | Actions |
|---|---|
| Conduite | 1 Pédale · Cycle Régénération Personnalisé · Éco. énergie |
| Sécurité | ESC · Somnolence · Somnolence : sensibilité · Système Anticollision · ADAS · Panneaux (TSR) · Alerte survitesse · Alerte changement de limite · Son |
| Confort | Siège chauffant gauche · Siège chauffant droit · Volant chauffant · Clim ON/OFF · Clim : température ± · Clim : ventilation ± · Dégivrage avant · Dégivrage arrière · Recirculation · Luminosité ± |
| Média | Lecture / Pause · Piste suivante · Piste précédente · Volume + · Volume - |
| Application et véhicule | Lancer un profil · Sélecteur de profil · Ouvrir MG4Control · Lancer une application · Éteindre la voiture |

Les actions de confort et de sécurité **relisent l'état sur le véhicule à chaque pression** au lieu
de mémoriser le leur : l'utilisateur agit aussi depuis l'écran d'origine, un état gardé en mémoire
dériverait dès le premier usage. Un état illisible vaut abstention — rien n'est écrit à l'aveugle.

La liste des raccourcis avancés affiche pour chaque ligne le **bouton** (nom et code), le **type
d'appui**, la **fonction**, puis *Modifier* et *Supprimer*. Réattribuer un bouton déjà utilisé sur
le même type d'appui demande confirmation et nomme la fonction qui va être remplacée.

#### Raccourcis associés à un profil
Un raccourci avancé porte une touche, un type d'appui, une fonction — et un **profil**, « Tous les
profils » par défaut. La même touche et le même appui peuvent donc porter plusieurs raccourcis, un
par profil : sous *Sport* le bouton fait une chose, sous *Hiver* une autre.

La résolution à l'appui se fait en deux crans, dans cet ordre :
1. un raccourci réservé au **profil actif** ;
2. sinon celui marqué **Tous les profils** ;
3. sinon rien.

Le cas 3 mérite d'être connu : la touche reste **consommée**, comme n'importe quel type d'appui
laissé libre sur une touche réclamée — elle ne retombe pas sur le launcher. La liste le signale
sur les groupes qui n'ont aucune ligne « Tous les profils ».

Le **profil actif** est le dernier appliqué, quelle qu'en soit la source : démarrage, contact,
Bluetooth, automatisation température ou choix manuel. Il est enregistré, donc il survit à un
redémarrage du service.

> [!NOTE]
> C'est le dernier profil **appliqué**, pas l'état réel du véhicule : un réglage changé à la main
> depuis le Dashboard ne l'invalide pas. L'alternative — comparer l'état du véhicule à chaque
> profil — coûterait une dizaine de lectures véhicule à chaque appui, pour un résultat ambigu dès
> que deux profils se ressemblent.

La liste des raccourcis est **groupée par touche + type d'appui**, « Tous les profils » en tête de
chaque groupe : l'ordre d'affichage est celui de la résolution.

Les trois réglages d'action — **repli du mode 1 Pédale**, **crans du cycle ADAS** et **cycle de
régénération** — ont chacun leur page, révélée dans le rail de gauche dès que la fonction est
attribuée à un bouton. Peu importe par quelle voie : emplacement classique, raccourci avancé, ou
fonction simplement sélectionnée dans le formulaire avancé, avant même la création du raccourci.

Le cycle de régénération se compose sur sa page : toucher un mode l'ajoute **en fin de cycle**,
le toucher à nouveau le retire, et l'ordre des appuis est celui du cycle. Les cinq modes proposés
sont Faible, Moyen, Fort, Adaptatif et 1 Pédale. On peut tout effacer pour recomposer de zéro ;
rien n'est enregistré avant *Sauvegarder*, qui reste grisé sous deux modes — en dessous, le
raccourci n'aurait plus rien à parcourir après le premier appui. Le réglage est **global** — la
même séquence pour tous les boutons qui déclenchent la fonction — et sans réglage, le comportement
d'origine reste inchangé (Faible → Moyen → Fort → Adaptatif).

### Automatisation
- **Application d'un profil selon la température extérieure** : seuil, sens
  (inférieure/supérieure), profil à appliquer, exécution directe ou popup de confirmation
- **Déclenchement A/C via la température** : deux règles indépendantes (température supérieure /
  inférieure), chacune avec son seuil, sa consigne, sa ventilation, ses dégivrages, le mode
  automatique et la recirculation
- Chaque automatisation est dépliable indépendamment de son interrupteur d'activation

### Gestion de profils
- Sauvegarde jusqu'à **5 profils** personnalisés
- Application instantanée d'un profil en un clic
- Application automatique du profil par défaut **au démarrage du véhicule**
- **Association Bluetooth** : un profil peut être lié à un appareil et s'applique à sa connexion
- **Sauvegarde automatique dans la mémoire du véhicule** : elle survit à la désinstallation de
  l'application, et une popup propose de restaurer les profils à la réinstallation
- Précédence entre déclencheurs : manuel → température → Bluetooth → profil par défaut

### Réglages
Écran organisé en **quatre onglets** :
- **Langues** : français, anglais, allemand, espagnol, italien, portugais
- **Interface** : écran affiché au démarrage, apparence (auto / sombre / clair)
- **Réglages avancés** : **Mode Garage** (cf. ci-dessous), vérification des mises à jour au
  lancement, **alerte de mise à jour sur l'écran du véhicule**, **canal bêta**, extinction du
  véhicule écran allumé, blocage des réglages de conduite au-delà d'une vitesse donnée,
  **API externe** (cf. section dédiée)
- **Infos** : vérification des mises à jour, nettoyage des APK, **consommation de données**
  (aujourd'hui, semaine en cours, mois courant, 30 derniers jours), dialog « À propos » (version de
  l'app, firmware, QR codes), indicateur de firmware, et bouton Diagnostic révélé par 5 clics sur
  le logo

### Mode Garage
Un seul interrupteur met MG4Control **en veille complète**, sans rien désinstaller ni
reconfigurer. Il répond à un cas concret : laisser la voiture à l'atelier sans qu'un technicien
voie des réglages changer seuls au contact, ni un bouton du volant faire autre chose que prévu.

Ce que le mode suspend, ce sont les comportements **autonomes** — ceux que personne n'a demandés
sur le moment :

| Suspendu | Détail |
|---|---|
| Raccourcis classiques | La touche repart au launcher |
| Raccourcis avancés | Les touches réclamées sont **rendues** : le service d'accessibilité ne consomme plus rien |
| Profil au démarrage et au contact | Y compris la résolution Bluetooth |
| Automatisations par température | Profil comme climatisation |
| Baisse de volume à l'ouverture de porte | |
| API externe | Les commandes tierces sont refusées |
| Alerte de mise à jour sur l'écran du véhicule | |

Ce qu'il ne touche pas : l'application elle-même. Ouvrir MG4Control et appliquer un profil à la
main reste possible — c'est une action de l'utilisateur, pas un comportement observable par
quelqu'un qui ne fait que rouler.

**Rien n'est effacé.** Repasser l'interrupteur sur OFF rend l'ensemble à l'identique, profils,
raccourcis et automatisations compris.

La notification persistante affiche « Mode Garage — en veille » tant qu'il est actif : sans ce
repère, un mode oublié se manifesterait par « plus rien ne marche » sans la moindre explication.

> [!NOTE]
> Le Mode Garage **remplace** l'ancien interrupteur « le profil par défaut s'applique
> automatiquement au lancement », dont il est la version complète. Qui l'avait décoché est repris
> en Mode Garage activé à la première ouverture : couper l'application était bien l'intention.

### Alerte de mise à jour sur l'écran du véhicule
Une mise à jour ne se découvre plus seulement en ouvrant MG4Control : un message apparaît
**par-dessus l'infodivertissement**, comme le popup de confirmation de profil, et annonce la
version installée face à la nouvelle. Trois choix :

| Bouton | Effet |
|---|---|
| **Installer la MAJ** | Ouvre MG4Control sur le dialogue de mise à jour habituel, sans refaire la requête réseau |
| **Ignorer cette version** | Cette version n'est plus proposée, ni ici ni au lancement de l'application |
| **Ne plus me prévenir** | Coupe ce popup. Le dialogue au lancement de MG4Control, lui, reste |

Toucher le fond assombri vaut « plus tard » : rien n'est retenu, l'annonce reviendra.

Quatre garde-fous, parce que ce chemin n'est déclenché par personne :
- la vérification a lieu **20 s après le coup de contact** — au moment même, la liaison données de
  la voiture n'est pas encore montée ;
- **six heures minimum** entre deux interrogations réseau, la voiture étant sur un forfait données ;
- **rien ne s'affiche en roulant** (même verrou que le popup multiprofils) ;
- une version déjà proposée ne l'est plus dans la même session — et le marquage n'a lieu que si le
  popup est **réellement apparu**.

L'interrupteur historique « vérifier les mises à jour au lancement » coupe aussi ce popup : c'est
un réglage que l'utilisateur croit global, et il l'est.

### Profils
- Liste des profils avec application, définition par défaut, modification, suppression
- **Éditeur en plein écran** organisé en trois catégories : Conduite, Sécurité, Confort
- Le nom du profil et le réglage « profil par défaut » restent visibles sur les trois onglets
- Volant et sièges chauffants disposent d'un interrupteur de **prise en compte** : décoché, le
  profil ne touche pas au réglage au lieu de l'éteindre

### Compatibilité firmware inconnue (UNKNOWN)
- Dialog d'avertissement au démarrage si le firmware n'est pas reconnu
- L'utilisateur peut fermer l'application ou continuer
- En mode "Continuer", les pastilles de firmware (*Réglages → Infos*) deviennent cliquables pour
  forcer un mode de compatibilité
- Le choix forcé est persisté en SharedPreferences et survit aux redémarrages de l'app

---

## Compatibilité

| Élément | Valeur |
|---------|--------|
| Véhicule cible | MG4 Electric (SAIC) |
| OS | Android Automotive 9+ (API 28+) |
| SoC | SAIC MT2712 |
| Résolution d'écran | 1280 × 480 (orientation paysage forcée) |
| Firmware SWI133 | Compatible ✅ |
| Firmware SWI131 | Compatible ✅ |
| Firmware SWI132 | Compatible ✅ |
| Firmware SWI68 | Compatible ✅ |
| Firmware SWI69 | Compatible ✅ |
| Firmware SWI165 | Compatible ✅ |
| Firmware UNKNOWN | Mode forcé SWI133/SWI132/SWI68/SWI69/SWI131/SWI165 disponible ⚠️ |

---

## Architecture

### Vue d'ensemble

```
┌──────────────────────────────────────────────────────┐
│                    INTERFACE                          │
│  MainActivity ─── NavController ─── Fragment Host   │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │  Dashboard  │  │   Profils    │  │  Réglages   │ │
│  └─────────────┘  └──────────────┘  └─────────────┘ │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│                 COUCHE MÉTIER                         │
│  ProfileManager  ─  ProfileApplier  ─  FirmwareInfo  │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│            ABSTRACTION MATÉRIELLE (MG4Hardware)       │
│  Katman1 (Car API) → Katman2 (Binder) → Katman4      │
│                      (ADAS / SWI133 / SWI68)          │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│              SERVICES SYSTÈME & BOOT                  │
│      MG4ControlService  ─────  BootReceiver          │
└──────────────────────────────────────────────────────┘
```

### Démarrage de l'application

```
Démarrage véhicule
       │
       ▼
BootReceiver.onReceive()
       │
       ▼
MG4ControlService.onCreate()
  └─ MG4Hardware.init()
  └─ Découverte des services Katman1 / Katman4
  └─ Application du profil par défaut (si activé)
       │
       ▼
MainActivity (IHM)
  └─ FirmwareInfo.initWithContext()     ← charge mode forcé (SharedPreferences)
  └─ Détection du firmware (SWI133 / SWI68 / UNKNOWN)
  └─ Configuration de la top bar (chips firmware)
  └─ checkUnknownFirmware()             ← dialog si UNKNOWN et non forcé
  └─ Navigation vers DashboardFragment
```

---

## Structure du projet

```
MG4Control/
├── app/src/main/
│   ├── java/com/mg4/control/
│   │   ├── MG4App.kt                  # Application — mode nuit, locale, journal de plantage
│   │   ├── MainActivity.kt            # Activité principale, top bar, navigation
│   │   │
│   │   ├── model/
│   │   │   ├── DrivingProfile.kt      # Modèle de données d'un profil
│   │   │   ├── DriveMode.kt           # Enum modes de conduite (ECO/NORMAL/SPORT/SNOW/CUSTOM)
│   │   │   ├── RegenLevel.kt          # Enum niveaux de régénération + ordre du cycle par défaut
│   │   │   └── ProfileBackup.kt       # Format de la sauvegarde véhicule
│   │   │
│   │   ├── profile/
│   │   │   ├── ActiveProfile.kt       # Dernier profil appliqué (raccourcis par profil)
│   │   │   ├── ProfileManager.kt      # CRUD profils (SharedPreferences + Gson)
│   │   │   ├── ProfileApplier.kt      # Application des réglages au véhicule (async)
│   │   │   └── ProfileBackupManager.kt# Sauvegarde / restauration en mémoire véhicule
│   │   │
│   │   ├── hardware/
│   │   │   ├── MG4Hardware.kt         # Abstraction matérielle (4 couches)
│   │   │   └── VehicleWriteGate.kt    # Verrou de vitesse posé dans les primitives d'écriture
│   │   │
│   │   ├── accessibility/
│   │   │   ├── KeyCaptureService.kt   # Service d'accessibilité — voit les touches avant le launcher
│   │   │   └── AdvancedShortcuts.kt   # Stockage (touche, type d'appui) → action
│   │   │
│   │   ├── shortcut/
│   │   │   ├── RegenCycle.kt          # Séquence du cycle de régénération choisie par l'utilisateur
│   │   │   └── ShortcutAction.kt      # Catalogue des actions, partagé par les deux systèmes
│   │   │
│   │   ├── bluetooth/
│   │   │   └── BluetoothProfileManager.kt # Profil appliqué à la connexion d'un appareil
│   │   │
│   │   ├── api/
│   │   │   ├── ExternalApi.kt         # Contrat de l'API externe (actions, clés, verrous)
│   │   │   ├── ExternalApiReceiver.kt # Réception des intents tiers
│   │   │   └── StateProvider.kt       # Lecture de l'état (ContentProvider)
│   │   │
│   │   ├── automation/
│   │   │   ├── AutomationSettings.kt        # Profil selon la température
│   │   │   ├── AutomationDecision.kt        # Décision pure (testable hors Android)
│   │   │   ├── ClimateAutomationSettings.kt # Déclenchement A/C
│   │   │   └── ClimateAutomationDecision.kt
│   │   │
│   │   ├── ui/
│   │   │   ├── DashboardFragment.kt   # Écran principal (rail 3 catégories)
│   │   │   ├── ProfileFragment.kt     # Liste des profils
│   │   │   ├── ProfileEditFragment.kt # Éditeur plein écran (rail 3 catégories)
│   │   │   ├── SettingsFragment.kt    # Réglages (rail 4 onglets)
│   │   │   ├── ShortcutsFragment.kt   # Raccourcis classiques + avancés + liste
│   │   │   ├── AutomationFragment.kt  # Automatisations
│   │   │   ├── AudioFragment.kt       # Audio (A9 uniquement)
│   │   │   ├── ProfileAdapter.kt      # Adaptateur RecyclerView profils
│   │   │   ├── ConsoleFragment.kt     # Journal de debug en temps réel
│   │   │   ├── DriveRegenFragment.kt  # Héritage (non utilisé en v2)
│   │   │   ├── ClimateFragment.kt     # Héritage (non utilisé en v2)
│   │   │   └── AdasFragment.kt        # Héritage (non utilisé en v2)
│   │   │
│   │   ├── service/
│   │   │   ├── MG4ControlService.kt   # Service de premier plan (boot, raccourcis, API externe)
│   │   │   ├── ProfilePickerOverlay.kt# Sélecteur de profil en fenêtre flottante
│   │   │   ├── ProfileConfirmOverlay.kt # Confirmation OUI/NON des automatisations
│   │   │   └── UpdateOverlay.kt      # Popup « MAJ disponible » sur l'écran du véhicule
│   │   │
│   │   ├── receiver/
│   │   │   └── BootReceiver.kt        # Récepteur de démarrage système
│   │   │
│   │   ├── util/
│   │   │   ├── FirmwareInfo.kt        # Détection firmware + mode forcé
│   │   │   ├── GarageMode.kt          # Mode Garage — met tous les automatismes en veille
│   │   │   ├── FirmwareHelper.kt      # Lecture version firmware complète (async)
│   │   │   ├── LocaleHelper.kt        # Gestion de la langue (6 langues)
│   │   │   ├── ThemeHelper.kt         # Thème auto / sombre / clair
│   │   │   └── DataUsage.kt           # Consommation de données (interface Ethernet)
│   │   │
│   │   ├── update/
│   │   │   ├── UpdateChecker.kt       # Vérification des releases GitHub (stable et bêta)
│   │   │   ├── UpdateDialogManager.kt # Dialog MAJ + DownloadManager
│   │   │   ├── UpdateNotifier.kt     # Quand signaler une MAJ sur l'écran du véhicule
│   │   │   ├── UpdateInfo.kt          # Description d'une version disponible
│   │   │   ├── ApkSecurity.kt         # Contrôle de signature de l'APK téléchargé
│   │   │   ├── ApkInstaller.kt        # Installation
│   │   │   └── ApkCleanup.kt          # Nettoyage des anciens APK
│   │   │
│   │   └── debug/
│   │       ├── AppLogger.kt           # Buffer de logs en mémoire
│   │       ├── CrashLogger.kt         # Trace de plantage écrite sur disque
│   │       └── DataUsageProbe.kt      # Sonde de diagnostic réseau
│   │
│   ├── res/
│   │   ├── layout/                    # Écrans, dialogs et items de liste
│   │   ├── navigation/nav_graph.xml   # Dashboard → Profils / Réglages / Raccourcis / …
│   │   ├── values/strings.xml         # Chaînes FR (+ values-en, -de, -es, -it, -pt)
│   │   ├── values/colors.xml          # Palette claire
│   │   └── values-night/colors.xml    # Palette sombre (mêmes noms de token)
│   │
│   └── AndroidManifest.xml
│
└── mockup/
    └── index.html                     # Maquette interactive HTML 1280×480
```

---

## Couches matérielles

`MG4Hardware` est organisé en **4 couches d'accès**, du plus haut niveau au plus bas, avec repli automatique en cas d'échec.

### Katman1 — Android Automotive Car API
Couche principale. Utilise les APIs officielles Android Automotive :
- `CarPropertyManager` → modes de conduite, régénération, pédale unique
- `CarHvacManager` → siège chauffant, volant chauffant

La connexion est initialisée par réflexion sur `Car.createCar()` avec plusieurs surcharges tentées successivement. Les actions en attente sont mises en file d'attente et exécutées dès que le service est prêt.

### Katman2 — Raw Binder (fallback)
Repli sur `ServiceManager.getService("vehiclesetting")` avec appels `binderTransact()` directs. Souvent bloqué par SELinux en production.

### Katman4 — Services ADAS (firmware-specific)
Couche dédiée aux fonctions ADAS, chargée dynamiquement selon la génération de firmware :

| Firmware | Service | Mécanisme |
|----------|---------|-----------|
| **SWI133** | `VehiclePropertyManager` | Chargé depuis l'APK launcher via `ClassLoader` + réflexion sur `mIVehiclePropertyService`. Utilise `getMixProperty()` / `setMixProperty()` |
| **SWI68** | `VehicleSettingManager` | Singleton statique chargé via réflexion. Utilise `setAccTjaMode()` / `setLaneKeepingWarningSound()` |
| **SWI69 / SWI131** | `VehicleSettingManager` | Même singleton que SWI68. Utilise `setFcwState()` / `getFcwState()` / `setFcwAutoBrakeMode()` / `setFcwSensitivity()` pour l'AEB. Valeurs confirmées empiriquement sur véhicule réel : `setFcwState(1)` = DÉSACTIVER, `setFcwState(2)` = ACTIVER. |
| **SWI165** | `VehicleSettingManager` | Même SDK que SWI68 (`com.saicmotor.sdk.vehiclesettings`). ADAS via `setAccTjaMode()`. AEB via `setAutoEmergencyBraking(1/2)` comme toggle principal + `setFcwAlarmMode(1/2)` + `setFcwAutoBrakeMode(1/2)`. Modes : 1=OFF, 2=ON. |

### Détection du firmware

```kotlin
// util/FirmwareInfo.kt
FirmwareInfo.initWithContext(context)   // Charge le mode forcé depuis SharedPreferences
val gen = FirmwareInfo.getGeneration()  // Lit ro.build.mt2712.version
// → Gen.SWI133 | Gen.SWI68 | Gen.UNKNOWN

// Si firmware inconnu, l'utilisateur peut forcer un mode :
FirmwareInfo.forceGeneration(context, FirmwareInfo.Gen.SWI133)
FirmwareInfo.isForced(context)          // true si mode forcé actif
FirmwareInfo.getDetectedString()        // Ex : "SWI69-12345" (brut)
```

Le résultat est mis en cache. Si le firmware est `UNKNOWN` et aucun mode forcé, un dialog d'avertissement s'affiche au démarrage. L'utilisateur peut choisir de continuer et forcer SWI133 ou SWI68 via les chips de la top bar.

---

## Système de profils

### Modèle `DrivingProfile`

```kotlin
data class DrivingProfile(
    val id: String,             // UUID unique
    val name: String,           // Nom affiché
    val driveMode: DriveMode,   // ECO / NORMAL / SPORT / SNOW / CUSTOM
    val regenLevel: RegenLevel, // OFF / LOW / MEDIUM / HIGH / ADAPTIVE / ONE_PEDAL
    val steeringHeat: Boolean,
    val seatHeatLeft: Int,      // 0–3
    val seatHeatRight: Int,     // 0–3
    // Prise en compte du chauffage, décorrélée de la valeur : null ou true = appliquer
    val steeringHeatEnabled: Boolean?,
    val seatHeatEnabled: Boolean?,
    // ADAS
    val overspeedAlarm: Boolean,
    val speedLimitTone: Boolean,
    val adasMode: Int,          // 0=Off 1=Lim 2=Auto 3=ACC 4=ICA
    val soundWarning: Boolean,
    val swi68AdasMode: Int,     // Swi68Mode.OFF / ACC / TJA
    val swi132LimiterConfigured: Boolean,
    val swi132SasMode: Int,     // 0=Désactivé 2=Manuel 3=Intelligent
    // Anti-collision avant
    val aebEnabled: Boolean,
    val aebMode: Int,           // 1=Alerte 2=Alerte+Freinage
    val aebSensitivity: Int,    // 0=non configuré 1=Faible 2=Standard 3=Élevé
    // Sortie de voie
    val elkMode: Int,           // 0=non configuré 1=OFF 2=Alerte 3=Aider 5=Maintien
    val elkSensitivity: Int,
    val lasAudibleWarning: Boolean,
    val lasVibrationReminder: Boolean,
    // Divers
    val energySaving: Boolean,
    val tsrEnabled: Boolean,
    val isDefault: Boolean,
    // ESC / somnolence : null = non configuré, le profil n'y touche pas
    val escEnabled: Boolean?,
    val drowsinessEnabled: Boolean?,
    val drowsinessSensitivity: Int?,  // 1=Faible 2=Standard 3=Élevé
    val btDeviceMac: String?          // Appareil Bluetooth associé
)
```

> **Pourquoi ces champs sont nullables :** Gson n'appelle pas le constructeur Kotlin. Un défaut
> déclaré `= true` ne s'appliquerait donc **pas** aux profils déjà enregistrés — champ absent du
> JSON, la JVM met `false`, et l'ESC se retrouverait désactivé sur tous les anciens profils.
> `null` signifie « profil antérieur à cette fonctionnalité », et ce sont les accesseurs qui
> tranchent.

### Persistance

Les profils sont sérialisés en JSON via **Gson** et stockés dans `SharedPreferences`. Maximum **5 profils** par appareil.

### Application d'un profil

`ProfileApplier.apply()` exécute les appels matériels dans l'ordre suivant sur `Dispatchers.IO` :
1. Mode de conduite (rapide — binder)
2. Niveau de régénération (rapide — binder)
3. Volant chauffant (~2 s — polling de confirmation d'état)
4. Siège gauche (~7 s — polling par toggle)
5. Siège droit (~7 s — polling par toggle)
6. Attente Katman4, puis ADAS selon firmware, anti-collision avant, ESC et somnolence, sortie de voie

Les réglages de sécurité sont **omis quand le profil ne les configure pas**, plutôt qu'écrits à une
valeur par défaut : l'écriture de l'ESC est une bascule pilotée par une relecture, viser « ON » à
partir d'une lecture douteuse le désactiverait.

---

## Interface utilisateur

### Navigation
L'application utilise un **NavController** avec **7 destinations** :

```
DashboardFragment (départ)
    ├──► ProfileFragment ──► ProfileEditFragment  (création / édition, plein écran)
    ├──► SettingsFragment
    ├──► ShortcutsFragment
    ├──► AudioFragment        (A9 uniquement)
    └──► AutomationFragment
```

Les boutons de la barre du haut fonctionnent en bascule : un second appui revient au dashboard.

### Rail de catégories
Quatre écrans partagent le même motif : un **rail vertical à gauche** sélectionne une catégorie,
le contenu défile à droite, et ce qui n'appartient à aucune catégorie reste dans un bandeau
persistant (nom du profil, interrupteur maître) ou en pied de page (Annuler / Enregistrer / Fermer).

| Écran | Onglets |
|---|---|
| Dashboard | Conduite · Sécurité · Confort |
| Éditeur de profil | Conduite · Sécurité · Confort |
| Réglages | Langues · Interface · Réglages avancés · Infos |
| Raccourcis | Classiques · Avancés · Liste |

Un onglet dont la page n'a plus aucune section visible sur le firmware courant est **masqué** —
mieux vaut pas d'onglet qu'un onglet qui ouvre une page vide.

### Dimensionnement
Valeurs communes aux écrans refondus, calées sur la lisibilité au volant : titres **20sp**,
en-têtes de section **13sp**, libellés et boutons **16sp**, hauteur de bouton **52dp**, onglets du
rail **64dp**, rail **180dp**, padding de carte **14dp**.

### Palette de couleurs

L'application suit le thème clair ou sombre. Les valeurs claires sont dans
`res/values/colors.xml`, les sombres dans `res/values-night/colors.xml` — **mêmes noms de token
des deux côtés**, c'est la seule règle à respecter en ajoutant une couleur.

| Token | Clair | Sombre | Usage |
|-------|-------|--------|-------|
| `dash_bg` | `#F2F2F7` | `#0C0C0E` | Fond général |
| `dash_card` | `#FFFFFF` | `#141416` | Cartes |
| `dash_section` | `#F2F2F7` | `#1C1C1F` | Sections internes |
| `dash_border` | `#D1D1D6` | `#2A2A2E` | Bordures et séparateurs |
| `dash_btn` | `#E5E5EA` | `#222226` | Fond de bouton inactif |
| `dash_text_lo` | `#8E8E93` | `#52525B` | En-têtes de section |
| `dash_accent` | `#0284C7` | `#38BDF8` | Sélection active (bleu) |
| `dash_accent_dim` | `#E0F2FE` | `#0C4A6E` | Fond de la sélection active |
| `dash_eco` | `#16A34A` | `#22C55E` | Mode ECO (vert) |
| `dash_warn` | `#D97706` | `#F59E0B` | Avertissement (orange) |
| `dash_danger` | `#E11D48` | `#F43F5E` | Suppression / danger |
| `text_primary` | `#1C1C1E` | `#FFFFFF` | Texte principal |
| `text_secondary` | `#6C6C70` | `#B0B0B0` | Texte secondaire |

Chaque couleur `*_dim` est le fond associé à sa couleur vive : `dash_eco_dim`, `dash_warn_dim` et
`dash_danger_dim` suivent le même principe que `dash_accent_dim`.

> **Piège de nommage :** `bg_dark` vaut `#FFFFFF` en thème clair. Le nom date d'une époque où
> l'application n'avait qu'un thème sombre ; il désigne le fond général, pas une couleur foncée.

---

## API externe (KeyMapper, Tasker…)

Permet à une application tierce de déclencher les fonctions de MG4Control (issue #79).

> **Désactivée par défaut.** Elle s'active dans *Réglages → Réglages avancés → « API externe »*,
> avec une confirmation explicite. Tant qu'elle est désactivée, toute commande reçue est refusée
> et journalisée. Une fois activée, **n'importe quelle application installée** peut envoyer ces
> intents : ils ne sont protégés par aucune permission, car KeyMapper et Tasker viennent du Play
> Store et ne peuvent pas en détenir une de niveau `signature`.

### Actions directes — une action d'intent par commande

Aucun extra requis : c'est la forme utilisable depuis **KeyMapper**, dont l'éditeur d'intent ne
propose que le type (*Broadcast receiver*) et la chaîne d'action.

| Action | Effet |
|---|---|
| `com.mg4.control.action.ONE_PEDAL` | Bascule 1 pédale ↔ niveau de repli |
| `com.mg4.control.action.ENERGY_SAVING_TOGGLE` | Économie d'énergie |
| `com.mg4.control.action.PROFILE_PICKER` | Ouvre le sélecteur de profil à l'écran |
| `com.mg4.control.action.OPEN_APP` | Ouvre MG4Control |

Ce sont des **bascules** : chaque envoi inverse l'état, il n'existe pas de « mettre à ON ».

> **Commandes volontairement hors API.** `VEHICLE_POWER_OFF`, `ADAS_CYCLE`, `AEB_CYCLE`,
> `TSR_TOGGLE`, `OVERSPEED_ALARM`, `SPEED_LIMIT_TONE` et `SOUND_WARNING` ne sont **pas** exposées :
> elles touchent à la sécurité active ou coupent le véhicule. Le refus s'applique aussi à
> `EXECUTE` — les retirer des seules actions directes n'aurait rien protégé. Elles restent
> pilotables depuis l'application et les raccourcis volant.

**Dans KeyMapper** : ajouter une action → *Intent* (version 2.3.0 minimum) → type
**Broadcast receiver** → coller la chaîne dans le champ *Action*.

### `EXECUTE` — pour Tasker, adb, scripts

`com.mg4.control.action.EXECUTE` avec un extra texte `action` valant **le nom d'une action de
raccourci**, à l'exception de celles listées ci-dessus comme hors API. Cela couvre donc bien plus
que les quatre actions directes : `REGEN_CYCLE`, `SEAT_HEAT_LEFT_CYCLE`, `HVAC_RECIRC_CYCLE`,
`BRIGHTNESS_UP`… — les noms exacts sont ceux de `shortcut/ShortcutAction.kt`.

Deux d'entre elles réclament un extra, et ne peuvent donc pas exister en action directe :

- `APPLY_PROFILE` — exige un extra `profile` : le nom du profil, insensible à la casse
- `OPEN_CUSTOM_APP` — ouvre l'application configurée dans les raccourcis

```bash
adb shell am broadcast -a com.mg4.control.action.EXECUTE \
  --es action APPLY_PROFILE --es profile "Trajet domicile"
```

### `SET` — écriture directe d'une valeur

`com.mg4.control.action.SET` avec les extras `key` et `value` :

| `key` | `value` accepté |
|---|---|
| `drive_mode` | `ECO` `NORMAL` `SPORT` `SNOW` `CUSTOM` |
| `regen` | `OFF` `LOW` `MEDIUM` `HIGH` `ADAPTIVE` `ONE_PEDAL` |
| `seat_heat_left` | `0` à `3` |
| `seat_heat_right` | `0` à `3` |
| `steering_heat` | `0`/`1` ou `false`/`true` |
| `profile` | nom du profil |
| `hvac_power` | `0`/`1` — marche/arrêt de la clim |
| `ac` | `0`/`1` — compresseur A/C |
| `hvac_auto` | `0`/`1` — mode automatique |
| `hvac_temp` | °C, clampé aux bornes réelles du véhicule |
| `hvac_fan` | niveau de ventilation, clampé aux bornes réelles |
| `hvac_recirc` | `INNER` `OUTSIDE` `AUTO` (ou `0` `1` `2`) |
| `defrost_front` | `0`/`1` |
| `defrost_rear` | `0`/`1` |

Les clés `hvac_*` et `defrost_*` sont ignorées si le firmware n'expose pas la climatisation.
Consigne et ventilation sont clampées aux bornes **lues sur le véhicule**, qui diffèrent d'un
firmware à l'autre. Ces commandes sont des bascules matérielles qui avancent d'un cran à la fois :
comptez quelques secondes avant que l'état final soit atteint.

```bash
adb shell am broadcast -a com.mg4.control.action.SET --es key drive_mode --es value SPORT
```

#### `NEXT` / `PREV` / `TOGGLE` — cycler sans connaître l'état

À la place d'une consigne, `value` accepte **`NEXT`** (cran suivant), **`PREV`** (cran précédent)
ou **`TOGGLE`** (alias de `NEXT`, plus lisible sur un booléen). La nouvelle valeur est calculée à
partir de l'état lu sur le véhicule, ce qui permet d'assigner « siège chauffant +1 » à un seul
bouton de volant. Le cycle **reboucle** : au maximum, le cran suivant revient au minimum.

```bash
adb shell am broadcast -a com.mg4.control.action.SET --es key seat_heat_left --es value NEXT
adb shell am broadcast -a com.mg4.control.action.SET --es key ac --es value TOGGLE
```

Clés cyclables : `seat_heat_left`, `seat_heat_right`, `steering_heat`, `hvac_power`, `ac`,
`hvac_auto`, `hvac_temp`, `hvac_fan`, `hvac_recirc`, `defrost_front`, `defrost_rear`.

`drive_mode`, `regen` et `profile` en sont **volontairement exclus** : l'énumération des modes de
conduite n'est pas filtrée par firmware (on cyclerait vers un mode absent du véhicule), la
disponibilité de la régénération dépend de l'état courant (aucun niveau en mode Neige, One Pedal
seul quand Éco énergie est actif), et il n'existe pas de notion de « profil courant ».

Si l'état courant est illisible, la commande est **refusée sans rien écrire** plutôt que de partir
d'une valeur supposée — un point de départ deviné ferait descendre la valeur alors que vous
appuyez pour la monter. Le refus est journalisé (`adb logcat -s MG4_API`).

### Lecture de l'état — ContentProvider

`content://com.mg4.control.state/state` (ou `com.mg4.control.offline.state` pour la variante
offline — l'authority suit l'applicationId). Un curseur d'**une** ligne :

`drive_mode`, `regen`, `seat_heat_left`, `seat_heat_right`, `steering_heat`, `speed_kmh`,
`outside_temp_c`, `tsr`, `energy_saving`, `aeb_enabled`, `firmware`, `profiles` (noms séparés
par `|`), `default_profile`.

Une valeur illisible vaut `null`, jamais `0` — un zéro se confondrait avec « siège éteint » ou
« véhicule à l'arrêt ». Tasker sait interroger un ContentProvider, KeyMapper non.

Contrairement aux broadcasts, un provider connaît son appelant : chaque lecture est journalisée
nominativement, et la préférence `external_api_allowlist` (liste de paquets séparés par des
virgules, vide = tous acceptés) est réellement appliquée.

### Sécurité et diagnostic

Le **verrou de vitesse** (*Réglages → « Bloquer les réglages de conduite au-delà d'une certaine
vitesse »*) s'applique aussi à l'API, puisqu'il est posé dans les primitives d'écriture. Attention :
il est lui-même **désactivé par défaut** — s'il ne l'est pas, aucune limite de vitesse ne
s'applique aux commandes externes. Le confort (sièges, volant chauffants) n'est jamais concerné.

Toute commande, acceptée ou refusée, est tracée au tag **`MG4_API`** (visible via le bouton
Diagnostic). Pour tester l'application indépendamment de KeyMapper :

```bash
adb shell am broadcast -a com.mg4.control.action.PROFILE_PICKER
```

Silence complet = APK pas à jour ou service arrêté. `REFUS … API externe désactivée` =
l'interrupteur des Réglages n'a pas été confirmé.

---

## Compilation et installation

Vous pouvez directement télécharger la dernière version de MG4Control via les releases : https://github.com/SliDeeN/MG4Control/releases
Il ne vous faut qu'une clé USB et l'accès aux paramètres AAOS afin d'installer l'APK.


Vous pouvez aussi compiler vous même le projet :

### Prérequis
- Android Studio Hedgehog (2023.1) ou supérieur
- JDK 17+
- Android SDK API 34

### Build debug

```bash
# Avec le JDK d'Android Studio
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew assembleDebug
```

L'APK se trouve dans :
```
app/build/outputs/apk/debug/app-debug.apk
```

### Installation sur le véhicule

L'application nécessite d'être signée avec la clé système de la ROM. Sur un système de développement :

```bash
adb push app-debug.apk /sdcard/
adb shell pm install -r --system /sdcard/app-debug.apk
```

> Sur une ROM de production, l'APK doit être incluse dans le build système ou installée via un mécanisme OEM spécifique.

---

## Permissions requises

| Permission | Justification |
|-----------|---------------|
| `FOREGROUND_SERVICE` | Service de premier plan pour l'auto-apply |
| `WAKE_LOCK` | Empêche le sleep pendant l'application des réglages |
| `RECEIVE_BOOT_COMPLETED` | Démarrage automatique au boot |
| `CAR_POWERTRAIN` | Contrôle du mode de conduite et de la régénération |
| `CONTROL_CAR_CLIMATE` | Contrôle des sièges et du volant chauffants |
| `CAR_VENDOR_EXTENSION` | Extensions propriétaires SAIC |
| `CAR_ENERGY` | Informations batterie / motorisation |
| `INTERNET` | Vérification des mises à jour (GitHub API) |
| `DOWNLOAD_WITHOUT_NOTIFICATION` | Téléchargement silencieux de l'APK de mise à jour |
| `WRITE_EXTERNAL_STORAGE` | Enregistrement APK dans le dossier Téléchargements |

---

## Licence

| Versions | Licence |
|---|---|
| jusqu'à **2.6.5** incluse | MIT — [`LICENSE.MIT`](LICENSE.MIT) |
| à partir de **2.6.6** | **GNU GPL v3** ou ultérieure — [`COPYING`](COPYING) |

Les versions déjà publiées restent sous MIT : une licence accordée ne se révoque pas. À partir de
la 2.6.6, toute version modifiée que vous redistribuez doit l'être sous GPL, **code source
inclus**.

L'avis MIT est conservé pour le code publié sous ce régime avant le basculement, contributions de
tiers comprises. Détails dans [`LICENCE.md`](LICENCE.md).

</details>

---

<details open>
<summary><strong>🇬🇧 English</strong></summary>

## Table of Contents
1. [Overview](#overview)
2. [Features](#features)
3. [Compatibility](#compatibility)
4. [Architecture](#architecture)
5. [Project Structure](#project-structure)
6. [Hardware Layers](#hardware-layers)
7. [Profile System](#profile-system)
8. [User Interface](#user-interface)
9. [External API](#external-api-keymapper-tasker)
10. [Build & Installation](#build--installation)
11. [Required Permissions](#required-permissions)
12. [Licence](#licence-1)

---

## Overview

**MG4Control** is a system-level application designed for Android Automotive OS, intended to run on the head unit of MG4 electric vehicles equipped with the **SAIC MT2712** SoC. It provides direct, unified access to driving settings that are unavailable — or poorly accessible — through the stock manufacturer interface.

The app communicates with the vehicle through the proprietary SAIC SDK, accessing Android Automotive services (`CarPropertyManager`, `CarHvacManager`) as well as low-level services exposed by the vehicle's firmware.

> **Important:** This application requires system privileges (`sharedUserId="android.uid.system"`) and must be signed with the ROM's platform key. It cannot run on a standard unlocked device.

> [!WARNING]
> **MG4Control is an independent community project. It is in no way affiliated with, endorsed by, or supported by MG Motor, SAIC Motor, or any of their subsidiaries.**
> Use this application entirely at your own risk. Incorrect settings may affect vehicle behaviour. Proceed with caution.

---

## Features

### Driving Settings
- **Drive mode**: ECO / NORMAL / SPORT / SNOW / CUSTOM
- **Regenerative braking**: Off / Low / Medium / High / Adaptive / One Pedal

### Safety
- **ESC**: ON / OFF
- **Drowsiness warning**: ON / OFF, with Low / Standard / High sensitivity
- **Forward collision warning (AEB)**: ON / OFF, Alert only or Alert + Braking, sensitivity
- **Lane keeping assist (ELK)**: Alert / Assist / Emergency keeping, sensitivity, plus the audible
  warning and vibration on SWI132

> [!WARNING]
> Turning the ESC off also turns the forward collision system off — the vehicle enforces that.
> Both return to ON at every start-up. Inside a profile, ESC and drowsiness are written **only if
> that profile configures them**: a profile created before these settings existed leaves them alone.

### Comfort
- **Heated steering wheel**: On / Off
- **Heated seats (left & right)**: Off / Level 1 / 2 / 3
- **Climate control**: temperature setpoint, fan speed, power, A/C, AUTO, recirculation
  (inner / outside / auto), front and rear defrost
- **Screen brightness**
- **Door-opening volume**: lowers media volume when a front door opens, with selectable trigger
  doors and restore on close
- **Audio** (A9 firmwares): Bose sound type, balance, fader, speed-dependent volume

### ADAS (Advanced Driver Assistance)
- **SWI133**: Off / Speed Limiter / Auto / ACC / ICA + overspeed alert / speed limit change alert
- **SWI68**: Disable / ACC / TJA + audible warning On / Off
- **SWI69 / SWI131**: Forward Collision Warning (AEB) — On / Off + mode Alert only / Alert + Emergency Braking
- **SWI165**: Disable / ACC / TJA + Forward Collision Warning (AEB) On/Off + Alert / Alert+Braking mode + audible warning

### Physical Button Shortcuts

Two systems live side by side, configured from the same screen:

- **Classic shortcuts** — the left and right ★ buttons, short or long press. They rely on the
  broadcast the vehicle emits: if the stock launcher already uses that button, the launcher wins.
- **Advanced shortcuts** — any physical key, on the steering wheel **as well as on the panel
  below the screen**, as a **short, long or double** press, through an **accessibility service**
  the user enables themselves in Android. MG4Control then sees the key **before** the launcher and
  can consume it.

A long press fires **as soon as the 500 ms threshold is reached**, without waiting for the release:
the action always happens at the same moment, while the key is still held. A double press has a
cost worth knowing — on a key carrying one, the short press can no longer fire on release, it waits
another 300 ms to make sure no second press follows. Keys without a double press pay nothing.

> [!WARNING]
> A key registered as an advanced shortcut is claimed **as a whole**. Intercepting a long press
> means consuming the press as it starts, when it is still unknown whether it will be short, long
> or double. Press types left unassigned therefore do *not* fall back to the launcher: they simply
> do nothing.

Available actions — those depending on the firmware do not show up on the others:

| Category | Actions |
|---|---|
| Driving | One Pedal · Custom Regeneration Cycle · Energy saving |
| Safety | ESC · Drowsiness · Drowsiness: sensitivity · Forward collision · ADAS · Traffic signs (TSR) · Overspeed alert · Speed limit change alert · Sound |
| Comfort | Left seat heating · Right seat heating · Heated steering · Climate ON/OFF · Climate: temperature ± · Climate: fan ± · Front defrost · Rear defrost · Recirculation · Brightness ± |
| Media | Play / Pause · Next track · Previous track · Volume + · Volume - |
| App and vehicle | Apply a profile · Profile picker · Open MG4Control · Launch an app · Power the car off |

Comfort and safety actions **re-read the state from the vehicle on every press** instead of
remembering their own: the user also acts from the stock screen, so a remembered state would drift
immediately. An unreadable state means doing nothing — nothing is ever written blind.

Each row of the advanced list shows the **button** (name and code), the **press type**, the
**action**, then *Edit* and *Delete*. Reassigning a button already used with the same press type
asks for confirmation and names the action about to be replaced.

#### Profile-scoped shortcuts
An advanced shortcut carries a key, a press type, an action — and a **profile**, "All profiles" by
default. The same key and press type can therefore carry several shortcuts, one per profile: under
*Sport* the button does one thing, under *Winter* another.

Resolution on press happens in two steps, in this order:
1. a shortcut reserved for the **active profile**;
2. otherwise the one marked **All profiles**;
3. otherwise nothing.

Case 3 is worth knowing: the key is still **consumed**, like any press type left unassigned on a
claimed key — it does not fall back to the launcher. The list flags this on groups that have no
"All profiles" row.

The **active profile** is the last one applied, whatever the source: startup, ignition, Bluetooth,
temperature automation or a manual choice. It is stored, so it survives a service restart.

> [!NOTE]
> It is the last profile **applied**, not the actual state of the vehicle: a setting changed by
> hand from the Dashboard does not invalidate it. The alternative — comparing the vehicle state
> against every profile — would cost a dozen vehicle reads on every key press, for an ambiguous
> result as soon as two profiles look alike.

The shortcut list is **grouped by key + press type**, "All profiles" first in each group: the
display order is the resolution order.

The three action settings — **One Pedal fallback**, **ADAS cycle notches** and the **regeneration
cycle** — each get their own page, revealed in the left rail as soon as the action is assigned to a
button. However it was assigned: a classic slot, an advanced shortcut, or an action merely selected
in the advanced form, before the shortcut even exists.

The regeneration cycle is composed on its page: tapping a mode appends it **at the end of the
cycle**, tapping it again removes it, and the order of your taps is the order of the cycle. The five
modes on offer are Low, Medium, High, Adaptive and One Pedal. You can clear everything and start
over; nothing is stored until *Save*, which stays greyed out below two modes — under that, the
shortcut would have nothing left to walk through after the first press. The setting is **global** —
the same sequence for every button triggering the action — and with no setting at all, the original
behaviour stands unchanged (Low → Medium → High → Adaptive).

### Automation
- **Apply a profile from the outside temperature**: threshold, direction (below/above), profile to
  apply, direct execution or confirmation popup
- **Temperature-triggered A/C**: two independent rules (above / below), each with its threshold,
  setpoint, fan level, defrosters, automatic mode and recirculation
- Each automation folds open independently of its enable switch

### Profile Management
- Save up to **5 custom profiles**
- Instant one-tap profile application
- Automatic default profile application **on vehicle startup**
- **Bluetooth pairing**: a profile can be tied to a device and applied when it connects
- **Automatic backup into the vehicle's storage**: it survives uninstalling the app, and a popup
  offers to restore the profiles on reinstall
- Trigger precedence: manual → temperature → Bluetooth → default profile

### Settings
Screen organised into **four tabs**:
- **Languages**: French, English, German, Spanish, Italian, Portuguese
- **Interface**: screen shown at startup, appearance (auto / dark / light)
- **Advanced**: **Garage mode** (see below), update check at launch, **update alert on the
  vehicle screen**, **beta channel**, power the car off while keeping the screen on, block driving
  settings above a given speed, **external API** (see the dedicated section)
- **Info**: update check, APK cleanup, **data usage** (today, current week, current month, last 30
  days), "About" dialog (app version, firmware, QR codes), firmware indicator, and a Diagnostic
  button revealed by 5 taps on the logo

### Garage mode
A single switch puts MG4Control **fully to sleep**, without uninstalling or reconfiguring
anything. It answers a concrete case: leaving the car at a workshop without a technician seeing
settings change on their own at ignition, or a steering-wheel button doing something unexpected.

What the mode suspends are the **autonomous** behaviours — the ones nobody asked for at that
moment:

| Suspended | Detail |
|---|---|
| Classic shortcuts | The key goes back to the launcher |
| Advanced shortcuts | Claimed keys are **released**: the accessibility service consumes nothing |
| Profile at startup and at ignition | Bluetooth resolution included |
| Temperature automations | Profile and climate alike |
| Volume drop when a door opens | |
| External API | Third-party commands are refused |
| Update alert on the vehicle screen | |

What it does not touch: the app itself. Opening MG4Control and applying a profile by hand still
works — that is a user action, not a behaviour observable by someone who is only driving.

**Nothing is erased.** Switching it back off restores everything as it was: profiles, shortcuts
and automations.

The persistent notification reads "Garage mode — asleep" while it is on: without that marker, a
forgotten mode would show up as "nothing works any more" with no explanation whatsoever.

> [!NOTE]
> Garage mode **replaces** the old "the default profile is applied automatically at launch"
> switch, of which it is the complete version. Anyone who had unchecked it is carried over into
> Garage mode on first launch: stopping the app from acting was the intent.

### Update alert on the vehicle screen
An update is no longer found only by opening MG4Control: a message appears **over the
infotainment**, like the profile confirmation popup, showing the installed version against the new
one. Three choices:

| Button | Effect |
|---|---|
| **Install update** | Opens MG4Control on the usual update dialog, without repeating the network request |
| **Skip this version** | That version is no longer offered, here or at app launch |
| **Stop telling me** | Turns this popup off. The dialog at MG4Control launch stays |

Tapping the dimmed background means "later": nothing is remembered, the alert will come back.

Four safeguards, because nobody triggers this path:
- the check runs **20 s after ignition** — at the moment itself the car's data link is not up yet;
- **six hours minimum** between network requests, the car being on a data plan;
- **nothing shows while driving** (same lock as the multi-profile popup);
- a version already offered is not offered again in the same session — and it is only marked as
  offered if the popup **actually appeared**.

The long-standing "check for updates at launch" switch also turns this popup off: it is a setting
users read as global, and it is.

### Profiles
- Profile list with apply, set as default, edit and delete
- **Full-screen editor** organised into three categories: Driving, Safety, Comfort
- The profile name and the "default profile" setting stay visible across the three tabs
- Heated steering wheel and seats have an **apply** switch: unchecked, the profile leaves the
  setting alone instead of turning it off

### Unknown firmware (UNKNOWN)
- Warning dialog at startup when the firmware is not recognised
- The user can close the app or continue
- In "continue" mode the firmware chips (*Settings → Info*) become tappable to force a
  compatibility mode
- The forced choice is persisted in SharedPreferences and survives app restarts

---

## Compatibility

| Item | Value |
|------|-------|
| Target vehicle | MG4 Electric (SAIC) |
| OS | Android Automotive 9+ (API 28+) |
| SoC | SAIC MT2712 |
| Screen resolution | 1280 × 480 (forced landscape) |
| Firmware SWI133 | Compatible ✅ |
| Firmware SWI131 | Compatible ✅ |
| Firmware SWI132 | Compatible ✅ |
| Firmware SWI68 | Compatible ✅ |
| Firmware SWI69 | Compatible ✅ |
| Firmware SWI165 | Compatible ✅ |
| UNKNOWN firmware | Forced SWI133/SWI132/SWI68/SWI69/SWI131/SWI165 mode available ⚠️ |

---

## Architecture

### Overview

```
┌──────────────────────────────────────────────────────┐
│                      UI LAYER                         │
│  MainActivity ─── NavController ─── Fragment Host    │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │  Dashboard  │  │   Profiles   │  │  Settings   │ │
│  └─────────────┘  └──────────────┘  └─────────────┘ │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│                  BUSINESS LOGIC                       │
│  ProfileManager  ─  ProfileApplier  ─  FirmwareInfo  │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│           HARDWARE ABSTRACTION (MG4Hardware)          │
│  Katman1 (Car API) → Katman2 (Binder) → Katman4      │
│                      (ADAS / SWI133 / SWI68)          │
└──────────────────────────────────────────────────────┘
                         │
┌──────────────────────────────────────────────────────┐
│               SYSTEM SERVICES & BOOT                  │
│       MG4ControlService  ─────  BootReceiver         │
└──────────────────────────────────────────────────────┘
```

### Startup Sequence

```
Vehicle boot
       │
       ▼
BootReceiver.onReceive()
       │
       ▼
MG4ControlService.onCreate()
  └─ MG4Hardware.init()
  └─ Katman1 / Katman4 service discovery
  └─ Apply default profile (if enabled)
       │
       ▼
MainActivity (UI)
  └─ Firmware detection (SWI133 / SWI68)
  └─ Top bar setup
  └─ Navigate to DashboardFragment
```

---

## Project Structure

```
MG4Control/
├── app/src/main/
│   ├── java/com/mg4/control/
│   │   ├── MG4App.kt                  # Application — night mode, locale, crash log
│   │   ├── MainActivity.kt            # Main activity, top bar, navigation
│   │   │
│   │   ├── model/
│   │   │   ├── DrivingProfile.kt      # Profile data model
│   │   │   ├── DriveMode.kt           # Drive mode enum (ECO/NORMAL/SPORT/SNOW/CUSTOM)
│   │   │   ├── RegenLevel.kt          # Regen level enum + default shortcut cycle order
│   │   │   └── ProfileBackup.kt       # Vehicle backup format
│   │   │
│   │   ├── profile/
│   │   │   ├── ActiveProfile.kt       # Last applied profile (profile-scoped shortcuts)
│   │   │   ├── ProfileManager.kt      # Profile CRUD (SharedPreferences + Gson)
│   │   │   ├── ProfileApplier.kt      # Applies settings to the vehicle (async)
│   │   │   └── ProfileBackupManager.kt# Backup / restore in vehicle storage
│   │   │
│   │   ├── hardware/
│   │   │   ├── MG4Hardware.kt         # Hardware abstraction (4 layers)
│   │   │   └── VehicleWriteGate.kt    # Speed lock, enforced inside the write primitives
│   │   │
│   │   ├── accessibility/
│   │   │   ├── KeyCaptureService.kt   # Accessibility service — sees keys before the launcher
│   │   │   └── AdvancedShortcuts.kt   # Storage for (key, press type) → action
│   │   │
│   │   ├── shortcut/
│   │   │   ├── RegenCycle.kt          # User-composed regeneration cycle sequence
│   │   │   └── ShortcutAction.kt      # Action catalogue, shared by both systems
│   │   │
│   │   ├── bluetooth/
│   │   │   └── BluetoothProfileManager.kt # Profile applied when a device connects
│   │   │
│   │   ├── api/
│   │   │   ├── ExternalApi.kt         # External API contract (actions, keys, locks)
│   │   │   ├── ExternalApiReceiver.kt # Third-party intent reception
│   │   │   └── StateProvider.kt       # State reading (ContentProvider)
│   │   │
│   │   ├── automation/
│   │   │   ├── AutomationSettings.kt        # Profile from temperature
│   │   │   ├── AutomationDecision.kt        # Pure decision (testable off-device)
│   │   │   ├── ClimateAutomationSettings.kt # A/C triggering
│   │   │   └── ClimateAutomationDecision.kt
│   │   │
│   │   ├── ui/
│   │   │   ├── DashboardFragment.kt   # Main screen (3-category rail)
│   │   │   ├── ProfileFragment.kt     # Profile list
│   │   │   ├── ProfileEditFragment.kt # Full-screen editor (3-category rail)
│   │   │   ├── SettingsFragment.kt    # Settings (4-tab rail)
│   │   │   ├── ShortcutsFragment.kt   # Classic + advanced shortcuts + list
│   │   │   ├── AutomationFragment.kt  # Automations
│   │   │   ├── AudioFragment.kt       # Audio (A9 only)
│   │   │   ├── ProfileAdapter.kt      # Profile RecyclerView adapter
│   │   │   ├── ConsoleFragment.kt     # Real-time debug log viewer
│   │   │   ├── DriveRegenFragment.kt  # Legacy (unused in v2)
│   │   │   ├── ClimateFragment.kt     # Legacy (unused in v2)
│   │   │   └── AdasFragment.kt        # Legacy (unused in v2)
│   │   │
│   │   ├── service/
│   │   │   ├── MG4ControlService.kt   # Foreground service (boot, shortcuts, external API)
│   │   │   ├── ProfilePickerOverlay.kt# Floating profile picker
│   │   │   ├── ProfileConfirmOverlay.kt # YES/NO confirmation for automations
│   │   │   └── UpdateOverlay.kt      # "Update available" popup on the vehicle screen
│   │   │
│   │   ├── receiver/
│   │   │   └── BootReceiver.kt        # System boot receiver
│   │   │
│   │   ├── util/
│   │   │   ├── FirmwareInfo.kt        # Firmware detection + forced mode
│   │   │   ├── GarageMode.kt          # Garage mode — puts every automatism to sleep
│   │   │   ├── FirmwareHelper.kt      # Full firmware version string reader (async)
│   │   │   ├── LocaleHelper.kt        # Language management (6 languages)
│   │   │   ├── ThemeHelper.kt         # Auto / dark / light theme
│   │   │   └── DataUsage.kt           # Data usage (Ethernet interface)
│   │   │
│   │   ├── update/
│   │   │   ├── UpdateChecker.kt       # GitHub release check (stable and beta)
│   │   │   ├── UpdateDialogManager.kt # Update dialog + DownloadManager
│   │   │   ├── UpdateNotifier.kt     # When to announce an update on the vehicle screen
│   │   │   ├── UpdateInfo.kt          # Description of an available version
│   │   │   ├── ApkSecurity.kt         # Signature check of the downloaded APK
│   │   │   ├── ApkInstaller.kt        # Installation
│   │   │   └── ApkCleanup.kt          # Old APK cleanup
│   │   │
│   │   └── debug/
│   │       ├── AppLogger.kt           # In-memory log ring buffer
│   │       ├── CrashLogger.kt         # Crash trace written to disk
│   │       └── DataUsageProbe.kt      # Network diagnostic probe
│   │
│   ├── res/
│   │   ├── layout/                    # Screens, dialogs and list items
│   │   ├── navigation/nav_graph.xml   # Dashboard → Profiles / Settings / Shortcuts / …
│   │   ├── values/strings.xml         # French strings (+ values-en, -de, -es, -it, -pt)
│   │   ├── values/colors.xml          # Light palette
│   │   └── values-night/colors.xml    # Dark palette (same token names)
│   │
│   └── AndroidManifest.xml
│
└── mockup/
    └── index.html                     # Interactive HTML mockup (1280×480)
```

---

## Hardware Layers

`MG4Hardware` is organized into **4 access layers**, from highest to lowest level, with automatic fallback on failure.

### Katman1 — Android Automotive Car API
Primary layer. Uses official Android Automotive APIs:
- `CarPropertyManager` → drive modes, regeneration, one-pedal
- `CarHvacManager` → seat heating, steering wheel heating

The connection is initialized via reflection on `Car.createCar()` with multiple overloads tried in sequence. Pending actions are queued and executed once the service is ready, with exponential backoff retry (2 s → 60 s).

### Katman2 — Raw Binder (fallback)
Falls back to `ServiceManager.getService("vehiclesetting")` with direct `binderTransact()` calls. Usually blocked by SELinux in production builds.

### Katman4 — ADAS Services (firmware-specific)
Dedicated layer for ADAS functions, dynamically loaded according to the detected firmware generation:

| Firmware | Service | Mechanism |
|----------|---------|-----------|
| **SWI133** | `VehiclePropertyManager` | Loaded from the launcher APK via `ClassLoader` + reflection on `mIVehiclePropertyService`. Uses `getMixProperty()` / `setMixProperty()` |
| **SWI68** | `VehicleSettingManager` | Static singleton loaded via reflection. Uses `setAccTjaMode()` / `setLaneKeepingWarningSound()` |
| **SWI69 / SWI131** | `VehicleSettingManager` | Same singleton as SWI68. Uses `setFcwState()` / `getFcwState()` / `setFcwAutoBrakeMode()` / `setFcwSensitivity()` for AEB. Values confirmed empirically on real hardware: `setFcwState(1)` = DISABLE, `setFcwState(2)` = ENABLE. |
| **SWI165** | `VehicleSettingManager` | Same SDK as SWI68 (`com.saicmotor.sdk.vehiclesettings`). ADAS via `setAccTjaMode()`. AEB via `setAutoEmergencyBraking(1/2)` as the main toggle + `setFcwAlarmMode(1/2)` + `setFcwAutoBrakeMode(1/2)`. Values: 1=OFF, 2=ON. |

### Firmware Detection

```kotlin
// util/FirmwareInfo.kt
val gen = FirmwareInfo.getGeneration()  // Reads ro.build.mt2712.version
// → Gen.SWI133 | Gen.SWI68 | Gen.UNKNOWN
```

The result is cached and used throughout the app to branch firmware-specific code paths.

---

## Profile System

### `DrivingProfile` Model

```kotlin
data class DrivingProfile(
    val id: String,             // Unique UUID
    val name: String,           // Display name
    val driveMode: DriveMode,   // ECO / NORMAL / SPORT / SNOW / CUSTOM
    val regenLevel: RegenLevel, // OFF / LOW / MEDIUM / HIGH / ADAPTIVE / ONE_PEDAL
    val steeringHeat: Boolean,
    val seatHeatLeft: Int,      // 0–3
    val seatHeatRight: Int,     // 0–3
    // Whether heating is applied at all, decoupled from its value: null or true = apply
    val steeringHeatEnabled: Boolean?,
    val seatHeatEnabled: Boolean?,
    // ADAS
    val overspeedAlarm: Boolean,
    val speedLimitTone: Boolean,
    val adasMode: Int,          // 0=Off 1=Limiter 2=Auto 3=ACC 4=ICA
    val soundWarning: Boolean,
    val swi68AdasMode: Int,     // Swi68Mode.OFF / ACC / TJA
    val swi132LimiterConfigured: Boolean,
    val swi132SasMode: Int,     // 0=Off 2=Manual 3=Smart
    // Forward collision
    val aebEnabled: Boolean,
    val aebMode: Int,           // 1=Alert 2=Alert+Braking
    val aebSensitivity: Int,    // 0=unset 1=Low 2=Standard 3=High
    // Lane keeping
    val elkMode: Int,           // 0=unset 1=OFF 2=Alert 3=Assist 5=Emergency
    val elkSensitivity: Int,
    val lasAudibleWarning: Boolean,
    val lasVibrationReminder: Boolean,
    // Misc
    val energySaving: Boolean,
    val tsrEnabled: Boolean,
    val isDefault: Boolean,
    // ESC / drowsiness: null = unset, the profile leaves them alone
    val escEnabled: Boolean?,
    val drowsinessEnabled: Boolean?,
    val drowsinessSensitivity: Int?,  // 1=Low 2=Standard 3=High
    val btDeviceMac: String?          // Paired Bluetooth device
)
```

> **Why these fields are nullable:** Gson does not call the Kotlin constructor. A declared default
> of `= true` would therefore **not** apply to already-saved profiles — the field is missing from
> the JSON, the JVM writes `false`, and the ESC would end up disabled on every old profile. `null`
> means "profile older than this feature", and the accessors decide what to do.

### Persistence

Profiles are serialized to JSON via **Gson** and stored in `SharedPreferences`. Maximum **5 profiles** per device.

### Applying a Profile

`ProfileApplier.apply()` executes hardware calls in the following order on `Dispatchers.IO`:
1. Drive mode (fast — binder call)
2. Regen level (fast — binder call)
3. Heated steering wheel (~2 s — state confirmation polling)
4. Left seat heating (~7 s — toggle polling)
5. Right seat heating (~7 s — toggle polling)
6. Wait for Katman4, then firmware ADAS, forward collision, ESC and drowsiness, lane keeping

Safety settings are **skipped when the profile does not configure them**, rather than written to a
default: the ESC write is a toggle driven by a prior read, so aiming for "ON" from a doubtful read
would disable it.

---

## User Interface

### Navigation
The app uses a **NavController** with **7 destinations**:

```
DashboardFragment (start)
    ├──► ProfileFragment ──► ProfileEditFragment  (create / edit, full screen)
    ├──► SettingsFragment
    ├──► ShortcutsFragment
    ├──► AudioFragment        (A9 only)
    └──► AutomationFragment
```

Top-bar buttons act as toggles: a second press returns to the dashboard.

### Category rail
Four screens share the same pattern: a **vertical rail on the left** selects a category, the
content scrolls on the right, and whatever belongs to no category stays in a persistent header
(profile name, master switch) or footer (Cancel / Save / Close).

| Screen | Tabs |
|---|---|
| Dashboard | Driving · Safety · Comfort |
| Profile editor | Driving · Safety · Comfort |
| Settings | Languages · Interface · Advanced · Info |
| Shortcuts | Classic · Advanced · List |

A tab whose page has no visible section left on the current firmware is **hidden** — better no tab
than a tab opening an empty page.

### Sizing
Values shared by the reworked screens, tuned for readability while driving: titles **20sp**,
section headers **13sp**, labels and buttons **16sp**, button height **52dp**, rail tabs **64dp**,
rail width **180dp**, card padding **14dp**.

### Color Palette

The app follows the light or dark theme. Light values live in `res/values/colors.xml`, dark ones in
`res/values-night/colors.xml` — **same token names on both sides**, which is the only rule to
follow when adding a colour.

| Token | Light | Dark | Usage |
|-------|-------|------|-------|
| `dash_bg` | `#F2F2F7` | `#0C0C0E` | App background |
| `dash_card` | `#FFFFFF` | `#141416` | Cards |
| `dash_section` | `#F2F2F7` | `#1C1C1F` | Inner sections |
| `dash_border` | `#D1D1D6` | `#2A2A2E` | Borders and dividers |
| `dash_btn` | `#E5E5EA` | `#222226` | Inactive button background |
| `dash_text_lo` | `#8E8E93` | `#52525B` | Section headers |
| `dash_accent` | `#0284C7` | `#38BDF8` | Active selection (blue) |
| `dash_accent_dim` | `#E0F2FE` | `#0C4A6E` | Active selection background |
| `dash_eco` | `#16A34A` | `#22C55E` | ECO mode (green) |
| `dash_warn` | `#D97706` | `#F59E0B` | Warning (amber) |
| `dash_danger` | `#E11D48` | `#F43F5E` | Delete / danger actions |
| `text_primary` | `#1C1C1E` | `#FFFFFF` | Primary text |
| `text_secondary` | `#6C6C70` | `#B0B0B0` | Secondary text |

Every `*_dim` colour is the background paired with its vivid counterpart: `dash_eco_dim`,
`dash_warn_dim` and `dash_danger_dim` follow the same principle as `dash_accent_dim`.

> **Naming pitfall:** `bg_dark` is `#FFFFFF` in the light theme. The name dates back to when the
> app only had a dark theme; it means the general background, not a dark colour.

---

## External API (KeyMapper, Tasker…)

Lets a third-party app trigger MG4Control functions (issue #79).

> **Disabled by default.** Turn it on in *Settings → Advanced settings → "External API"*, with an
> explicit confirmation. While disabled, every incoming command is refused and logged. Once
> enabled, **any installed app** can send these intents: they are protected by no permission,
> because KeyMapper and Tasker ship from the Play Store and can never hold a `signature` one.

### Direct actions — one intent action per command

No extras required. This is the form usable from **KeyMapper**, whose intent editor only offers
the type (*Broadcast receiver*) and the action string.

| Action | Effect |
|---|---|
| `com.mg4.control.action.ONE_PEDAL` | Toggle 1-pedal ↔ fallback regen level |
| `com.mg4.control.action.ENERGY_SAVING_TOGGLE` | Energy saving |
| `com.mg4.control.action.PROFILE_PICKER` | Show the on-screen profile picker |
| `com.mg4.control.action.OPEN_APP` | Open MG4Control |

These are **toggles**: each send flips the state, there is no "set to ON".

> **Deliberately out of the API.** `VEHICLE_POWER_OFF`, `ADAS_CYCLE`, `AEB_CYCLE`, `TSR_TOGGLE`,
> `OVERSPEED_ALARM`, `SPEED_LIMIT_TONE` and `SOUND_WARNING` are **not** exposed: they affect active
> safety or shut the vehicle down. The refusal also covers `EXECUTE` — removing them from the direct
> actions alone would have protected nothing. They remain available from the app and the steering
> wheel shortcuts.

**In KeyMapper**: add an action → *Intent* (version 2.3.0 minimum) → type **Broadcast receiver** →
paste the string into the *Action* field.

### `EXECUTE` — for Tasker, adb, scripts

`com.mg4.control.action.EXECUTE` with a string extra `action` holding **the name of any shortcut
action**, except the ones listed above as out of the API. That covers far more than the four direct
actions: `REGEN_CYCLE`, `SEAT_HEAT_LEFT_CYCLE`, `HVAC_RECIRC_CYCLE`, `BRIGHTNESS_UP`… — the exact
names are the ones in `shortcut/ShortcutAction.kt`.

Two of them need an extra, and therefore cannot exist as a direct action:

- `APPLY_PROFILE` — requires a `profile` extra: the profile name, case-insensitive
- `OPEN_CUSTOM_APP` — opens the app configured in the shortcuts screen

```bash
adb shell am broadcast -a com.mg4.control.action.EXECUTE \
  --es action APPLY_PROFILE --es profile "Home commute"
```

### `SET` — write a value directly

`com.mg4.control.action.SET` with the `key` and `value` extras:

| `key` | accepted `value` |
|---|---|
| `drive_mode` | `ECO` `NORMAL` `SPORT` `SNOW` `CUSTOM` |
| `regen` | `OFF` `LOW` `MEDIUM` `HIGH` `ADAPTIVE` `ONE_PEDAL` |
| `seat_heat_left` | `0` to `3` |
| `seat_heat_right` | `0` to `3` |
| `steering_heat` | `0`/`1` or `false`/`true` |
| `profile` | profile name |
| `hvac_power` | `0`/`1` — climate on/off |
| `ac` | `0`/`1` — A/C compressor |
| `hvac_auto` | `0`/`1` — automatic mode |
| `hvac_temp` | °C, clamped to the vehicle's real bounds |
| `hvac_fan` | fan level, clamped to the real bounds |
| `hvac_recirc` | `INNER` `OUTSIDE` `AUTO` (or `0` `1` `2`) |
| `defrost_front` | `0`/`1` |
| `defrost_rear` | `0`/`1` |

The `hvac_*` and `defrost_*` keys are ignored when the firmware exposes no climate control.
Setpoint and fan are clamped to bounds **read from the vehicle**, which differ across firmwares.
These are hardware toggles that step one notch at a time: expect a few seconds before the final
state is reached.

```bash
adb shell am broadcast -a com.mg4.control.action.SET --es key drive_mode --es value SPORT
```

#### `NEXT` / `PREV` / `TOGGLE` — cycling without knowing the state

Instead of a setpoint, `value` accepts **`NEXT`** (next notch), **`PREV`** (previous notch) or
**`TOGGLE`** (an alias of `NEXT`, easier to read on a boolean). The new value is computed from the
state read on the vehicle, so a single steering-wheel button can mean "seat heat +1". The cycle
**wraps around**: past the maximum, the next notch returns to the minimum.

```bash
adb shell am broadcast -a com.mg4.control.action.SET --es key seat_heat_left --es value NEXT
adb shell am broadcast -a com.mg4.control.action.SET --es key ac --es value TOGGLE
```

Cyclable keys: `seat_heat_left`, `seat_heat_right`, `steering_heat`, `hvac_power`, `ac`,
`hvac_auto`, `hvac_temp`, `hvac_fan`, `hvac_recirc`, `defrost_front`, `defrost_rear`.

`drive_mode`, `regen` and `profile` are **deliberately excluded**: the drive-mode enum is not
filtered per firmware (cycling could select a mode the car does not have), regen availability
depends on the current state (no level at all in Snow mode, One Pedal only while Energy Saving is
on), and there is no notion of a "current profile".

When the current state cannot be read, the command is **refused without writing anything** rather
than assuming a starting point — a guessed origin would move the value down while you press to
move it up. Refusals are logged (`adb logcat -s MG4_API`).

### Reading state — ContentProvider

`content://com.mg4.control.state/state` (or `com.mg4.control.offline.state` for the offline
variant — the authority follows the applicationId). A **single**-row cursor:

`drive_mode`, `regen`, `seat_heat_left`, `seat_heat_right`, `steering_heat`, `speed_kmh`,
`outside_temp_c`, `tsr`, `energy_saving`, `aeb_enabled`, `firmware`, `profiles` (names separated
by `|`), `default_profile`.

An unreadable value is `null`, never `0` — a zero would be indistinguishable from "seat off" or
"vehicle stopped". Tasker can query a ContentProvider, KeyMapper cannot.

Unlike broadcasts, a provider knows its caller: every read is logged by package name, and the
`external_api_allowlist` preference (comma-separated packages, empty = all allowed) is actually
enforced.

### Security and diagnostics

The **speed lock** (*Settings → "Block driving settings above a given speed"*) also covers the API,
since it sits in the write primitives. Note that it is itself **disabled by default** — if you have
not enabled it, no speed limit applies to external commands. Comfort settings (seats, steering
wheel heating) are never affected.

Every command, accepted or refused, is traced under the **`MG4_API`** tag (visible via the
Diagnostic button). To test the app independently of KeyMapper:

```bash
adb shell am broadcast -a com.mg4.control.action.PROFILE_PICKER
```

Complete silence = stale APK or service not running. `REFUS … API externe désactivée` = the
Settings toggle was never confirmed.

---

## Build & Installation

You can download the latest version of MG4Control directly from the releases page: https://github.com/SliDeeN/MG4Control/releases
All you need is a USB drive and access to the AAOS settings to install the APK.


You can also compile the project yourself:

### Prerequisites
- Android Studio Hedgehog (2023.1) or later
- JDK 17+
- Android SDK API 34

### Debug Build

```bash
# Using Android Studio's bundled JDK
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew assembleDebug
```

Output APK location:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Installing on the Vehicle

The application must be signed with the ROM's system key. On a development system:

```bash
adb push app-debug.apk /sdcard/
adb shell pm install -r --system /sdcard/app-debug.apk
```

> On a production ROM, the APK must be included in the system build or installed through an OEM-specific mechanism.

---

## Required Permissions

| Permission | Reason |
|-----------|--------|
| `FOREGROUND_SERVICE` | Persistent foreground service for auto-apply |
| `WAKE_LOCK` | Prevents sleep during settings application |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on vehicle boot |
| `CAR_POWERTRAIN` | Drive mode and regeneration control |
| `CONTROL_CAR_CLIMATE` | Seat and steering wheel heating control |
| `CAR_VENDOR_EXTENSION` | SAIC proprietary extensions |
| `CAR_ENERGY` | Battery / powertrain information |
| `INTERNET` | Update check (GitHub API) |
| `DOWNLOAD_WITHOUT_NOTIFICATION` | Silent download of the update APK |
| `WRITE_EXTERNAL_STORAGE` | Saving the APK into the Downloads folder |

---

## Licence

| Versions | Licence |
|---|---|
| up to and including **2.6.5** | MIT — [`LICENSE.MIT`](LICENSE.MIT) |
| from **2.6.6** onwards | **GNU GPL v3** or later — [`COPYING`](COPYING) |

Already published versions stay under MIT: a granted licence cannot be revoked. From 2.6.6, any
modified version you redistribute must also be released under the GPL, **source code included**.

The MIT notice is retained for the code published under that regime before the switch, third-party
contributions included. Details in [`LICENCE.md`](LICENCE.md).

---

## Credits

Made with ❤ by **SliDeeN** and **Claude IA**

Basé sur l'application **DriveHub Dort** développée par **Merth4n** & **hotboy_ist**

Merci à **confor1max**, **FrAsErTaG**, **sixty4h**, **hojnikb** et **depippi.p** pour les tests avant chaque release 🙏

[![GitHub](https://img.shields.io/badge/GitHub-SliDeeN%2FMG4Control-181717?logo=github)](https://github.com/SliDeeN/MG4Control)

</details>
