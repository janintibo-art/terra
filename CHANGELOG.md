# Journal des versions

## v0.5.0 — Socle du rendu à niveaux de détail

Aucun changement visible. Ce lot pose les fondations qui rendront possible la
descente continue de l'orbite jusqu'au sol, et donc la végétation et les
créatures. Tout est en Kotlin pur et testé en intégration continue ; le moteur
de rendu n'est pas touché, il ne peut donc rien casser.

### Ce que la simulation a établi avant écriture du code

| Altitude caméra | Tuiles | Triangles | Arête au sol |
|---|---|---|---|
| 10 000 km | 9 | 18 k | 156 km |
| 100 km | 198 | 405 k | 1,2 km |
| 1 km | 381 | 780 k | 9,5 m |
| 2 m | 633 | 1,3 M | 4 cm |

La charge est **bornée** : descendre de l'orbite au ras du sol la multiplie par
70, non par un quadrillion comme le ferait une subdivision uniforme. Avec des
tuiles de 17×17, le pire cas tombe à 324 000 triangles et 7 Mo.

### Composants

- **`CubeSphere`** : projection des six faces d'un cube sur la sphère, avec
  déformation tangente. Ramène le rapport de surface entre cellules du centre et
  des coins de 4,74 à 1,37 — mesuré, pas estimé. L'icosphère reste le support de
  la simulation ; le cube-sphère devient celui de la géométrie affichée.
- **`TileId`** : adressage quadtree (face, niveau, x, y), géométrie, élimination
  par l'horizon, critère de subdivision et sélection descendante.
- **`ElevationField`** : le champ d'élévation devient une fonction pure de la
  position, évaluable à toute résolution.
- **`TerrainProfile`** : rassemble les constantes issues des traitements globaux
  (niveau de la mer, amplitudes, relief). **Garantit que le terrain fin et la
  grille simulée sont la même fonction**, pas deux approximations voisines — ce
  qui élimine coutures et sauts visuels entre niveaux de détail par construction
  plutôt que par correction. Un test le vérifie sommet par sommet.
- **`CoarseSampler`** : retrouve la cellule simulée d'un point quelconque par
  descente sur le graphe d'adjacence, en une trentaine d'étapes au lieu de
  10 242 comparaisons. Vérifié contre la recherche exhaustive.

### Ce que ce lot ne fait pas encore

Le maillage des tuiles, la gestion de l'arbre en mémoire, les coordonnées
relatives à la caméra et la caméra libre. Le flottant 32 bits ne représente que
50 cm près du centre de la planète, contre un centième de millimètre à cent
mètres de la caméra : les coordonnées relatives sont donc obligatoires, et
constituent le prochain lot.

## v0.4.0 — Climat crédible et rendu maîtrisé

Le premier APK fonctionnel a révélé une planète couverte à 30 % de glace, contre
7 % sur Terre. Ce lot corrige la cause et instrumente la vérification.

### Le défaut principal

Le profil thermique employait `T = 28 − 62·sin²(latitude)`, qui donne **−3 °C à
45°** alors que la moyenne annuelle terrestre y est de **+12 °C**. Conséquence :
toute mer au-delà de 44° gelait, toute terre au-delà de 50° devenait glacier.

Le nouveau profil mélange `sin²` et `sin⁴`, ce qui colle de près aux
observations :

| Latitude | Modèle | Terre |
|---|---|---|
| 0° | 27 °C | 26 °C |
| 30° | 21 °C | 20 °C |
| 45° | 12 °C | 12 °C |
| 60° | −1 °C | 0 °C |
| 90° | −20 °C | −20 °C |

### Autres corrections climatiques

- **Inertie thermique océanique** : la mer restitue en hiver la chaleur de l'été,
  ce qui adoucit les hautes latitudes maritimes et repousse la banquise.
- **Continentalité** : un parcours en largeur depuis le littoral donne
  l'éloignement de chaque cellule à la mer. L'intérieur des continents devient
  plus froid aux hautes latitudes et surtout plus sec — c'est ce qui creuse les
  déserts continentaux, du Gobi au Taklamakan.

### Rendu

- **Compression des hautes lumières** : neige et banquise saturaient dès qu'elles
  étaient éclairées, transformant la calotte en aplat blanc sans relief. Un genou
  doux ne comprime que ce qui dépasse et préserve le détail des facettes.
- **Reflet solaire resserré** : exposant porté de 70 à 160 et intensité réduite.
  Un glint réel est petit et net, pas une tache couvrant un quart du globe.

### Variété et lisibilité

