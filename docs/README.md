# Site MG4Control (GitHub Pages)

Site statique de présentation de [MG4Control](https://github.com/SliDeeN/MG4Control), bilingue FR/EN,
construit autour d'une maquette interactive de l'application (écran 1280 × 480) et d'un « véhicule
virtuel ». La maquette reste collée en haut de l'écran ; cliquer une fonctionnalité dans la liste
l'amène au bon écran et surligne l'élément concerné.

Aucun outil de build : du HTML, du CSS et du JavaScript servis tels quels.

## Structure

```
index.html              Page unique (contenu FR + EN, bascule par data-lang)
assets/css/site.css     Mise en page commune (thème clair/sombre, cartes, widgets)
assets/css/explorer.css Explorateur : maquette collée, barre de thèmes, liste des fonctionnalités
assets/css/sim.css      Maquette de l'app : palette et dimensions de res/values*/colors.xml
assets/js/strings.js    Libellés de l'app en 7 langues, générés depuis res/values*/strings.xml
assets/js/sim.js        Simulateur : écrans, règles par firmware, raccourcis, automatisations, API
assets/js/site.js       Langue, thème, liens fonctionnalités → maquette, widgets
assets/js/explorer.js   Maquette épinglée, barre de thèmes, filtre 2.6.7, retour aux explications
assets/img/             Icônes (dérivées de ic_launcher-playstore.png)
tools/strings2js.ps1    Régénère strings.js depuis les ressources de l'app
.nojekyll               Désactive Jekyll sur GitHub Pages
```

## Publication (GitHub Pages)

Ce dossier `docs/` est servi par GitHub Pages depuis la branche `main`
(**Settings → Pages** : Deploy from a branch, `main`, `/docs`). Toute modification poussée sur
`main` est en ligne une à deux minutes plus tard à l'adresse `https://slideen.github.io/MG4Control/`
(suivi dans l'onglet **Actions**, « pages build and deployment »).

Le dossier `docs/` n'est pas lu par Gradle : il n'a aucun effet sur la compilation de l'APK.

## Tester en local

N'importe quel serveur statique convient, par exemple :

```bash
python -m http.server 8000
```

depuis le dossier `docs/`, puis ouvrir `http://localhost:8000`. (Ouvrir `index.html` directement en
`file://` fonctionne aussi dans la plupart des navigateurs.)

## Version documentée

Le site et le simulateur décrivent la **2.6.7** (code de la branche `beta` au 30/09/2026, commit
`4127f65`, refonte de l'écran des vitres). Ils sont prévus pour être publiés en même temps que la release 2.6.7 : le bouton
« Télécharger la dernière version » pointe vers `releases/latest`.

Les nouveautés portent le badge `<span class="badge-new">` (« Nouveau · 2.6.7 ») ; le filtre
« ★ 2.6.7 » et l'encadré de l'accueil s'appuient dessus. À la version suivante, retirer ces badges
et réécrire l'encadré des nouveautés.

## Ajouter ou modifier une fonctionnalité

Chaque fonctionnalité est une carte `.pt` dans un thème `<section class="grp">` de `index.html` :

- `data-hl-key` : l'élément de la maquette à surligner (attribut `data-hl` dans `sim.js`) ;
- `data-go="écran:onglet"` ou `data-demo="nom"` : ce que la maquette affiche au clic ;
- un résumé d'une ou deux phrases, puis le détail dans `<details class="more">`.

Un nouveau thème doit aussi avoir sa puce dans la barre `.x-chips` (`data-grp` = id du thème).

## Mettre à jour les libellés de l'app

`assets/js/strings.js` est une copie des `strings.xml` de l'application (7 langues). Après une
modification des traductions, le régénérer depuis la racine du dépôt :

```powershell
powershell -ExecutionPolicy Bypass -File docs/tools/strings2js.ps1 -Res app/src/main/res -Out docs/assets/js/strings.js
```

## Quand l'application évolue

Les règles par firmware sont regroupées dans la fonction `caps()` de `sim.js`, et la liste des
actions de raccourci dans `availableActions()`. Ce sont les deux endroits à relire lorsqu'une
fonctionnalité change.
