# Site MG4Control (GitHub Pages)

Site statique de présentation de [MG4Control](https://github.com/SliDeeN/MG4Control), en français et
en anglais, construit autour d'une maquette interactive de l'application (écran 1280 × 480) et d'un
« véhicule virtuel ». La maquette reste collée en haut de l'écran ; cliquer une fonctionnalité dans la
liste l'amène au bon écran et surligne l'élément concerné.

Chaque langue a sa page : `https://slideen.github.io/MG4Control/` (français) et
`https://slideen.github.io/MG4Control/en/` (anglais). Les deux sont **générées** depuis une seule
source bilingue, `site-src/index.src.html`, par un script PowerShell (fourni avec Windows, aucune
dépendance). Le CSS et le JavaScript sont servis tels quels.

> **Ne pas modifier `index.html` ni `en/index.html` à la main** : ils sont réécrits à chaque
> génération. Le contenu se modifie dans `site-src/index.src.html`.

## Structure

```
../site-src/index.src.html  SOURCE des deux pages (chaque texte en double : data-l="fr" et data-l="en")
../site-src/build.ps1       Génère index.html, en/index.html, sitemap.xml (et les images d'aperçu)
../site-src/og-card.html    Modèle de l'image d'aperçu des liens partagés (1200 × 630)

index.html              Page française (générée)
en/index.html           Page anglaise (générée)
sitemap.xml             Plan du site pour les moteurs de recherche (généré)
assets/css/site.css     Mise en page commune (thème clair/sombre, cartes, widgets)
assets/css/explorer.css Explorateur : maquette collée, barre de thèmes, liste des fonctionnalités
assets/css/sim.css      Maquette de l'app : palette et dimensions de res/values*/colors.xml
assets/js/strings.js    Libellés de l'app en 7 langues, générés depuis res/values*/strings.xml
assets/js/sim.js        Simulateur : écrans, règles par firmware, raccourcis, automatisations, API
assets/js/site.js       Lien de langue, thème, liens fonctionnalités → maquette, widgets
assets/js/explorer.js   Maquette épinglée, barre de thèmes, filtre 2.6.7, retour aux explications
assets/img/             Icônes (dérivées de ic_launcher-playstore.png) et images d'aperçu og-fr / og-en
tools/strings2js.ps1    Régénère strings.js depuis les ressources de l'app
.nojekyll               Désactive Jekyll sur GitHub Pages
```

`site-src/` est à la racine du dépôt, hors de `docs/`, pour ne pas être publié : la source contient
les deux langues et ferait doublon avec les pages.

## Générer les pages

Depuis la racine du dépôt :

```powershell
powershell -ExecutionPolicy Bypass -File site-src/build.ps1
```

À relancer après **toute** modification de `site-src/index.src.html`, d'un fichier CSS ou d'un
fichier JavaScript. Le script :

- ne garde, dans chaque page, que les textes de sa langue (`data-l`) et les libellés d'accessibilité
  correspondants (`data-aria-fr` / `data-aria-en` → `aria-label`) ;
- écrit le titre et la description de la page, lus dans la source (`data-title-*` sur `<html>`,
  `data-fr` / `data-en` sur la balise `description`) ;
- remplace `<!-- @seo -->` par le bloc de référencement : adresse canonique, langues alternatives
  (`hreflang`), Open Graph et Twitter (aperçu des liens partagés), données structurées
  `SoftwareApplication` — la version y est celle de la pastille `vX.Y.Z` de l'accueil ;
- ajoute `?v=<empreinte>` aux fichiers CSS et JavaScript, pour qu'un navigateur ne garde pas une
  ancienne copie en cache à côté d'une page plus récente ;
- s'arrête avec un message si la source ne respecte pas ses règles (voir ci-dessous).

Images d'aperçu (`assets/img/og-fr.png`, `og-en.png`), à refaire quand l'écran d'accueil de la
maquette change visiblement — il faut Edge ou Chrome sur la machine :

```powershell
powershell -ExecutionPolicy Bypass -File site-src/build.ps1 -Cards
```

### Règles de la source

- Un texte traduit s'écrit en deux éléments voisins, `data-l="fr"` puis `data-l="en"`, sur une balise
  `span`, `p`, `td`, `ul` ou `ol`. Un tel élément ne doit pas en contenir un autre du même nom
  (pas de `<span>` dans un `<span data-l>`).
- Les liens vers les fichiers du site s'écrivent `assets/…` : le script ajoute `../` pour la page
  anglaise.
- `build.ps1` reste en ASCII pur (PowerShell 5.1 lit les scripts sans BOM en ANSI) : aucun texte
  affiché n'y figure, tout est lu dans la source.

## Langue

Le bouton FR / EN est un lien vers l'autre page ; le choix est mémorisé (`localStorage`,
`mg4site.lang`) et l'ancre conservée. Un visiteur qui a choisi une langue y est renvoyé d'office, de
même qu'un ancien lien `?lang=fr|en`.

