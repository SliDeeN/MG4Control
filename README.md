![image info](mg4control_github_banner.svg)

[![Security](https://github.com/SliDeeN/MG4Control/actions/workflows/security.yml/badge.svg)](https://github.com/SliDeeN/MG4Control/actions/workflows/security.yml)
[![Release](https://github.com/SliDeeN/MG4Control/actions/workflows/release.yml/badge.svg)](https://github.com/SliDeeN/MG4Control/actions/workflows/release.yml)
[![Demo](https://img.shields.io/badge/Demo-Essayer%20%C2%B7%20Try%20it-1f6feb?logo=github)](https://slideen.github.io/MG4Control/)

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

#### Mode de conduite Personnalisé
Trois réglages que le véhicule n'expose que dans ce mode : **puissance** (Éco / Normal / Sport),
**direction** et **force sur la pédale** (Confort / Normal / Sport). Disponibles sur les six
firmwares, par trois voies distinctes — signaux `VehiclePropertyManager` en SWI133, méthodes
`VehicleSettingManager` en SWI68/165, méthodes `CarVehicleSettingClient` sur A9.

Présents à deux endroits, avec une règle d'apparition différente :

| Écran | La carte apparaît quand |
|---|---|
| Dashboard, onglet Conduite | la **voiture** est en mode Personnalisé, y compris si le mode a été mis depuis l'écran d'origine |
| Éditeur de profil, onglet Conduite | le **profil** en cours d'édition a Personnalisé pour mode |

Sur le Dashboard, une ligne dont le véhicule ne rend pas l'état disparaît : la commande existe sur
tous les firmwares, mais rien ne garantit que la finition porte l'équipement.

> [!WARNING]
> Les trois réglages n'ont pas la même échelle côté véhicule, et la pédale est un piège :
> **Normal y vaut 0**, pas la valeur du milieu. La puissance vaut 1/2/3 en SWI133/68/165 mais
> 2/3/4 sur A9, où la voiture réutilise l'échelle des modes de conduite. L'application ne
> manipule donc jamais ces nombres — elle passe un index 0/1/2 et la conversion vit dans
> `MG4Hardware`.

Dans un profil, ils ne sont écrits que si son mode de conduite est Personnalisé, et un profil
enregistré avant cette fonctionnalité n'écrit rien tant qu'il n'a pas été rouvert et sauvegardé.

#### Chauffage intelligent de la batterie
L'option d'origine cachée dans le widget batterie (onglet « Consommation d'énergie ») chauffe la
batterie en roulant : la recharge rapide est meilleure, mais l'autonomie baisse, et une fois
activée elle ne se coupe jamais d'elle-même. MG4Control la met en ON/OFF sur le Dashboard (onglet
Conduite, avec le temps restant avant la coupure automatique), en raccourci, et propose de la
couper automatiquement (voir Automatisation). Une seule propriété véhicule, `DRVNG_PTC_HEAT`, sur
les six firmwares : par le `VehiclePropertyManager` en SWI133, comme SystemUI, directement ailleurs.

> [!NOTE]
> Les écrans d'origine ne lisent pas l'état de la même façon : 0 = activé sur l'ancienne
> plateforme (SWI133/68/165), 1 = activé sur A9. L'application suit chacun ; la sonde du
> Diagnostic (`MG4_BATHEAT`) relève la valeur brute pour le vérifier sur véhicule.
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
- **Gestion automatique des feux de route** : On / Off
- **Luminosité de l'écran**

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
| Conduite | 1 Pédale · Cycle Régénération Personnalisé · Éco. énergie · Chauffage de la batterie ON/OFF |
| Sécurité | ESC · Somnolence · Somnolence : sensibilité · Système Anticollision · ADAS · Panneaux (TSR) · Alerte survitesse · Alerte changement de limite · Son |
| Confort | Siège chauffant gauche · Siège chauffant droit · Volant chauffant · Clim ON/OFF · Clim : A/C ON/OFF · Clim : température ± · Clim : ventilation ± · Dégivrage avant · Dégivrage arrière · Recirculation · Feux de route automatiques ON/OFF · Luminosité ± · Pop-up HVAC |
| Vitres | Ouvrir toutes les vitres · Fermer toutes les vitres |
| Média | Lecture / Pause · Piste suivante · Piste précédente · Volume + · Volume - · Cycle de sources audio |
| Application et véhicule | Lancer un profil · Sélecteur de profils · Cycle de profils · Pop-up de charge · Ouvrir MG4Control · Lancer une application · Éteindre la voiture |

Les actions de confort et de sécurité **relisent l'état sur le véhicule à chaque pression** au lieu
de mémoriser le leur : l'utilisateur agit aussi depuis l'écran d'origine, un état gardé en mémoire
dériverait dès le premier usage. Un état illisible vaut abstention — rien n'est écrit à l'aveugle.

Les bascules **1 Pédale**, **Éco. énergie**, **Panneaux (TSR)**, **Alerte survitesse**, **Alerte
changement de limite** et **Son** relisent elles aussi l'état réel : un mode 1 Pédale activé par
le profil au démarrage est coupé dès le premier appui. Là où cet état ne se lit pas de façon sûre,
elles retombent sur leur dernière consigne et alternent comme avant ; seul le cycle anticollision
A/B suit encore un état mémorisé.

La liste des raccourcis avancés affiche pour chaque ligne le **bouton** (nom et code), le **type
d'appui**, la **fonction**, puis *Modifier* et *Supprimer*. Réattribuer un bouton déjà utilisé sur
le même type d'appui demande confirmation et nomme la fonction qui va être remplacée. *Modifier*
ouvre une fenêtre à deux listes côte à côte — la fonction et le profil —, chacune ouverte sur le
choix en cours ; l'application ou le profil visé par un raccourci se choisit dans une grille de
tuiles.

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

#### Pop-up HVAC
L'action **Pop-up HVAC** ouvre la climatisation par-dessus l'écran en cours — y compris quand
Android Auto ou CarPlay occupe tout l'écran et masque le bandeau clim d'origine. Le joystick droit
du volant n'y déplace aucun focus : haut/bas règlent la température, gauche/droite la ventilation,
le clic central bascule le mode AUTO (régler la ventilation en fait sortir). Au cran 1, un appui
à gauche éteint la clim (« OFF ») ; éteinte, gauche ne la rallume pas et droite la remet au
cran 1. Un repère dans
l'en-tête rappelle ces gestes, ou prévient que le joystick est inactif tant que le service
d'accessibilité n'est pas activé — la fenêtre reste alors utilisable au doigt. Se choisissent au
doigt : l'A/C, la recirculation, le sens de l'air et, sur les firmwares équipés (SWI133, SWI68,
SWI165), le volant chauffant et le niveau de chaque siège, avec une couleur par niveau (vert,
orange, rouge). La fenêtre se referme d'un second appui sur le raccourci, par sa croix, ou seule
après le délai des pop-ups.

#### Cycle de profils
L'action **Cycle de profils** fait défiler les profils, dans un ordre composé sur sa page (au
moins deux profils ; sans réglage, tous les profils dans l'ordre de leur liste). À chaque appui le
raccourci vise le profil qui suit le profil actif et affiche son nom ; il l'applique une seconde
plus tard, puis le confirme à l'écran. Deux appuis rapprochés sautent donc un profil sans
l'appliquer. Si le verrou de vitesse refuse l'écriture, rien ne change.

#### Cycle de sources audio
L'action **Cycle de sources audio** passe à la source suivante d'un cycle composé sur la page
*Cycle sources*, en tuiles : radio, Bluetooth, USB, en ligne, CarPlay / Android Auto (au moins
deux sources ; sans réglage, radio puis Bluetooth). À chaque appui, *Source : …* s'affiche et la
voiture y passe une seconde plus tard ; deux appuis rapprochés sautent donc une source. Il
n'existe pas de commande « changer de source » : l'application demande à la source visée de
jouer, ce qui la met au premier plan, sans rien mettre en pause. La disponibilité n'est pas
vérifiée : une source sans rien à jouer reste muette, et l'appui suivant passe à la suivante,
car le cycle repart de la dernière source choisie, pas de celle qui joue.

- **SWI133, SWI68, SWI165** : par le service média SAIC, source par source.
- **SWI69, SWI131, SWI132** : le Bluetooth passe par l'appli Média SAIC, comme la touche de
  source d'origine — c'est elle qui fait passer la sortie audio de la voiture en Bluetooth avant
  de lancer la lecture. L'USB vise le lecteur du port où la clé est branchée (un lecteur par
  port). « En ligne » n'est pas proposé : cette source n'existe pas sur ces firmwares.
- **CarPlay / Android Auto** : par le récepteur d'origine AllGo ; s'il n'a pas de téléphone,
  l'application essaie Android Auto sans fil par DiAuto-MG4.

### Automatisation
- **Application d'un profil selon la température extérieure** : seuil, sens
  (inférieure/supérieure), profil à appliquer, exécution directe ou bandeau de confirmation
- **Application d'un profil selon la batterie** : quand le niveau passe sous un seuil réglable au
  curseur (5–60 %), au démarrage comme en roulant ; une fois par épisode, réarmée à chaque
  démarrage ou après une recharge ; exécution directe ou bandeau de confirmation
- **Bandeau de confirmation** : la question se pose en bas de l'écran, sans masquer la navigation
  — la mesure et son seuil, le profil proposé, NON et OUI. Elle se répond au doigt ou au joystick
  droit du volant (OUI présélectionné) ; NON, ou le délai des pop-ups écoulé, et rien n'est appliqué
- **Luminosité automatique au démarrage** : au passage en READY, l'écran prend une luminosité
  choisie d'après les feux et/ou la météo — au moins une source, les feux seuls par défaut.
  **Feux seuls** (ni données mobiles, ni GPS ; seul mode de la version hors ligne) : deux
  luminosités réglables, feux éteints (80 % par défaut) et feux allumés (20 %).
  **Météo** (case à cocher, elle consomme un peu de données) : une courbe à quatre points réglables
  (nuit, crépuscule, temps couvert, plein soleil) associe la luminosité à la lumière extérieure.
  La voiture ne mesurant pas la lumière, elle est estimée d'après la hauteur du soleil (position
  GPS + heure, calcul sans réseau) et la prévision d'ensoleillement
  [Open-Meteo](https://open-meteo.com) sur une grille de 25 points autour de la voiture (zone
  d'environ 60 × 60 km : en roulant, la météo de l'endroit où l'on est), mise en cache pour 3 jours
  et actualisée toutes les 8 h au plus ou en sortant de la zone (~10 Ko par requête), position
  arrondie à ~10 km. Avec les feux en plus, feux de position allumés (garage, tunnel, nuit) :
  point Nuit de la courbe. Option « Ajuster la luminosité pendant la conduite » : réajuste aussi à
  chaque bascule des feux (tunnel) et, avec la météo, dès que la lumière estimée s'écarte de
  10 points ; un réglage à la main suspend tout jusqu'au prochain démarrage. Changements en fondu
  d'une seconde. Bouton « Tester maintenant »
- **Coupure du chauffage de la batterie** : coupe le chauffage intelligent de la batterie après un
  temps réglable au curseur (5 à 120 min, 30 par défaut). Le décompte part de l'activation, en
  roulant ou à l'arrêt — écran d'origine, fenêtre « température basse » de SystemUI, MG4Control
  ou raccourci —, ou du démarrage si l'option était déjà activée, et ne vaut que pour le trajet en
  cours. Un message s'affiche à la coupure
- **Déclenchement A/C via la température** : deux règles indépendantes (température supérieure /
  inférieure), chacune avec son seuil, sa consigne, sa ventilation, ses dégivrages, le mode
  automatique et la recirculation
- **Baisse du volume en quittant la voiture** : le volume média descend au niveau choisi au
  curseur au moment du départ, sur les six firmwares. Là où la voiture signale ses portes (SWI133,
  SWI132), le déclencheur est l'ouverture d'une porte avant, avec choix des portes et restauration
  à la fermeture ; ailleurs, faute de capteur atteignable, c'est la **sortie du mode READY** qui
  sert de signal et la restauration, si elle est demandée, se fait au retour en READY. Option
  coupée, ces réglages restent lisibles mais grisés
- Chaque automatisation s'ouvre dépliée si elle est active, se déplie quand on l'active et se
  replie quand on la coupe ; entre-temps, son chevron déplie ou replie librement le paramétrage

#### Fermeture automatique des vitres électriques
Carte construite comme les autres automatisations : interrupteur d'activation à côté de la
description, réglages dépliés au chevron (carte ouverte d'office quand l'option est activée).

- **Fermeture en quittant la voiture** — voiture en P et fermeture armée : quand la voiture sort
  du mode READY (ceinture détachée et porte conducteur ouverte, ou extinction), toutes les vitres
  se ferment après le délai choisi ; un retour en READY avant la fin annule. L'armement se règle (vitesse atteinte
  et/ou durée d'allumage, l'une ou l'autre ou les deux), le délai va de 0 à 30 s, et un bip
  d'avertissement facultatif, au volume réglable, peut accompagner le décompte. Option coupée,
  ces réglages restent lisibles mais grisés.
- **Durée de course** — seule la vitre conducteur a un capteur et sa course automatique
  d'origine ; les trois autres reçoivent une impulsion de durée réglable (5 s par défaut).
- **Avancé : calibrage par vitre** — mesure la course réelle de chaque vitre, plus juste que la
  durée générale ; les mesures restent enregistrées si l'option est désactivée.

Durée de course et calibrage restent réglables option coupée : ils servent aussi aux raccourcis
« ouvrir / fermer toutes les vitres ».

> [!WARNING]
> Les courses commandées par l'application n'ont pas l'anti-pincement du véhicule, sauf sur la
> vitre conducteur. Personne près des vitres pendant une fermeture, et en particulier pendant une
> fermeture automatique, qui part alors que vous êtes déjà dehors.

### Gestion de profils
- Sauvegarde jusqu'à **10 profils** personnalisés (5 avant la 2.6.9)
- Application instantanée d'un profil en un clic
- Application automatique du profil par défaut **au démarrage du véhicule**
- **Association Bluetooth** : un profil peut être lié à un appareil et s'applique à sa connexion
- **Sauvegarde automatique dans la mémoire du véhicule** : elle survit à la désinstallation de
  l'application, et à la réinstallation une fenêtre liste les profils retrouvés et propose de les
  restaurer
- Précédence entre déclencheurs : manuel → température → Bluetooth → profil par défaut

### Statistiques
Onglet de la barre du haut, **désactivé par défaut** : tant qu'il n'est pas activé, rien n'est
relevé. Tout reste dans la voiture, dans un fichier privé de l'application ; rien n'est envoyé
ailleurs. Trois sous-onglets sur le rail habituel :

- **Général** : résumé sur 30 jours ou sur tout l'historique — distance, consommation, énergie
  consommée, récupérée et rechargée, coût. Réglages : prix du kWh en charge lente et en charge rapide
  (jusqu'à 100 000, pour toutes les monnaies), monnaie, batterie de la voiture (51, 64 ou 77 kWh)
  et son **état de santé** (SOH), filtre des petits trajets, durée de conservation (30 jours à 1 an, 3 mois par défaut) et
  « Tout supprimer »
- **Trajets** : chaque trajet avec sa durée, sa distance, sa consommation et le détail de l'énergie
- **Recharge** : énergie rechargée, coût et sessions AC / DC, y compris les charges faites pendant
  que le boîtier dormait

D'où viennent les chiffres :

| Mesure | Source |
|---|---|
| Bornes d'un trajet | Le contact — les compteurs d'énergie de la voiture repartent de zéro au même moment |
| Distance | Intégration de la vitesse, relevée toutes les 10 s en roulant ; l'odomètre, qui ne publie que le kilomètre entier, sert de garde-fou |
| Énergie d'un trajet, SWI133 et SWI132 | Compteurs de la voiture, au dixième de kWh : total, climatisation, accessoires, récupération |
| Énergie d'un trajet, autres firmwares | Leurs compteurs restent à zéro : tension × courant de la batterie, relevés chaque seconde en roulant. Le détail se limite à « Moteur » et « Climatisation et autres », au kWh entier |
| Énergie d'une charge, alternatif | Écart de pourcentage × capacité retenue : capacité utile de la batterie choisie × état de santé |
| Énergie d'une charge, continu | Mesurée pendant la charge (tension × courant) ; la partie non vue est estimée d'après une table apprise par tranche de 1 % (cf. *Charges rapides*) |
| Type et puissance d'une charge | Type lu sur la voiture (alternatif ou continu) ; puissance mesurée si le boîtier est éveillé pendant la charge, sinon calculée (énergie ÷ durée) et annoncée comme telle |

Règles à connaître :
- **Charge de nuit** : le boîtier s'éteint pendant la charge et rien n'est relevé. Au réveil, le
  pourcentage est comparé au dernier relevé et la charge est **reconstituée**. On peut la
  **compléter** — type de prise, heures de début et de fin, aux boutons − et + — pour obtenir sa
  puissance moyenne
- **Coût d'un trajet** : il suit le **prix moyen de l'énergie réellement rechargée** sur la période,
  pas le tarif domicile — sans quoi un plein en borne rapide disparaîtrait du coût. Le tarif d'une
  charge se **corrige** depuis son détail ; le prix moyen et le coût des trajets suivent
- **Consommation d'une période** : calculée sur les totaux, pas en moyennant les trajets — un trajet
  de 2 km pèserait sinon autant qu'un trajet de 200 km
- **Précision affichée** : quand l'incertitude d'une consommation dépasse 10 % (trajet court, compteur
  au dixième de kWh), elle s'affiche avec « ≈ » et sans décimale
- **Petits trajets** : filtre désactivé par défaut ; activé, un trajet plus court que la distance
  choisie (1 km par défaut, de 0,1 à 50 km) n'est pas enregistré
- **Batterie et état de santé** : l'état de santé se règle de 50 à 100 % par pas de 0,5 %, aux
  boutons − et + (maintenus, ils défilent), et la capacité retenue s'affiche dessous. 100 % par
  défaut : un concessionnaire ou une appli OBD peut le mesurer. Changer l'un ou l'autre recalcule
  les recharges enregistrées d'après le pourcentage — une énergie mesurée ne bouge pas ; tant que la
  batterie n'a pas été confirmée, l'onglet Recharge le rappelle
- **Charges rapides** : le pourcentage affiché ne suit pas l'énergie entrée (de 62,8 à 70,1 %,
  5,0 kWh mesurés côté batterie, quand l'écart de pourcentage n'en comptait que 4,2). L'énergie
  d'une charge en continu est donc **mesurée** pendant la charge, relevée toutes les 2 s, pop-up de
  charge activé ou non. L'écran central s'éteint au verrouillage : la partie non vue — la fin, ou
  le début si l'écran s'est rallumé en pleine charge — est estimée d'après une **table apprise par
  tranche de 1 %**, nourrie par chaque tranche vue en entier (une tranche sert dès deux mesures,
  les plus récentes comptent davantage, une mesure invraisemblable est refusée). Une charge jamais
  vue est estimée tranche par tranche, ou d'après le pourcentage tant que la table est vide. Le
  détail de la charge dit d'où vient son énergie : *mesurée pendant la charge*, *mesurée en
  partie*, *estimée d'après les charges déjà vues* ou *estimée d'après le niveau de batterie*. Les
  charges en alternatif, longues et faites surtout écran éteint, restent calculées d'après le
  pourcentage
- **Coupures** : le trajet en cours est enregistré après chaque relevé, et un trajet interrompu par
  l'extinction du boîtier est clos au démarrage suivant. L'historique est écrit dans un fichier
  temporaire puis renommé : une coupure d'alimentation ne le tronque jamais
- **Horloge** : un boîtier réveillé par la charge peut repartir sur son horloge d'usine (2019) ; les
  relevés datés d'avant 2025 sont écartés

### Écran conducteur
Option de *Réglages → Interface*, **désactivée par défaut**, sur **SWI133, SWI68 et SWI165**
seulement : un module MG4Control s'affiche au centre du combiné, derrière le volant, à la place de
la page d'origine des onglets Navigation et Musique.

Le combiné est un calculateur à part. Seul un carré de **260 × 260 px**, au centre, vient de
l'écran central : SAIC l'y dessine avec une application système (`clusterprojection`), sur le second
écran d'Android (n° 1, 800 × 480). MG4Control pose sa fenêtre exactement sur ce carré, avec le fond
de cette application pour s'y fondre, dès le démarrage de l'application — sans attendre le READY.
Sur les firmwares **A9** (SWI69, SWI131, SWI132), cette application n'existe pas et le combiné
dessine tout lui-même : rien ne peut s'y afficher, et la carte n'apparaît pas dans les réglages.

| Module | Jauge | Grand chiffre | Pied |
|---|---|---|---|
| **Consommation** | Anneau gradué, échelle logarithmique jusqu'à 3 000 Wh/km (300 kWh/100 km) | Wh/km ou kWh/100 km en roulant ; kW consommés à l'arrêt (sous 5 km/h) ; en récupération, une consommation négative en bleu (25 kW récupérés à 60 km/h : −41,7 kWh/100 km), les kW récupérés sous 5 km/h | Moyenne du trajet · énergie récupérée |
| **Puissance** | Arc gradué de 60 kW récupérés (à gauche) à 320 kW (à droite), échelle logarithmique | kW entiers en roulant (une décimale à l'arrêt), négatifs en récupération ; dessous, en grand, la consommation instantanée, négative et bleue en récupération | Moyenne du trajet, seule et centrée — la batterie est déjà sur le combiné |

- **Un module par onglet** : Aucun, Consommation ou Puissance pour l'onglet Navigation, de même pour
  l'onglet Musique. Le module suit l'onglet affiché sur le combiné (`IPK_CLUSTER_TAB_BTN_STS`) ;
  ordinateur de bord, pression des pneus et réglages sont dessinés par le combiné lui-même et
  gardent leur page
- **Style** : *Dynamique* (l'anneau ou l'arc, et le chiffre qui compte) ou *Discret*, pour qui
  trouve distrayants les chiffres qui défilent : une barre fine graduée, des chiffres fixes sous les
  crans. Puissance : le zéro marqué, la récupération à gauche en bleu, la puissance à droite en gris
  clair. Consommation : une courte zone de récupération à gauche, puis l'échelle ; un triangle
  « moy. » marque la moyenne du trajet, et la barre est verte en dessous, orange au-dessus. Mêmes
  échelles que le style dynamique, même pastille, même moyenne en bas ; la barre suit en deux
  secondes, sans trembler, quelle que soit l'actualisation
- **Pastille** en haut à gauche : *Profil* (par défaut) — le dernier profil appliqué, en capitales,
  dans la couleur du mode de conduite **du profil** (celle de la voiture s'il ne règle pas la
  conduite) —, *Mode de conduite*, ou *Aucune*. Jamais plus large que celle du mode le plus long :
  un nom plus long finit en ellipse
- **Unités** : celle de la **jauge**, Wh/km (par défaut) ou kWh/100 km, avec une décimale sous 100 —
  l'anneau ne bouge pas d'une unité à l'autre, ses graduations (0 · 100 · 200 · 500 · 1k · 3k Wh/km,
  ou 0 · 10 · 20 · 50 · 100 · 300 kWh/100 km) suivent l'unité — et, à part, celle de la **moyenne du
  trajet** : Wh/km, kWh/100 km ou km/kWh (par défaut, celle de la jauge)
- **Échelle de puissance resserrée** : logarithmique, pour que l'arc bouge en roulant — 20 kW en
  remplissent un quart, 50 kW 44 %, 100 kW 63 % ; côté récupération, 10 kW font 36 %. Graduations
  20 · 50 · 100 · 200 · 320 et 10 · 30 · 60. Le remplissage s'arrête net au zéro, sans déborder de
  l'autre côté
- **Couleurs** : orange pour la consommation, bleu pour la récupération, quelle que soit la valeur
- **Avancé** : l'**actualisation** de la puissance, de la vitesse et de la jauge, de 0,1 à 1 s (1 s
  par défaut ; plus court, plus réactif, mais le chiffre bouge davantage), et la **durée de
  l'animation**, de 0,2 à 2 s (0,8 s par défaut). Le mode de conduite et le profil sont relus
  toutes les 10 s
- **Le chiffre compte** d'une valeur à la suivante (100, 101… 300) au lieu de sauter, et la jauge
  glisse avec lui, sur la durée d'animation réglée ; un changement d'unité (Wh/km → kW à l'arrêt)
  saute directement
- **Moyenne du trajet** : énergie nette (récupération déduite) ÷ distance intégrée depuis le passage
  en READY, affichée à partir de 200 m. Elle est calculée sur place plutôt que reprise de l'onglet
  Statistiques, qui peut être désactivé
- **Pendant une charge**, le **module de charge** remplace la jauge sur les onglets qui en ont une,
  dès que la charge est détectée, en AC comme en rapide : en grand le temps restant jusqu'à la
  limite, dessous le niveau au dixième et l'heure de fin, la barre de batterie avec le repère de la
  limite, puis puissance, énergie et durée. L'éclair de la pastille pulse et un reflet court sur la
  barre jusqu'à la limite ; à la fin, tout se fige et passe au vert (« Charge terminée »). Au
  débranchement ou au READY, la jauge revient
- **Carte de réglages** : à gauche, un **aperçu** — la vraie jauge sur un trajet simulé, qui suit
  chaque réglage, pour l'onglet Navigation ou Musique ; à droite, les réglages en trois groupes
  (Modules, Apparence, Unités), puis « Avancé », replié sous son résumé

### Pop-up de charge
Option de *Réglages → Interface*, **activée par défaut**, sur tous les firmwares : dès qu'une charge
commence, en AC comme en rapide, un pop-up plein cadre s'ouvre sur l'écran central — y compris
quand l'application démarre en pleine charge. Il n'a pas de décompte : il se ferme par sa croix, au
passage en READY, ou d'un appui sur le raccourci **Pop-up de charge**, qui le rouvre aussi (option
coupée, le raccourci dit où l'activer). Une pause de la borne de moins de 10 minutes reste la même
charge.

- **À gauche** : la batterie au dixième, la barre de niveau, puis l'heure de fin estimée en grand et
  le temps restant jusqu'à la limite
- **Au milieu** : la courbe de la puissance (aire bleue) et du niveau (ligne verte) **depuis le début
  de la charge**, pas depuis l'ouverture du pop-up — l'application relève la charge en arrière-plan,
  un point toutes les 5 s (un trou si l'écran s'éteint). L'axe des kW monte jusqu'à la puissance
  maximale atteinte, la limite est en pointillé gris, et un pointillé vert relie le niveau actuel à
  la limite, à l'heure de fin donnée par la voiture
- **À droite** : puissance (et le maximum atteint), énergie ajoutée, autonomie gagnée — l'écart de
  l'autonomie estimée par la voiture depuis le début —, coût estimé et durée

**Limite de charge** : la barre de batterie sert de curseur, de 40 à 100 % par pas de 10 ; le cran
visé passe en gras, et la limite part à la voiture au relâchement (`BMS_CHRG_TRGT_SOC`, la propriété
qu'écrit l'écran d'origine). Sous le niveau actuel, le pop-up prévient que la charge s'arrêtera ; si
la voiture n'a pas pris la nouvelle limite au bout de 20 s, il le dit. Sur les firmwares **A9**
(SWI69, SWI131, SWI132), la limite ne se règle que si la voiture le permet, selon la règle de son
application de charge (type de batterie lu en `0x21704244`, codes 7 et 9) : pas sur une batterie LFP
(MG4 Standard), qui charge jusqu'à 100 %. La barre montre alors le seul niveau, un cadenas
l'explique, et l'heure de fin vise le plein.

**Prix de cette charge** : le coût suit le tarif AC ou DC de l'onglet Statistiques ; le crayon ouvre
un éditeur sans clavier — boutons − et +, pas au choix, coût recalculé en direct, l'ancien barré.
*Tarif par défaut* revient au tarif des Statistiques ; le prix corrigé suit la charge dans l'onglet
Statistiques.

### Réglages
Écran organisé en **quatre onglets** :
- **Langues** : français, anglais, allemand, espagnol, italien, portugais, turc — la langue est
  demandée au premier lancement, dans une fenêtre à sept grandes tuiles
- **Interface** : écran affiché au démarrage, apparence (auto / sombre / clair), taille du texte
  (standard / grand / très grand), **délai avant la fermeture des pop-ups** (1 à 60 s, 10 par
  défaut), **pop-up de charge** (activé par défaut), **module sur l'écran conducteur** (SWI133,
  SWI68, SWI165 — cf. ci-dessus)
- **Réglages avancés** : **Mode Garage** (cf. ci-dessous), retour au profil par défaut à chaque
  passage en READY (option, décochée), vérification des mises à jour au lancement, **alerte de mise
  à jour sur l'écran du véhicule**, **canal bêta**, extinction du véhicule écran allumé, blocage des
  réglages de conduite au-delà d'une vitesse donnée, **API externe** (cf. section dédiée)
- **Infos** : vérification des mises à jour, nettoyage des APK, **consommation de données**
  (aujourd'hui, semaine en cours, mois courant, 30 derniers jours), dialog « À propos » (version de
  l'app, firmware, QR codes), indicateur de firmware, et bouton Diagnostic révélé par 5 clics sur
  le logo

### Pop-ups et fenêtres
Depuis la 2.6.8, tout ce qui s'affiche en fenêtre partage la même présentation : cartes arrondies,
gros boutons, et plus aucune saisie au clavier.

**Par-dessus l'écran de la voiture**, quelle que soit l'application en cours :

| Élément | Présentation |
|---|---|
| Sélecteur de profils | Plein cadre : une carte par profil (mode de conduite en couleur, profil en cours marqué « Actif »), luminosité (Nuit / Moyen / Jour et curseur), Ouvrir MG4Control, Éteindre la voiture |
| Pop-up HVAC | Plein cadre : chauffages, température et ventilation au joystick, A/C et recirculation |
| Pop-up de charge | Plein cadre : niveau et limite de charge, courbe depuis le début, heure de fin, puissance, énergie, autonomie gagnée, coût, durée |
| Confirmation d'une automatisation | Bandeau en bas de l'écran : mesure, seuil, profil proposé, NON / OUI |
| Mise à jour disponible | Bandeau : versions, Plus tard / Ignorer cette version / Installer la MAJ |
| Extinction du véhicule | Bandeau : Annuler / Éteindre — ou « Extinction impossible » hors de P |

Le sélecteur et le pop-up HVAC se pilotent au **joystick droit du volant** (service d'accessibilité
requis) ; un repère dans leur en-tête rappelle les gestes, ou prévient que le joystick est inactif.
Un bandeau ne masque ni n'assombrit rien : ce qui est touché ailleurs va à l'application du dessous.
Tout se referme seul après le **délai des pop-ups** (*Réglages → Interface*), sauf le pop-up de
charge, qui reste jusqu'à sa croix ou au READY ; sur un bandeau, le décompte est écrit sur le bouton
qui vaut « non » — c'est lui que le délai déclenche.

Le **raccourci qui ouvre un pop-up le referme** : un second appui ferme le sélecteur de profils
comme la fin du délai (en cas de conflit Bluetooth, le premier profil proposé s'applique), et de même
les pop-ups HVAC et de charge. Quand ce raccourci est un appui long sur une touche du joystick droit, un appui court
sur cette touche continue de naviguer — au relâchement —, et un appui maintenu jusqu'au seuil
referme la fenêtre sans naviguer : au pop-up HVAC, refermer ne change ni la température ni la
ventilation.

Le **sélecteur de profils s'ouvre sur le profil actif** : le joystick part de sa carte, et la rangée
défile pour la montrer s'il est au-delà du cinquième profil. En conflit Bluetooth, il part de la
première carte, celle qu'applique le délai.

**Dans l'application** :
- les **confirmations** se ressemblent toutes — un pictogramme, un titre, un texte, un ou deux
  boutons — et leur couleur dit la nature de l'action : bleu pour une action ordinaire, orange pour
  un avertissement, rouge pour ce qui efface ou éteint ;
- une **application** ou un **profil** se choisit dans une grille de tuiles qui défile ;
- un **raccourci avancé** se modifie dans deux listes côte à côte ;
- les **horaires** et le **tarif** d'une recharge (onglet Statistiques), comme le **prix** de la
  charge en cours (pop-up de charge), se règlent aux boutons − et +, sans clavier — celui de la voiture couvre la moitié de l'écran. Les minutes vont de cinq en
  cinq, le pas du tarif se choisit, et un appui maintenu répète le pas.

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
| Baisse de volume en quittant la voiture | |
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
Une mise à jour ne se découvre plus seulement en ouvrant MG4Control : un bandeau apparaît **en bas
de l'écran, par-dessus l'infodivertissement**, comme celui de la confirmation de profil, et annonce
la version installée face à la nouvelle. Quatre choix :

| Bouton | Effet |
|---|---|
| **Installer la MAJ** | Ouvre MG4Control sur le dialogue de mise à jour habituel, sans refaire la requête réseau |
| **Ignorer cette version** | Cette version n'est plus proposée, ni ici ni au lancement de l'application |
| **Plus tard** | Referme le bandeau : rien n'est retenu, l'annonce reviendra |
| **Ne plus me prévenir** | Coupe ce bandeau. Le dialogue au lancement de MG4Control, lui, reste |

Sans réponse, le bandeau se referme seul après le délai des pop-ups, ce qui vaut « plus tard ». Il
n'assombrit ni ne bloque rien : le reste de l'écran reste utilisable.

Quatre garde-fous, parce que ce chemin n'est déclenché par personne :
- la vérification a lieu **20 s après le coup de contact** — au moment même, la liaison données de
  la voiture n'est pas encore montée ;
- **six heures minimum** entre deux interrogations réseau, la voiture étant sur un forfait données ;
- **rien ne s'affiche en roulant** (même verrou que le sélecteur de profils) ;
- une version déjà proposée ne l'est plus dans la même session — et le marquage n'a lieu que si le
  bandeau est **réellement apparu**.

L'interrupteur historique « vérifier les mises à jour au lancement » coupe aussi ce bandeau : c'est
un réglage que l'utilisateur croit global, et il l'est.

### Profils
- Liste des profils avec application, définition par défaut, modification, suppression
- **Ordre des profils** : un appui long sur une carte de la liste la soulève ; on la fait glisser à
  sa place (la liste défile seule près de ses bords, un bandeau indique la position) et l'ordre est
  enregistré au relâcher. Cet ordre est celui du sélecteur, des boutons de la page « Cycle profils »
  et du cycle par défaut ; en conflit Bluetooth, c'est la priorité — le premier des profils en
  conflit s'applique à la fin du délai
- **Éditeur en plein écran** organisé en trois catégories : Conduite, Sécurité, Confort
- **Un interrupteur par catégorie**, en tête de chaque onglet : coupée, l'onglet se replie et le
  profil n'y écrit rien — un profil peut ne régler que la sécurité, ou que le confort. Il en faut au
  moins une pour enregistrer ; les profils déjà créés gardent leurs trois catégories actives
- Le nom du profil et le réglage « profil par défaut » restent visibles sur les trois onglets
- **Régénération inchangée** : un interrupteur sur la section Régénération de l'onglet Conduite ;
  coupé, le profil ne touche ni au niveau de régénération ni à la conduite à une pédale, les boutons
  de niveau se grisent, et la liste comme le sélecteur l'indiquent (« Régén. inchangée »). Les
  profils déjà créés gardent leur régénération
- Volant et sièges chauffants disposent d'un interrupteur de **prise en compte** : décoché, le
  profil ne touche pas au réglage au lieu de l'éteindre
- **Gestion automatique des feux de route** : même interrupteur, décoché par défaut — un profil
  existant n'y touche donc pas
- **Climatisation** : bloc facultatif de l'onglet Confort, décoché par défaut, reprenant les
  commandes du Dashboard — marche/arrêt, A/C, ventilation automatique, consigne, vitesse,
  recyclage et dégivrages

#### Bloc climatisation du profil
Décoché, le profil ne touche pas à la climatisation : c'est le comportement de toujours, et celui
de tous les profils déjà enregistrés. Coché, il applique ce qu'il porte au démarrage, au contact
et à chaque application manuelle.

Trois règles s'y appliquent, qui viennent du véhicule et non de l'interface :

| Règle | Conséquence |
|---|---|
| Clim éteinte | Rien d'autre n'est écrit — une consigne posée sur une clim à l'arrêt la rallume sur certains firmwares |
| Mode AUTO | La vitesse de ventilation n'est pas appliquée : la régler ferait sortir du mode auto |
| « Inchangé » | Recyclage et dégivrages ne sont pas touchés — sans cette valeur, un profil qui ne s'en préoccupe pas les éteindrait |

Les curseurs sont bornés à la saisie (15–33 °C, 1–11), mais les **vraies** limites sont lues sur le
véhicule au moment d'appliquer et la consigne y est clampée : elles diffèrent entre A9 et les
autres firmwares.

> [!NOTE]
> **Le profil est prioritaire sur l'automatisation A/C.** Si le profil appliqué depuis le démarrage
> de l'application porte un bloc climatisation, l'automatisation par température ne fait rien et le
> journalise. Sans cette règle les deux s'écriraient dessus au contact, dans un ordre que rien ne
> garantit. Au démarrage, quand un profil enregistré règle la climatisation, l'automatisation attend
> que le profil du démarrage soit appliqué — une dizaine de secondes au plus — avant de décider : le
> profil du trajet précédent ne compte pas.

### Compatibilité firmware inconnue (UNKNOWN)
- Fenêtre d'avertissement au démarrage si le firmware n'est pas reconnu, avec la version lue sur
  la voiture
- L'utilisateur peut fermer l'application ou continuer
- « Continuer » ouvre directement *Réglages → Infos*, où les pastilles de firmware sont cliquables
  pour forcer un mode de compatibilité
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

Le module sur l'écran conducteur est réservé à SWI133, SWI68 et SWI165 (cf.
[Écran conducteur](#écran-conducteur)). Le pop-up de charge fonctionne partout ; sur les A9, sa
limite de charge ne se règle que si la batterie le permet (pas en LFP — cf.
[Pop-up de charge](#pop-up-de-charge)).

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
│   │   │   ├── StatsTracker.kt        # Statistiques : trajets et charges (machine à états pure, testée)
│   │   │   ├── StatsSummary.kt        # Statistiques : agrégats d'une période (coût, consommation)
│   │   │   ├── PowerIntegrator.kt     # Énergie par tension × courant (firmwares sans compteurs)
│   │   │   ├── ClusterGauge.kt        # Écran conducteur : échelles des jauges, place du carré, moyenne du trajet
│   │   │   ├── ClusterTab.kt          # Écran conducteur : onglets du combiné
│   │   │   ├── ChargeModule.kt        # Module de charge du combiné : cible, temps restant, énergie
│   │   │   ├── ChargePopupModel.kt    # Pop-up de charge : limite, échelle de la courbe, relevés de la charge
│   │   │   ├── ChargeEnergyLearning.kt # Énergie des charges : table apprise par tranche de 1 %, source retenue
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
│   │   │   ├── StatsCollector.kt      # Relevés des statistiques (seulement si elles sont activées)
│   │   │   ├── ClusterTabReader.kt    # Onglet affiché sur le combiné
│   │   │   ├── BatteryTypeConfig.kt   # Type de batterie (A9) : limite de charge réglable ou non
│   │   │   ├── ChargeProbe.kt         # Sonde de diagnostic de la charge (journal MG4_CHARGE)
│   │   │   └── VehicleWriteGate.kt    # Verrou de vitesse posé dans les primitives d'écriture
│   │   │
│   │   ├── stats/
│   │   │   └── StatsStore.kt          # Historique des statistiques : fichier, purge à la lecture
│   │   │
│   │   ├── accessibility/
│   │   │   ├── KeyCaptureService.kt   # Service d'accessibilité — voit les touches avant le launcher
│   │   │   ├── AccessibilityServiceList.kt # Le service est-il activé ? (formes longue et courte du nom)
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
│   │   │   ├── StatsFragment.kt       # Statistiques (rail 3 onglets)
│   │   │   ├── ProfileAdapter.kt      # Adaptateur RecyclerView profils
│   │   │   ├── AppDialog.kt           # Fenêtres de l'app : confirmation, grille de choix, listes, boutons − / +
│   │   │   ├── RestoreProfilesDialog.kt # « Restaurer les profils ? » — liste des profils retrouvés
│   │   │   ├── StatsDialogs.kt        # Compléter une recharge, corriger un tarif (sans clavier)
│   │   │   ├── ClusterSettingsPreview.kt # Aperçu de l'écran conducteur dans les réglages (trajet simulé)
│   │   │   ├── ClusterTabDialog.kt    # Diagnostic : onglet affiché sur le combiné
│   │   │   ├── widget/ClusterGaugeView.kt # Module de l'écran conducteur (anneau, arc ou barre discrète ; charge)
│   │   │   ├── widget/ChargeCurveView.kt # Courbe du pop-up de charge
│   │   │   ├── widget/ChargeLimitSliderView.kt # Barre de batterie, curseur de la limite de charge
│   │   │   ├── ConsoleFragment.kt     # Journal de debug en temps réel
│   │   │   ├── DriveRegenFragment.kt  # Héritage (non utilisé en v2)
│   │   │   ├── ClimateFragment.kt     # Héritage (non utilisé en v2)
│   │   │   └── AdasFragment.kt        # Héritage (non utilisé en v2)
│   │   │
│   │   ├── service/
│   │   │   ├── MG4ControlService.kt   # Service de premier plan (boot, raccourcis, API externe)
│   │   │   ├── OverlayKit.kt          # Socle des pop-ups et bandeaux : échelle, fonds, place à l'écran
│   │   │   ├── OverlayNavigation.kt   # Navigation au joystick droit dans un pop-up
│   │   │   ├── ProfilePickerOverlay.kt# Sélecteur de profils (pop-up plein cadre)
│   │   │   ├── HvacPopupOverlay.kt    # Pop-up HVAC (+ HvacPopupSkin.kt, sa peinture)
│   │   │   ├── ProfileConfirmOverlay.kt # Bandeau OUI/NON des automatisations
│   │   │   ├── VehiclePowerOffOverlay.kt # Bandeau de confirmation d'extinction
│   │   │   ├── UpdateOverlay.kt      # Bandeau « MAJ disponible » sur l'écran du véhicule
│   │   │   ├── ChargeMonitor.kt       # Suivi de charge en arrière-plan (pop-up et statistiques)
│   │   │   ├── ChargePopupOverlay.kt  # Pop-up de charge (plein cadre)
│   │   │   └── ClusterModuleOverlay.kt # Fenêtre du module sur l'écran du combiné, relevés et onglet
│   │   │
│   │   ├── receiver/
│   │   │   └── BootReceiver.kt        # Récepteur de démarrage système
│   │   │
│   │   ├── util/
│   │   │   ├── FirmwareInfo.kt        # Détection firmware + mode forcé
│   │   │   ├── ClusterModuleSettings.kt # Écran conducteur : activation, module par onglet, style, pastille, unités, actualisation, animation
│   │   │   ├── ChargePopupSettings.kt # Pop-up de charge : activé ou non
│   │   │   ├── GarageMode.kt          # Mode Garage — met tous les automatismes en veille
│   │   │   ├── FirmwareHelper.kt      # Lecture version firmware complète (async)
│   │   │   ├── LocaleHelper.kt        # Gestion de la langue (7 langues) et de la taille du texte
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
    val regenEnabled: Boolean?, // Régénération : null ou true = appliquer, false = inchangée
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
    val autoHighBeam: Boolean?,       // Feux de route auto ; null = le profil n'y touche pas
    val btDeviceMac: String?          // Appareil Bluetooth associé
)
```

> **Pourquoi ces champs sont nullables :** Gson n'appelle pas le constructeur Kotlin. Un défaut
> déclaré `= true` ne s'appliquerait donc **pas** aux profils déjà enregistrés — champ absent du
> JSON, la JVM met `false`, et l'ESC se retrouverait désactivé sur tous les anciens profils.
> `null` signifie « profil antérieur à cette fonctionnalité », et ce sont les accesseurs qui
> tranchent.

### Persistance

Les profils sont sérialisés en JSON via **Gson** et stockés dans `SharedPreferences`. Maximum **10 profils** par appareil
(`ProfileManager.MAX_PROFILES`). Au-delà de cinq, le sélecteur fait défiler ses cartes et la page « Cycle profils »
passe sur deux rangées de cinq. Une version antérieure à la 2.6.9 lit et applique les dix, mais n'en crée plus et n'en
restaure que cinq depuis la sauvegarde du véhicule.

### Application d'un profil

`ProfileApplier.apply()` exécute les appels matériels dans l'ordre suivant sur `Dispatchers.IO` :
1. Mode de conduite (rapide — binder)
2. Niveau de régénération (rapide — binder)
3. Volant chauffant (~2 s — polling de confirmation d'état)
4. Siège gauche (~7 s — polling par toggle)
5. Siège droit (~7 s — polling par toggle)
6. Attente Katman4, puis ADAS selon firmware, anti-collision avant, ESC et somnolence, sortie de voie,
   feux de route automatiques

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
    ├──► AutomationFragment
    ├──► ShortcutsFragment
    ├──► StatsFragment
    └──► SettingsFragment
```

La barre du haut présente les onglets dans cet ordre : Profils, Automatisation, Raccourcis,
Statistiques, Réglages. Ses boutons fonctionnent en bascule : un second appui revient au dashboard.

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

### Fenêtres et pop-ups
- **Fenêtres de l'application** — `ui/AppDialog` : une carte arrondie sans décor, à la largeur
  qu'on lui donne (celle par défaut d'un dialogue Android est trop étroite pour un écran de
  voiture). `confirmer` pour une confirmation simple, au ton neutre, d'alerte ou de danger ;
  `choisir` pour une grille de tuiles ; `options` pour une liste à choix unique ; `segments` pour un
  choix à cases ; `repeter` pour un bouton − ou + qui se répète quand on le maintient. Tout nouveau
  oui / non de l'application passe par `AppDialog.confirmer`.
- **Pop-ups et bandeaux** — `service/OverlayKit` : leurs mises en page sont écrites pour une carte
  de base (1108 × 392 dp) et gonflées avec une densité corrigée, pour occuper la même part de
  l'écran partout. Si la fenêtre accordée est plus petite que prévu, la carte est réduite d'un
  bloc et le journal `MG4_OVERLAY` donne la place trouvée. Les teintes sont posées par le code :
  ces mises en page sont gonflées hors de l'activité, sans la fabrique AppCompat.

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

Données météo de la luminosité automatique : [Open-Meteo.com](https://open-meteo.com), sous licence
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

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

#### Custom drive mode
Three settings the vehicle only exposes in that mode: **power** (Eco / Normal / Sport),
**steering** and **pedal force** (Comfort / Normal / Sport). Available on all six firmwares
through three distinct routes — `VehiclePropertyManager` signals on SWI133,
`VehicleSettingManager` methods on SWI68/165, and `CarVehicleSettingClient` methods on A9.

Present in two places, with a different reveal rule:

| Screen | The card appears when |
|---|---|
| Dashboard, Driving tab | the **car** is in Custom mode, including when it was set from the stock screen |
| Profile editor, Driving tab | the **profile** being edited has Custom as its mode |

On the Dashboard, a row whose state the vehicle does not report disappears: the command exists on
every firmware, but nothing guarantees the trim carries the hardware.

> [!WARNING]
> The three settings do not share one scale, and the pedal is a trap: **Normal is 0 there**, not
> the middle value. Power is 1/2/3 on SWI133/68/165 but 2/3/4 on A9, where the car reuses the
> drive-mode scale. The app therefore never handles those numbers — it passes a 0/1/2 index and
> the conversion lives in `MG4Hardware`.

In a profile they are only written when its drive mode is Custom, and a profile saved before this
feature writes nothing until it has been reopened and saved.

#### Intelligent battery heating
The stock option hidden in the battery widget ("Energy consumption" tab) heats the battery while
driving: fast charging gets better but range drops, and once switched on it never switches itself
off. MG4Control puts it ON/OFF on the Dashboard (Driving tab, with the time left before the
automatic switch-off), as a shortcut, and offers to switch it off automatically (see Automation).
One vehicle property, `DRVNG_PTC_HEAT`, on all six firmwares: through the `VehiclePropertyManager`
on SWI133, like SystemUI, directly elsewhere.

> [!NOTE]
> The stock screens do not read the state the same way: 0 = on for the older platform
> (SWI133/68/165), 1 = on for A9. The app follows each one; the Diagnostic probe (`MG4_BATHEAT`)
> logs the raw value so it can be checked on a car.
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
- **Automatic high beam**: On / Off
- **Screen brightness**

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
| Driving | One Pedal · Custom Regeneration Cycle · Energy saving · Battery heating ON/OFF |
| Safety | ESC · Drowsiness · Drowsiness: sensitivity · Forward collision · ADAS · Traffic signs (TSR) · Overspeed alert · Speed limit change alert · Sound |
| Comfort | Left seat heating · Right seat heating · Heated steering · Climate ON/OFF · Climate: A/C ON/OFF · Climate: temperature ± · Climate: fan ± · Front defrost · Rear defrost · Recirculation · Automatic high beam ON/OFF · Brightness ± · HVAC pop-up |
| Windows | Open all windows · Close all windows |
| Media | Play / Pause · Next track · Previous track · Volume + · Volume - · Audio source cycle |
| App and vehicle | Apply a profile · Profile picker · Profile cycle · Charging pop-up · Open MG4Control · Launch an app · Power the car off |

Comfort and safety actions **re-read the state from the vehicle on every press** instead of
remembering their own: the user also acts from the stock screen, so a remembered state would drift
immediately. An unreadable state means doing nothing — nothing is ever written blind.

The **One Pedal**, **Energy saving**, **Traffic signs (TSR)**, **Overspeed alert**, **Speed limit
change alert** and **Sound** toggles also re-read the real state: a One Pedal switched on by the
start-up profile is switched off on the very first press. Where that state cannot be read
reliably, they fall back on their last command and alternate as before; only the forward
collision A/B cycle still follows a remembered state.

Each row of the advanced list shows the **button** (name and code), the **press type**, the
**action**, then *Edit* and *Delete*. Reassigning a button already used with the same press type
asks for confirmation and names the action about to be replaced. *Edit* opens a window with two
side-by-side lists — the action and the profile — each opened on the current choice; the app or
profile a shortcut targets is picked from a grid of tiles.

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

#### HVAC pop-up
The **HVAC pop-up** action opens the climate controls on top of the current screen — including
when Android Auto or CarPlay fills the screen and hides the stock climate bar. The steering
wheel's right joystick moves no focus there: up/down set the temperature, left/right the fan
speed, the centre click toggles AUTO mode (adjusting the fan speed leaves it). At fan level 1,
pressing left turns the climate off ("OFF"); while it is off, left does nothing and right sets it
back to level 1. A hint in the header
recalls these gestures, or warns that the joystick is inactive until the accessibility service is
enabled — the window then still works by touch. Chosen by touch: A/C, recirculation, air direction
and, on equipped firmwares (SWI133, SWI68, SWI165), the heated steering wheel and each seat's
level, one colour per level (green, orange, red). The window closes on a second press of the
shortcut, with its cross, or by itself after the pop-up delay.

#### Profile cycle
The **Profile cycle** action steps through the profiles, in an order composed on its page (at
least two profiles; without it, every profile in the order of their list). On each press the
shortcut targets the profile that follows the active one and shows its name; it applies it one
second later, then confirms it on screen. Two quick presses therefore skip a profile without
applying it. If the speed lock refuses the write, nothing changes.

#### Audio source cycle
The **Audio source cycle** action switches to the next source of a cycle composed on the
*Source cycle* page, as tiles: radio, Bluetooth, USB, online, CarPlay / Android Auto (at least
two sources; without it, radio then Bluetooth). On each press, *Source: …* shows and the car
switches to it one second later; two quick presses therefore skip a source. There is no "change
source" command: the app asks the target source to play, which brings it to the front, without
pausing anything. Availability is not checked: a source with nothing to play stays silent, and
the next press moves on, because the cycle starts again from the last source chosen, not from
the one playing.

- **SWI133, SWI68, SWI165**: through the SAIC media service, source by source.
- **SWI69, SWI131, SWI132**: Bluetooth goes through the SAIC Media app, like the original source
  key — that app switches the car's audio output to Bluetooth before starting playback. USB
  targets the player of the port the stick is plugged into (one player per port). "Online" is
  not offered: that source does not exist on these firmwares.
- **CarPlay / Android Auto**: through the original AllGo receiver; if it has no phone, the app
  tries wireless Android Auto through DiAuto-MG4.

### Automation
- **Apply a profile from the outside temperature**: threshold, direction (below/above), profile to
  apply, direct execution or confirmation banner
- **Apply a profile from the battery level**: when the charge drops below a threshold set with a
  slider (5–60 %), at start-up or while driving; once per episode, re-armed at every start or after
  charging; direct execution or confirmation banner
- **Confirmation banner**: the question is asked at the bottom of the screen, without hiding
  navigation — the reading and its threshold, the proposed profile, NO and YES. It is answered by
  touch or with the steering wheel's right joystick (YES preselected); on NO, or once the pop-up
  delay has run out, nothing is applied
- **Automatic brightness at start-up**: when the car goes READY, the screen takes a brightness
  chosen from the lights and/or the weather — at least one source, lights only by default.
  **Lights only** (no mobile data, no GPS; the only mode of the offline version): two adjustable
  levels, lights off (80 % by default) and lights on (20 %).
  **Weather** (tick box, as it uses a little data): a four-point adjustable curve (night, dusk,
  overcast, full sun) maps the outdoor light to a brightness. The car does not measure light, so
  it is estimated from the sun height (GPS position + time, computed offline) and the
  [Open-Meteo](https://open-meteo.com) sunshine forecast on a 25-point grid around the car (an
  area of about 60 × 60 km: while driving, the weather where you are), cached for 3 days and
  refreshed every 8 h at most or when leaving the area (~10 KB per request), position rounded to
  ~10 km. With the lights as well, side lights on (garage, tunnel, night): Night point of the
  curve. "Adjust brightness while driving" option: also readjusts at every lights change (tunnel)
  and, with the weather, as soon as the estimated light drifts 10 points; adjusting by hand pauses
  it until the next start. Changes fade over one second. "Test now" button
- **Battery heating switch-off**: switches intelligent battery heating off after a time set with a
  slider (5 to 120 min, 30 by default). The countdown starts when the option is switched on,
  driving or parked — stock screen, SystemUI "low temperature" window, MG4Control or shortcut —,
  or at start-up if it was already on, and only applies to the current trip. A message is shown
  when it switches off
- **Temperature-triggered A/C**: two independent rules (above / below), each with its threshold,
  setpoint, fan level, defrosters, automatic mode and recirculation
- **Volume drop when leaving the car**: media volume goes down to the level set with a slider as
  you leave, on all six firmwares. Where the car reports its doors (SWI133, SWI132) the trigger is
  a front door opening, with selectable doors and restore on close; elsewhere, no door sensor
  being reachable, **leaving READY state** is used instead and the restore, when asked for,
  happens on the way back to READY. With the option off, these settings stay readable but greyed
  out
- Each automation opens unfolded when it is on, unfolds when switched on and folds when switched
  off; in between, its chevron folds or unfolds the settings freely

#### Automatic power window closing
Card built like the other automations: enable switch next to the description, settings unfolded
with the chevron (the card opens unfolded when the option is on).

- **Close on leaving the car** — car in P and closing armed: when the car leaves READY state
  (seat belt unfastened and driver door opened, or car switched off), every window closes after
  the chosen delay; going back to READY before the end cancels it. Arming is configurable (speed reached and/or time
  switched on, either or both), the delay ranges from 0 to 30 s, and an optional warning beep,
  with adjustable volume, can accompany the countdown. With the option off, these settings stay
  readable but greyed out.
- **Travel time** — only the driver's window has a sensor and its stock automatic travel; the
  other three get a pulse of adjustable length (5 s by default).
- **Advanced: per-window calibration** — measures each window's real travel, more accurate than
  the general time; measurements are kept if the option is switched off.

Travel time and calibration stay adjustable with the option off: the "open / close all windows"
shortcuts use them too.

> [!WARNING]
> Travels commanded by the app have no anti-pinch protection, except on the driver's window.
> Keep clear of the windows during a closing sequence, especially an automatic one, which starts
> once you are already outside.

### Profile Management
- Save up to **10 custom profiles** (5 before 2.6.9)
- Instant one-tap profile application
- Automatic default profile application **on vehicle startup**
- **Bluetooth pairing**: a profile can be tied to a device and applied when it connects
- **Automatic backup into the vehicle's storage**: it survives uninstalling the app, and on
  reinstall a window lists the profiles found and offers to restore them
- Trigger precedence: manual → temperature → Bluetooth → default profile

### Statistics
A top-bar tab, **off by default**: until it is turned on, nothing is recorded. Everything stays in
the car, in a private file of the app; nothing is sent anywhere. Three sub-tabs on the usual rail:

- **General**: summary over 30 days or the whole history — distance, consumption, energy used,
  recovered and charged, cost. Settings: kWh price for slow and fast charging (up to 100,000, for
  every currency), currency, the car's battery (51, 64 or 77 kWh) and its **state of health**
  (SOH), short-trip filter, retention (30 days to 1 year, 3 months by default) and "Delete all"
- **Trips**: each trip with its duration, distance, consumption and energy breakdown
- **Charging**: energy charged, cost and AC / DC sessions, including charges made while the head
  unit was asleep

Where the figures come from:

| Measure | Source |
|---|---|
| Trip boundaries | Ignition — the car's energy counters restart from zero at the same moment |
| Distance | Integration of the speed, read every 10 s while driving; the odometer, which only publishes whole kilometres, is the safeguard |
| Trip energy, SWI133 and SWI132 | The car's counters, in tenths of a kWh: total, climate, accessories, regeneration |
| Trip energy, other firmwares | Their counters stay at zero: battery voltage × current, read every second while driving. The breakdown is limited to "Motor" and "Climate control and others", in whole kWh |
| Charge energy, AC | Percentage gained × capacity used: usable capacity of the chosen battery × state of health |
| Charge energy, DC | Measured during the charge (voltage × current); the part not seen is estimated from a table learnt per 1% band (see *Fast charges*) |
| Charge type and power | Type read from the car (AC or DC); power measured if the head unit is awake during the charge, otherwise computed (energy ÷ duration) and labelled as such |

Rules worth knowing:
- **Overnight charge**: the head unit sleeps during the charge and nothing is read. On wake-up, the
  percentage is compared with the last reading and the charge is **reconstructed**. It can be
  **completed** — plug type, start and end times, with − and + buttons — to get its average power
- **Trip cost**: it follows the **average price of the energy actually charged** over the period,
  not the home tariff — otherwise a fast-charger top-up would vanish from the cost. A charge's
  tariff is **corrected** from its detail; the average price and trip costs follow
- **Consumption over a period**: computed on the totals, not by averaging trips — otherwise a 2 km
  trip would weigh as much as a 200 km one
- **Displayed precision**: when a consumption's uncertainty exceeds 10% (short trip, counter in
  tenths of a kWh), it is shown with "≈" and no decimal
- **Short trips**: filter off by default; when on, a trip shorter than the chosen distance (1 km by
  default, 0.1 to 50 km) is not recorded
- **Battery and state of health**: the state of health is set from 50 to 100% in 0.5% steps, with
  the − and + buttons (hold them to scroll), and the capacity used shows below. 100% by default: a
  dealer or an OBD app can measure it. Changing either recalculates the charges recorded from the
  percentage — a measured energy doesn't change; until the battery has been confirmed, the Charging
  tab reminds you
- **Fast charges**: the displayed percentage doesn't track the energy going in (from 62.8 to 70.1%,
  5.0 kWh measured at the battery, while the percentage difference only accounted for 4.2). A DC
  charge's energy is therefore **measured** during the charge, read every 2 s, whether the charging
  pop-up is on or not. The head unit turns off when the car is locked: the part not seen — the end,
  or the start if the screen came back on in the middle of a charge — is estimated from a **table
  learnt per 1% band**, fed by every band seen in full (a band is used from two measurements, the
  most recent ones weigh more, an implausible measurement is rejected). A charge never seen is
  estimated band by band, or from the percentage while the table is empty. The charge's details say
  where its energy comes from: *measured during the charge*, *partly measured*, *estimated from
  previous charges* or *estimated from the battery level*. AC charges, long and mostly done with the
  screen off, are still computed from the percentage
- **Power cuts**: the ongoing trip is saved after each reading, and a trip cut short by the head
  unit switching off is closed at the next start-up. The history is written to a temporary file
  then renamed: a power cut never truncates it
- **Clock**: a head unit woken up by charging may restart on its factory clock (2019); readings
  dated before 2025 are discarded

### Driver display
An option in *Settings → Interface*, **off by default**, on **SWI133, SWI68 and SWI165** only: an
MG4Control module shows in the centre of the instrument cluster, behind the wheel, instead of the
original page of the Navigation and Music tabs.

The cluster is a separate computer. Only a **260 × 260 px** square in its centre comes from the
head unit: SAIC draws it there with a system app (`clusterprojection`), on Android's second display
(no. 1, 800 × 480). MG4Control lays its window exactly over that square, with that app's background
to blend in, as soon as the app starts — without waiting for READY. On the **A9** firmwares (SWI69,
SWI131, SWI132), that app does not exist and the cluster draws everything itself: nothing can show
there, and the card does not appear in the settings.

| Module | Gauge | Large figure | Footer |
|---|---|---|---|
| **Consumption** | Graduated ring, logarithmic scale up to 3,000 Wh/km (300 kWh/100 km) | Wh/km or kWh/100 km while driving; kW drawn when stationary (below 5 km/h); when regenerating, a negative consumption in blue (25 kW recovered at 60 km/h: −41.7 kWh/100 km), the kW recovered below 5 km/h | Trip average · energy recovered |
| **Power** | Graduated arc from 60 kW recovered (left) to 320 kW (right), logarithmic scale | Whole kW while driving (one decimal when stationary), negative when regenerating; below, in large type, the instant consumption, negative and blue when regenerating | Trip average, alone and centred — the battery is already on the cluster |

- **One module per tab**: None, Consumption or Power for the Navigation tab, and likewise for the
  Music tab. The module follows the tab shown on the cluster (`IPK_CLUSTER_TAB_BTN_STS`); trip
  computer, tyre pressure and settings are drawn by the cluster itself and keep their page
- **Style**: *Dynamic* (the ring or the arc, and the counting figure) or *Discreet*, for those who
  find scrolling figures distracting: a thin graduated bar, fixed figures under the ticks. Power:
  zero marked, regeneration on the left in blue, power on the right in light grey. Consumption: a
  short regeneration zone on the left, then the scale; an "avg" triangle marks the trip average,
  and the bar is green below it, orange above. Same scales as the dynamic style, same badge, same
  average at the bottom; the bar follows over two seconds, without jitter, whatever the refresh rate
- **Badge** at the top left: *Profile* (default) — the last applied profile, in capitals, in the
  colour of **the profile's** drive mode (the car's if the profile doesn't set driving) —, *Drive
  mode*, or *None*. Never wider than the longest drive mode's badge: a longer name ends with an
  ellipsis
- **Units**: the **gauge** unit, Wh/km (default) or kWh/100 km, with one decimal below 100 — the
  ring does not move from one unit to the other, its graduations (0 · 100 · 200 · 500 · 1k · 3k Wh/km,
  or 0 · 10 · 20 · 50 · 100 · 300 kWh/100 km) follow the unit — and, separately, the **trip
  average** unit: Wh/km, kWh/100 km or km/kWh (by default, the gauge unit)
- **Tightened power scale**: logarithmic, so the arc moves while driving — 20 kW fill a quarter of it,
  50 kW 44%, 100 kW 63%; on the regeneration side, 10 kW make 36%. Graduations 20 · 50 · 100 · 200 · 320
  and 10 · 30 · 60. The fill stops cleanly at zero, without spilling over to the other side
- **Colours**: orange for consumption, blue for regeneration, whatever the value
- **Advanced**: the **refresh rate** of power, speed and gauge, from 0.1 to 1 s (1 s by default;
  shorter is more responsive, but the figure moves more), and the **animation length**, from 0.2 to
  2 s (0.8 s by default). The drive mode and the profile are read every 10 s
- **The figure counts** from one value to the next (100, 101… 300) instead of jumping, and the
  gauge glides along, over the animation length set; a change of unit (Wh/km → kW when stationary)
  jumps straight away
- **Trip average**: net energy (regeneration deducted) ÷ distance integrated since entering READY,
  shown from 200 m. It is computed on the spot rather than taken from the Statistics tab, which can
  be turned off
- **While charging**, the **charge module** replaces the gauge on the tabs that have one, as soon as
  a charge is detected, AC or fast: in large type the time left to the limit, below it the level to
  a tenth and the end time, the battery bar with the limit mark, then power, energy and duration.
  The badge's lightning bolt pulses and a highlight runs along the bar up to the limit; when done,
  everything freezes and turns green ("Charge complete"). When unplugged or in READY, the gauge
  comes back
- **Settings card**: on the left, a **preview** — the real gauge on a simulated trip, following
  every setting, for the Navigation or Music tab; on the right, the settings in three groups
  (Modules, Appearance, Units), then "Advanced", folded under its summary

### Charging pop-up
An option in *Settings → Interface*, **on by default**, on every firmware: as soon as a charge
starts, AC or fast, a full-frame pop-up opens on the head unit — including when the app starts in
the middle of a charge. It has no countdown: it closes with its cross, when the car goes to READY,
or with the **Charging pop-up** shortcut, which also reopens it (with the option off, the shortcut
says where to turn it on). A charger pause of less than 10 minutes remains the same charge.

- **Left**: the battery to a tenth, the level bar, then the estimated end time in large type and
  the time left to the limit
- **Middle**: the curve of power (blue area) and level (green line) **since the start of the
  charge**, not since the pop-up opened — the app logs the charge in the background, one point every
  5 s (a gap if the screen turns off). The kW axis goes up to the highest power reached, the limit
  is a grey dotted line, and a green dotted line joins the current level to the limit at the end
  time given by the car
- **Right**: power (and the maximum reached), energy added, range gained — the change in the car's
  estimated range since the start —, estimated cost and duration

**Charge limit**: the battery bar works as a slider, from 40 to 100% in steps of 10; the targeted
step turns bold, and the limit goes to the car on release (`BMS_CHRG_TRGT_SOC`, the property the
original screen writes). Below the current level, the pop-up warns that the charge will stop; if the
car hasn't taken the new limit after 20 s, it says so. On the **A9** firmwares (SWI69, SWI131,
SWI132), the limit can only be set when the car allows it, following its own charging app's rule
(battery type read at `0x21704244`, codes 7 and 9): not with an LFP battery (MG4 Standard), which
charges to 100%. The bar then shows the level only, a padlock explains why, and the end time aims
for full.

**Price of this charge**: the cost follows the AC or DC tariff from the Statistics tab; the pencil
opens a keyboard-free editor — − and + buttons, selectable step, cost recalculated live, the old one
struck through. *Default tariff* goes back to the Statistics tariff; the corrected price follows the
charge into the Statistics tab.

### Settings
Screen organised into **four tabs**:
- **Languages**: French, English, German, Spanish, Italian, Portuguese, Turkish — the language is
  asked on first launch, in a window with seven large tiles
- **Interface**: screen shown at startup, appearance (auto / dark / light), text size (standard /
  large / extra large), **pop-up closing delay** (1 to 60 s, 10 by default), **charging pop-up**
  (on by default), **module on the driver display** (SWI133, SWI68, SWI165 — see above)
- **Advanced**: **Garage mode** (see below), return to the default profile each time the car
  enters READY (option, off by default), update check at launch, **update alert on the
  vehicle screen**, **beta channel**, power the car off while keeping the screen on, block driving
  settings above a given speed, **external API** (see the dedicated section)
- **Info**: update check, APK cleanup, **data usage** (today, current week, current month, last 30
  days), "About" dialog (app version, firmware, QR codes), firmware indicator, and a Diagnostic
  button revealed by 5 taps on the logo

### Pop-ups and windows
Since 2.6.8, everything shown in a window shares the same look: rounded cards, large buttons, and
no more keyboard input.

**On top of the car's screen**, whatever app is running:

| Item | Layout |
|---|---|
| Profile picker | Full frame: one card per profile (drive mode in colour, current profile marked "Active"), brightness (Night / Medium / Day and a slider), Open MG4Control, Power the car off |
| HVAC pop-up | Full frame: heating, temperature and fan speed on the joystick, A/C and recirculation |
| Charging pop-up | Full frame: level and charge limit, curve since the start, end time, power, energy, range gained, cost, duration |
| Automation confirmation | Banner at the bottom of the screen: reading, threshold, proposed profile, NO / YES |
| Update available | Banner: versions, Later / Skip this version / Install update |
| Vehicle power-off | Banner: Cancel / Power off — or "Cannot power off" outside P |

The picker and the HVAC pop-up are driven with the **steering wheel's right joystick**
(accessibility service required); a hint in their header recalls the gestures, or warns that the
joystick is inactive. A banner neither hides nor dims anything: whatever is touched elsewhere goes to
the app underneath. Everything closes by itself after the **pop-up delay** (*Settings → Interface*),
except the charging pop-up, which stays until its cross or READY; on a banner, the countdown is
written on the button that means "no" — the one the delay triggers.

The **shortcut that opens a pop-up closes it**: a second press closes the profile picker just like
the end of the delay (in a Bluetooth conflict, the first profile offered is applied), and the HVAC
and charging pop-ups likewise. When that shortcut is a long press on a right-joystick key, a short press on that
key still navigates — on release —, and a press held up to the threshold closes the window without
navigating: on the HVAC pop-up, closing changes neither the temperature nor the fan speed.

The **profile picker opens on the active profile**: the joystick starts from its card, and the row
scrolls to show it when it is beyond the fifth profile. In a Bluetooth conflict, it starts from the
first card, the one the delay applies.

**Inside the app**:
- **confirmations** all look alike — an icon, a title, a text, one or two buttons — and their
  colour tells the nature of the action: blue for an ordinary action, orange for a warning, red for
  whatever deletes or powers off;
- an **app** or a **profile** is picked from a scrolling grid of tiles;
- an **advanced shortcut** is edited in two side-by-side lists;
- the **times** and the **tariff** of a charge (Statistics tab), like the **price** of the charge in
  progress (charging pop-up), are set with − and + buttons, without a keyboard — the car's one covers half the screen. Minutes go in steps of five, the
  tariff step is selectable, and holding a button repeats the step.

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
| Volume drop when leaving the car | |
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
An update is no longer found only by opening MG4Control: a banner appears **at the bottom of the
screen, over the infotainment**, like the profile confirmation one, showing the installed version
against the new one. Four choices:

| Button | Effect |
|---|---|
| **Install update** | Opens MG4Control on the usual update dialog, without repeating the network request |
| **Skip this version** | That version is no longer offered, here or at app launch |
| **Later** | Closes the banner: nothing is remembered, the alert will come back |
| **Stop telling me** | Turns this banner off. The dialog at MG4Control launch stays |

With no answer, the banner closes by itself after the pop-up delay, which means "later". It neither
dims nor blocks anything: the rest of the screen stays usable.

Four safeguards, because nobody triggers this path:
- the check runs **20 s after ignition** — at the moment itself the car's data link is not up yet;
- **six hours minimum** between network requests, the car being on a data plan;
- **nothing shows while driving** (same lock as the profile picker);
- a version already offered is not offered again in the same session — and it is only marked as
  offered if the banner **actually appeared**.

The long-standing "check for updates at launch" switch also turns this banner off: it is a setting
users read as global, and it is.

### Profiles
- Profile list with apply, set as default, edit and delete
- **Profile order**: a long press on a card in the list lifts it; drag it into place (the list
  scrolls by itself near its edges, a banner shows the position) and the order is saved on release.
  This order is the picker's, the "Profile cycle" page buttons' and the default cycle's; in a
  Bluetooth conflict it is the priority — the first of the conflicting profiles is applied when the
  delay runs out
- **Full-screen editor** organised into three categories: Driving, Safety, Comfort
- **One switch per category**, at the top of each tab: switched off, the tab folds away and the
  profile writes nothing there — a profile can cover safety only, or comfort only. At least one is
  needed to save; existing profiles keep their three categories on
- The profile name and the "default profile" setting stay visible across the three tabs
- **Regeneration unchanged**: a switch on the Regeneration section of the Driving tab; when off,
  the profile touches neither the regeneration level nor one-pedal driving, the level buttons grey
  out, and both the list and the picker show it ("Regen unchanged"). Existing profiles keep their
  regeneration
- Heated steering wheel and seats have an **apply** switch: unchecked, the profile leaves the
  setting alone instead of turning it off
- **Automatic high beam**: same switch, unchecked by default — existing profiles leave it alone
- **Air conditioning**: an optional block in the Comfort tab, unchecked by default, carrying the
  Dashboard's own controls — on/off, A/C, auto fan, setpoint, fan speed, recirculation, defrosters

#### The profile's climate block
Unchecked, the profile does not touch the climate: that is the long-standing behaviour, and the
one every stored profile keeps. Checked, it applies what it carries at startup, at ignition, and
on every manual application.

Three rules apply, and they come from the vehicle rather than the interface:

| Rule | Consequence |
|---|---|
| Climate off | Nothing else is written — a setpoint sent to a stopped climate turns it back on with some firmwares |
| AUTO mode | The fan speed is not applied: setting it would leave auto mode |
| "Unchanged" | Recirculation and defrosters are left alone — without that value, a profile that says nothing about them would turn them off |

The sliders are bounded for input (15–33 °C, 1–11), but the **real** limits are read from the
vehicle when applying and the setpoint is clamped to them: they differ between A9 and the other
firmwares.

> [!NOTE]
> **The profile wins over the A/C automation.** If the profile applied since the app started
> carries a climate block, the temperature automation does nothing and logs it. Without this rule
> both would write over each other at ignition, in an order nothing guarantees. At start-up, when a
> saved profile sets the climate, the automation waits for the start-up profile to be applied —
> about ten seconds at most — before deciding: the previous trip's profile does not count.

### Unknown firmware (UNKNOWN)
- Warning window at startup when the firmware is not recognised, showing the version read from the
  car
- The user can close the app or continue
- "Continue" opens *Settings → Info* directly, where the firmware chips are tappable to force a
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

The driver display module is limited to SWI133, SWI68 and SWI165 (see
[Driver display](#driver-display)). The charging pop-up works everywhere; on the A9 firmwares, its
charge limit can only be set when the battery allows it (not with LFP — see
[Charging pop-up](#charging-pop-up)).

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
│   │   │   ├── StatsTracker.kt        # Statistics: trips and charges (pure state machine, tested)
│   │   │   ├── StatsSummary.kt        # Statistics: aggregates of a period (cost, consumption)
│   │   │   ├── PowerIntegrator.kt     # Energy from voltage × current (firmwares without counters)
│   │   │   ├── ClusterGauge.kt        # Driver display: gauge scales, square placement, trip average
│   │   │   ├── ClusterTab.kt          # Driver display: the cluster's tabs
│   │   │   ├── ChargeModule.kt        # Cluster charge module: target, time left, energy
│   │   │   ├── ChargePopupModel.kt    # Charging pop-up: limit, curve scale, charge log
│   │   │   ├── ChargeEnergyLearning.kt # Charge energy: table learnt per 1% band, source kept
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
│   │   │   ├── StatsCollector.kt      # Statistics readings (only when turned on)
│   │   │   ├── ClusterTabReader.kt    # Tab shown on the cluster
│   │   │   ├── BatteryTypeConfig.kt   # Battery type (A9): charge limit adjustable or not
│   │   │   ├── ChargeProbe.kt         # Charging diagnostic probe (MG4_CHARGE log)
│   │   │   └── VehicleWriteGate.kt    # Speed lock, enforced inside the write primitives
│   │   │
│   │   ├── stats/
│   │   │   └── StatsStore.kt          # Statistics history: file, purged on read
│   │   │
│   │   ├── accessibility/
│   │   │   ├── KeyCaptureService.kt   # Accessibility service — sees keys before the launcher
│   │   │   ├── AccessibilityServiceList.kt # Is the service enabled? (long and short name forms)
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
│   │   │   ├── StatsFragment.kt       # Statistics (3-tab rail)
│   │   │   ├── ProfileAdapter.kt      # Profile RecyclerView adapter
│   │   │   ├── AppDialog.kt           # App windows: confirmation, choice grid, lists, − / + buttons
│   │   │   ├── RestoreProfilesDialog.kt # "Restore profiles?" — lists the profiles found
│   │   │   ├── StatsDialogs.kt        # Complete a charge, fix a tariff (no keyboard)
│   │   │   ├── ClusterSettingsPreview.kt # Driver display preview in the settings (simulated trip)
│   │   │   ├── ClusterTabDialog.kt    # Diagnostic: tab shown on the cluster
│   │   │   ├── widget/ClusterGaugeView.kt # Driver display module (ring, arc or discreet bar; charge)
│   │   │   ├── widget/ChargeCurveView.kt # Charging pop-up curve
│   │   │   ├── widget/ChargeLimitSliderView.kt # Battery bar, charge-limit slider
│   │   │   ├── ConsoleFragment.kt     # Real-time debug log viewer
│   │   │   ├── DriveRegenFragment.kt  # Legacy (unused in v2)
│   │   │   ├── ClimateFragment.kt     # Legacy (unused in v2)
│   │   │   └── AdasFragment.kt        # Legacy (unused in v2)
│   │   │
│   │   ├── service/
│   │   │   ├── MG4ControlService.kt   # Foreground service (boot, shortcuts, external API)
│   │   │   ├── OverlayKit.kt          # Base of pop-ups and banners: scale, backgrounds, room on screen
│   │   │   ├── OverlayNavigation.kt   # Right-joystick navigation inside a pop-up
│   │   │   ├── ProfilePickerOverlay.kt# Profile picker (full-frame pop-up)
│   │   │   ├── HvacPopupOverlay.kt    # HVAC pop-up (+ HvacPopupSkin.kt, its painting)
│   │   │   ├── ProfileConfirmOverlay.kt # YES/NO banner for automations
│   │   │   ├── VehiclePowerOffOverlay.kt # Power-off confirmation banner
│   │   │   ├── UpdateOverlay.kt      # "Update available" banner on the vehicle screen
│   │   │   ├── ChargeMonitor.kt       # Background charge tracking (pop-up and statistics)
│   │   │   ├── ChargePopupOverlay.kt  # Charging pop-up (full frame)
│   │   │   └── ClusterModuleOverlay.kt # Module window on the cluster display, readings and tab
│   │   │
│   │   ├── receiver/
│   │   │   └── BootReceiver.kt        # System boot receiver
│   │   │
│   │   ├── util/
│   │   │   ├── FirmwareInfo.kt        # Firmware detection + forced mode
│   │   │   ├── ClusterModuleSettings.kt # Driver display: on/off, module per tab, style, badge, units, refresh, animation
│   │   │   ├── ChargePopupSettings.kt # Charging pop-up: on or off
│   │   │   ├── GarageMode.kt          # Garage mode — puts every automatism to sleep
│   │   │   ├── FirmwareHelper.kt      # Full firmware version string reader (async)
│   │   │   ├── LocaleHelper.kt        # Language management (7 languages) and text size
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
    val regenEnabled: Boolean?, // Regeneration: null or true = apply, false = unchanged
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
    val autoHighBeam: Boolean?,       // Automatic high beam; null = the profile leaves it alone
    val btDeviceMac: String?          // Paired Bluetooth device
)
```

> **Why these fields are nullable:** Gson does not call the Kotlin constructor. A declared default
> of `= true` would therefore **not** apply to already-saved profiles — the field is missing from
> the JSON, the JVM writes `false`, and the ESC would end up disabled on every old profile. `null`
> means "profile older than this feature", and the accessors decide what to do.

### Persistence

Profiles are serialized to JSON via **Gson** and stored in `SharedPreferences`. Maximum **10 profiles** per device
(`ProfileManager.MAX_PROFILES`). Beyond five, the picker scrolls its cards and the "Profile cycle" page moves to two
rows of five. A version older than 2.6.9 reads and applies all ten, but creates no more and only restores five from
the vehicle backup.

### Applying a Profile

`ProfileApplier.apply()` executes hardware calls in the following order on `Dispatchers.IO`:
1. Drive mode (fast — binder call)
2. Regen level (fast — binder call)
3. Heated steering wheel (~2 s — state confirmation polling)
4. Left seat heating (~7 s — toggle polling)
5. Right seat heating (~7 s — toggle polling)
6. Wait for Katman4, then firmware ADAS, forward collision, ESC and drowsiness, lane keeping,
   automatic high beam

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
    ├──► AutomationFragment
    ├──► ShortcutsFragment
    ├──► StatsFragment
    └──► SettingsFragment
```

The top bar shows the tabs in this order: Profiles, Automation, Shortcuts, Statistics, Settings.
Its buttons act as toggles: a second press returns to the dashboard.

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

### Windows and pop-ups
- **App windows** — `ui/AppDialog`: a rounded card with no chrome, at the width it is given (an
  Android dialog's default width is too narrow for a car screen). `confirmer` for a simple
  confirmation, with a neutral, warning or danger tone; `choisir` for a grid of tiles; `options`
  for a single-choice list; `segments` for a segmented choice; `repeter` for a − or + button that
  repeats while held. Every new yes / no in the app goes through `AppDialog.confirmer`.
- **Pop-ups and banners** — `service/OverlayKit`: their layouts are written for a base card
  (1108 × 392 dp) and inflated with a corrected density, to take the same share of the screen
  everywhere. If the window granted is smaller than expected, the card is shrunk as a whole and the
  `MG4_OVERLAY` log gives the room found. Tints are set in code: these layouts are inflated outside
  the activity, without the AppCompat factory.

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

Weather data for automatic brightness: [Open-Meteo.com](https://open-meteo.com), licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

---

## Credits

Made with ❤ by **SliDeeN** and **Claude IA**

Basé sur l'application **DriveHub Dort** développée par **Merth4n** & **hotboy_ist**

Merci à **confor1max**, **FrAsErTaG**, **sixty4h**, **hojnikb** et **depippi.p** pour les tests avant chaque release 🙏

Le pilotage des vitres doit beaucoup à **[winclose](https://github.com/Skittle6938/winclose)** de
**Skittle6938** : identifiants et valeurs de commande des vitres, et principe de la fermeture
automatique en quittant la voiture, repris puis étendus aux six firmwares. Merci 🙏

[![GitHub](https://img.shields.io/badge/GitHub-SliDeeN%2FMG4Control-181717?logo=github)](https://github.com/SliDeeN/MG4Control)

</details>