- **Relief propre à chaque monde** : le calibrage par percentile faisait que
  toutes les planètes atteignaient exactement +7000 m. Chaque monde tire
  désormais son amplitude et son caractère — pénéplaine érodée ou massif
  tourmenté.
- **Lacs distingués des mers intérieures** : « 19 mers intérieures » comptait
  surtout des mares de deux cellules.
- **HUD compact** : les dernières lignes passaient sous la barre de boutons. Un
  indicateur permanent de couverture glaciaire est ajouté.

### Tests

Sept tests climatiques nouveaux, dont la comparaison du profil thermique aux
moyennes annuelles observées à cinq latitudes, une borne sur la couverture
glaciaire, et la vérification que les tropiques ne gèlent jamais.

`GENERATION_VERSION` passe à 2 : les mondes de la v0.3 seront signalés comme
antérieurs.

## v0.3.1 — Correctifs de compilation

Le premier passage de la CI a révélé une erreur qui bloquait la compilation
depuis la v0.2.0 : aucun test n'avait donc jamais pu s'exécuter.

- **`Rng` : conflit de surcharge.** Le constructeur public `(graine, séquence)`
  et le constructeur privé `(état, inc)` ont la même signature une fois
  compilés — deux `Long`. Le second est remplacé par `Rng.fromState()`.
- `.max()` / `.min()` sur tableaux remplacés par `maxOrNull()` / `minOrNull()`.
- `out` n'est plus utilisé comme identifiant dans `WorldSave`.
- `:sim` expose `:core` en `api` et non `implementation` : `PlanetData` publie
  des types de `:core` dans sa signature.
- Deux tests mal calibrés corrigés : longueur maximale des noms générés, et
  comparaison stricte du rayon de rendu qu'un flottant 32 bits ne peut honorer
  pour des altitudes millimétriques.

## v0.3.0 — Consolidation

Lot sans nouveauté visuelle spectaculaire, consacré aux instruments qui
permettront de juger objectivement la tectonique du lot 1.4.

### Ajouts

- **Persistance** (lot 0.8) : le monde survit à la fermeture. On sauvegarde le
  nom, les paramètres et le tick — soit moins de 200 octets — plutôt que
  plusieurs mégaoctets de géométrie régénérable.
- **Nommage des mondes** : le nom *est* la graine. « Kaleth » reconstruit
  toujours la même planète, sur n'importe quel appareil. Saisie et génération
  aléatoire par le bouton « Monde » ou un appui long.
- **Cinq calques de données** : biomes, relief, température, précipitations,
  zones climatiques. Permet de juger le climat sur les grandeurs réelles au lieu
  de le deviner à travers les couleurs de biome.
- **Analyse géographique** : continents, îles, mers intérieures, longueur de
  littoral, fragmentation, altitude moyenne, part de montagnes.
- **Temps planétaire** (lot 0.5 branché) : jours, années, saisons, déclinaison
  solaire dérivée de l'inclinaison de l'axe. Contrôles pause, ×1, ×20, ×200.
- **Test d'empreinte** : chaque monde de référence a une signature 64 bits. Toute
  dérive involontaire de la génération fera désormais échouer la CI.
- **Anti-aliasing** 4× avec repli automatique sur 2× puis aucun.
- **Inertie de rotation** après un glissement.

### Corrections

- **Éclairage lié à la caméra.** Faire pivoter la vue faisait tourner le modèle,
  donc déplaçait la nuit avec le regard. La matrice modèle ne porte plus que la
  rotation propre de la planète ; la caméra orbite dans la matrice de vue.
- **Échecs GPU silencieux.** Une erreur de shader donnait un écran noir sans
  explication ; elle est maintenant affichée dans le HUD.
- **Sauvegarde atomique** : écriture dans un fichier temporaire puis renommage,
  pour qu'une interruption ne laisse pas de sauvegarde corrompue.

## v0.2.0 — Socle technique et monde statique

- Découpage en modules `:core`, `:sim`, `:app` ; la simulation ne dépend plus
  d'Android et devient testable en intégration continue.
- Graine hiérarchique : ajouter un système ne modifie plus les mondes existants.
- Générateur PCG32 déterministe, sérialisable, à flux indépendants.
- Bruit de Perlin 3D, icosphère géodésique, biomes de Whittaker en unités
  physiques réelles.
- Niveau de la mer calibré par percentile : correction du défaut de continent
  unique géant observé en v0.1.
- Une soixantaine de tests unitaires exécutés à chaque push.

## v0.1.0 — Prototype

- Planète low-poly par bruit de valeur, rendu OpenGL ES 2, compilation par
  GitHub Actions.