Il n'y a volontairement **aucune redirection d'après la langue du navigateur** : elle emporterait
aussi les robots d'indexation, qui s'annoncent en anglais, et la page française ne serait plus lue.
À la place, un bandeau propose l'autre page au visiteur dont la langue ne correspond pas, tant qu'il
n'a rien choisi.

## Référencement

- `sitemap.xml` liste les deux pages ; il est à déclarer une fois dans Google Search Console
  (propriété « Préfixe de l'URL » `https://slideen.github.io/MG4Control/`).
- Pas de `robots.txt` : il ne compte qu'à la racine de l'hôte (`slideen.github.io/`), hors de portée
  d'un site de projet. Sans lui, tout est autorisé.
- Tester l'aperçu d'un lien partagé : coller l'adresse dans Discord, WhatsApp ou
  [opengraph.xyz](https://www.opengraph.xyz/).

## Publication (GitHub Pages)

Ce dossier `docs/` est servi par GitHub Pages depuis la branche `main`
(**Settings → Pages** : Deploy from a branch, `main`, `/docs`). Toute modification poussée sur
`main` est en ligne une à deux minutes plus tard à l'adresse `https://slideen.github.io/MG4Control/`
(suivi dans l'onglet **Actions**, « pages build and deployment »).

Les pages générées sont versionnées avec leur source : pousser `site-src/` **et** `docs/`.

Le dossier `docs/` n'est pas lu par Gradle : il n'a aucun effet sur la compilation de l'APK.

## Tester en local

N'importe quel serveur statique convient, par exemple :

```bash
python -m http.server 8000
```

depuis le dossier `docs/`, puis ouvrir `http://localhost:8000` (page anglaise :
`http://localhost:8000/en/`). Un serveur est nécessaire : ouvert directement en `file://`, le lien de
langue mène à un dossier et non à sa page.

## Version documentée

Le site et le simulateur décrivent la **2.6.7** (code de la branche `beta` au 30/09/2026, commit
`4127f65`, refonte de l'écran des vitres). Ils sont prévus pour être publiés en même temps que la release 2.6.7 : le bouton
« Télécharger la dernière version » pointe vers `releases/latest`.

Les nouveautés portent le badge `<span class="badge-new">` (« Nouveau · 2.6.7 ») ; le filtre
« ★ 2.6.7 » et l'encadré de l'accueil s'appuient dessus. À la version suivante, retirer ces badges
et réécrire l'encadré des nouveautés.

## Ajouter ou modifier une fonctionnalité

Chaque fonctionnalité est une carte `.pt` dans un thème `<section class="grp">` de
`site-src/index.src.html` :

- `data-hl-key` : l'élément de la maquette à surligner (attribut `data-hl` dans `sim.js`) ;
- `data-go="écran:onglet"` ou `data-demo="nom"` : ce que la maquette affiche au clic ;
- un résumé d'une ou deux phrases, puis le détail dans `<details class="more">`.

Un nouveau thème doit aussi avoir sa puce dans la barre `.x-chips` (`data-grp` = id du thème).
Régénérer ensuite les pages.

## Mettre à jour les libellés de l'app

`assets/js/strings.js` est une copie des `strings.xml` de l'application (7 langues). Après une
modification des traductions, le régénérer depuis la racine du dépôt, puis régénérer les pages :

```powershell
powershell -ExecutionPolicy Bypass -File docs/tools/strings2js.ps1 -Res app/src/main/res -Out docs/assets/js/strings.js
powershell -ExecutionPolicy Bypass -File site-src/build.ps1
```

## Quand l'application évolue

Les règles par firmware sont regroupées dans la fonction `caps()` de `sim.js`, et la liste des
actions de raccourci dans `availableActions()`. Ce sont les deux endroits à relire lorsqu'une
fonctionnalité change.
