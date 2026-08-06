# Journal des versions

## v0.8.6 — Correctif : carrés blancs en avançant au sol

Signalé à l'essai : de près, en se déplaçant, des carrés clairs. Diagnostic :
des **trous de couverture** — une tuile fine pas encore maillée dont tous les
ancêtres proches ont été évincés du cache laisse voir le fond de brume,
découpé en silhouette de tuile. Le scénario : à l'arrêt, les feuilles fines
finissent toutes prêtes, leurs parents ne servent plus de repli et l'éviction
les rend au pool ; au premier déplacement, les nouvelles feuilles manquent et
le repli remonte vers... du vide.

Deux correctifs à la cause :

- **Les ancêtres d'une tuile sélectionnée ne sont plus évincés** tant qu'elle
  l'est : le filet du repli reste tendu pendant l'exercice (ravivage sur clés
  compactées, sans allocation, juste avant chaque passe d'éviction).
- **Le parent d'une feuille manquante est demandé en priorité** : il couvre
  quatre feuilles pour le même coût de maillage — le trou se comble quatre
  fois plus vite et le repli retrouve un échelon proche.

L'arithmétique de parenté déménage de `:app` vers `:sim` (`TileId.parentKey`)
où elle est enfin testée en CI face à la version objet — elle vivait hors de
portée des tests, et une divergence aurait recréé les trous en silence. Deux
tests ajoutés.

## v0.8.5 — Correctif de CI : le vieux verrou contre le nouveau

La CI de la v0.8.4 a rougi sur un **test préexistant devenu obsolète** — ni
erreur de compilation, ni vrai bug : `CameraTest` verrouillait depuis le lot
2-A la convention théorique du glissement vertical, celle-là même que l'essai
sur appareil a démentie. Le correctif v0.8.4 et ce vieux verrou se
contredisaient ; c'est le verrou qui avait tort.

Leçon appliquée plutôt que rustinée : les signes de gestes n'ont plus qu'**un
seul gardien** (`GesturesTest`, fixé par la mesure sur appareil). Les doublons
de `CameraTest` sont retirés avec un renvoi explicite — deux verrous sur la
même convention finissent toujours par se désynchroniser, la preuve. Une
variable morte signalée par le compilateur est aussi nettoyée.

## v0.8.4 — Correctif : glissement vertical inversé en mode sol

Signalé à l'essai : gauche-droite correct, haut-bas à rebours. La cause est
un signe manquant sur la composante verticale de `pan` — la première version
inversait l'axe horizontal (« le terrain suit le doigt ») mais pas le
vertical, et l'enchaînement cap → repère caméra → axes écran rend ce genre de
signe indécidable sur le papier : seul un doigt sur l'écran tranche.

La convention mesurée est désormais **figée par trois tests** (signe de
chaque axe, aller-retour en carré) : si un refactor retourne un signe, la CI
rougit au lieu que le bug ressurgisse à l'écran.

Au passage, la capture d'essai a validé visuellement le lot 1.5 : frontières
continues autour des plaques, trois types présents et mélangés.

## v0.8.3 — Lot 1.5 : les frontières de plaques classées

Chaque arête de la grille séparant deux plaques est désormais classée
**convergente**, **divergente** ou **transformante**, d'après la vitesse
relative des deux plaques en ce point — décomposée le long de la direction de
séparation, avec l'intensité (‖vr‖) conservée : c'est elle qui fera l'ampleur
du relief au lot 1.6.

### Un critère mesuré, pas supposé

Le critère naïf (« composante dominante », seuil 45°) classe la moitié des
frontières en transformantes, contre ~20 % sur Terre : nos frontières de
Voronoï sont orientées au hasard, quand les vraies s'alignent sur le
mouvement. Le seuil retenu (30°, composante normale > ‖vr‖/2) rend 33/33/34
sur rotations uniformes — validé par simulation avant écriture, et un test
borne les proportions par monde à trois écarts-types.

### Visible dès cet APK

Sur le calque **Plaques**, les frontières priment sur la teinte : **rouge**
pour la convergence (futures chaînes, fosses, arcs), **turquoise** pour la
divergence (futures dorsales et rifts), **jaune** pour le coulissage. Ce que
le lot 1.6 sculptera se lit déjà sur le globe.

- 5 tests ajoutés (arêtes classées exactement une fois, sommets de bord,
  déterminisme, proportions, orthogonalité de la décomposition).
- Mondes existants inchangés au bit près, comme au lot 1.4.

## v0.8.2 — Correctif : les formules du micro-relief supposaient une plage fausse

La CI de la v0.8.1 a rougi sur deux tests — et c'était un **vrai bug**, pas
un test mal calibré. `Noise.fbm` rend une valeur centrée sur zéro (~±0,7),
pas dans [0, 1] : le « −0,5 » des formules enfonçait tout le sol proche
d'environ 3 m, portait l'amplitude réelle au-delà de la constante déclarée —
donc sous-dimensionnait la sphère englobante du lancer de rayon — et tirait
la moucheture vers le sombre. Les tests de borne ont fait exactement leur
travail, avant qu'aucun APK ne soit produit.

- Formules recentrées ; amplitude des collines recalibrée (±3,1 m typiques,
  identiques au calibrage visuel prévu).
- `MICRO_TOTAL_AMPLITUDE_M` désormais calculée avec la borne **sûre** du
  Perlin (±0,9), pas sa plage typique : une borne de collision se dimensionne
  au pire cas garanti. La moucheture est bornée par construction (clamp).
- Les plages réelles de `fbm` et `ridged` sont maintenant documentées dans
  `Noise` — ce piège ne doit plus jamais se tendre.
- Deux avertissements de compilation nettoyés (assignation morte, variable
  inutilisée).
- Décompte réel mesuré par la CI : **173 tests** — les totaux annoncés
  précédemment étaient surestimés ; ce chiffre-ci vient du journal.

## v0.8.1 — Le sol proche existe enfin

Troisième retour d'essai sur le rendu rapproché, et il était fondé : le champ
de détail le plus fin variait sur ~19 km — à hauteur d'œil, le sol était
mathématiquement plat, et aucun éclairage ne peut sauver un plan.

### Micro-relief

Cinq octaves métriques s'ajoutent à la surface **rendue** (collines douces à
~1,5 km, cassures en crêtes jusqu'à 67 m de longueur d'onde), calibrées par
simulation : pente moyenne ~7°, fréquence la plus fine à 0,6 % du bruit de
quantification float32, montée progressive du niveau 12 au niveau 18 dont
chaque cran (±0,62 m) reste très en deçà de la marge des jupes. Le bénéfice
principal est indirect : les facettes du maillage retrouvent des orientations
variées, et c'est l'éclairage qui refait le relief.

Mailleur et lancer de rayon consomment la même fonction
(`renderedAltitudeAt`) : la régression « caméra sous la surface » de la
v0.7.1 est structurellement impossible, et le test de collision le vérifie.

### Moucheture

La teinte du sol varie par plages d'environ 450 m (±8 %) : fini l'aplat de
couleur d'un biome uniforme vu de près.

### Le monde simulé n'a pas bougé

Micro-relief et moucheture vivent sur le flux `terrain/micro` et ne touchent
que le rendu : grille, climat, biomes et empreintes sont bit à bit identiques
— un test le verrouille. `GENERATION_VERSION` passe néanmoins à 3 : l'aspect
des mondes change, et les sauvegardes anciennes s'affichent comme telles.

- 4 tests ajoutés — 203 attendus en CI.

## v0.8.0 — Lot 1.4 : les plaques tectoniques

Retour à la Phase 1, comme prévu par la feuille de route : le monde statique
reprend là où il s'était arrêté. Ce lot pose les fondations — le relief n'en
dérivera qu'au lot 1.6.

### Voronoï sphérique relaxé

15 à 40 plaques par monde, tirées sur la sphère puis relaxées par **une**
itération de Lloyd — chiffre mesuré avant écriture : zéro relaxation laisse
des plaques réduites à une poignée de cellules (artefact de discrétisation),
deux uniformisent des aires que la Terre montre très inégales. Une itération
garde un rapport d'aires de 4 à 5 en éliminant les dégénérées, et une
compaction garantit par construction qu'aucune plaque n'est vide.

### Mouvement par rotation d'Euler

Une plaque rigide sur une sphère ne peut que tourner autour d'un axe passant
par le centre : chaque plaque reçoit son axe et sa vitesse angulaire, 3 à
16 mrad/Ma — l'étalonnage terrestre (2 à 10 cm/an). La vélocité en tout point
est un produit vectoriel, tangence testée. C'est sur ces vecteurs que le lot
1.5 classera les frontières.

### Non-régression, vérifiable

La tectonique tire sa graine de son propre chemin : les mondes existants ne
changent pas d'un bit, `GENERATION_VERSION` reste à 2, et le test d'empreinte
doit rester vert sur les références figées — **d'où l'importance de les
committer avant ce push** (commande fournie précédemment).

### Aussi

- Nouveau calque « Plaques » : teintes espacées par le nombre d'or, familles
  distinctes pour océanique et continentale, assombries sous l'océan actuel.
- 5 tests ajoutés — 199 attendus en CI.

## v0.7.3 — Le temps à l'échelle de l'observateur, l'horizon comme repère

Réponse directe aux retours d'essai : cycle jour/nuit trop rapide au sol, et
un rendu « trop brouillon » pour distinguer le ciel d'un écran vide.

### Dilatation temporelle continue

Un jour en 48 s est un choix contemplatif pensé pour l'orbite ; au sol, c'est
un stroboscope. Même philosophie que le glissement proportionnel : une formule
continue, sans bouton ni bascule. L'écoulement du temps suit
`portée / 2 000 km`, borné à [1/2880, 1] — rien ne change en orbite, un jour
dure ~1 h à 27 km, et au sol le soleil est perçu comme immobile (jour = 38 h
réelles). ❚❚, ×20 et ×200 restent actifs par-dessus. Trois tests.

### Commande `soleil <heure>`

Avance l'horloge — jamais en arrière — jusqu'à l'heure locale voulue au point
visé : `soleil 12` pour tester en pleine lumière. Le sens de défilement de
l'heure est **mesuré** sur l'horloge plutôt que déduit des conventions de
signe (leçon v0.6), et un test vérifie la propriété physique elle-même :
après `soleil 12`, le méridien reçoit l'éclairement maximal de sa journée.

### Ciel calé sur l'horizon géométrique

Le dégradé plaqué sur l'écran est remplacé par un ciel reconstruisant la
direction de visée par pixel : l'horizon apparaît à son élévation exacte
(abaissé de 5° à 27 km d'altitude, affleurant au sol). Sous l'horizon, un fond
de brume qui sert aussi de secours aux tuiles pas encore maillées — un manque
se voit désormais en brume cohérente, plus jamais en trou noir indiscernable
d'une panne.

### Aussi

- Icône de l'application intégrée (cinq densités, manifest).
- 7 tests ajoutés — 194 attendus en CI.

## v0.7.2 — Correctif : écran noir à l'approche du sol

### La cause, démontrée avant correction

Le lancer de rayon évaluait le **champ de base**, mais les tuiles proches
rendent le **champ détaillé** (`detailedAltitudeAt`, jusqu'à ±26 m d'écart).
L'ancrage `snapToTerrain` garantissait donc deux mètres au-dessus d'une
surface qui n'était pas celle affichée : près du sol, l'œil passait sous le
terrain rendu, toutes les faces proches étaient vues de dos et éliminées —
écran noir, corrélé à l'altitude.

`TerrainRaycaster` évalue désormais la surface au niveau de détail maximal,
celle que le mailleur produit. Deux tests ajoutés : égalité exacte des deux
surfaces sur 500 directions (avec garde-fou exigeant des points où le détail
agit vraiment), et ancrage vérifié au-dessus de la surface **rendue** sur 40
poses de caméra au ras du sol. 187 tests attendus en CI.

### Nuit lisible

Second facteur du noir : à ×1, un jour planétaire dure 48 s, et la face
nocturne était éclairée à 1,8 % — indiscernable d'un écran éteint. Clair de
lune implicite au sol (lueur nocturne renforcée en descente uniquement) et
plancher bleu sombre sur le ciel : la nuit se voit comme une nuit, plus comme
une panne. Le bouton ❚❚ fige le soleil pour travailler côté jour.


## v0.7.1 — Lot B : la descente devient visible

Premier rendu à tuiles branché de bout en bout : sélection à chaque image,
maillage en tâche de fond sur le pool à priorité, téléversement budgété via le
pool de tampons, et **coordonnées relatives à la caméra** sur toute la chaîne.
Le globe contemplatif reste le mode par défaut ; un bouton « Sol » bascule.

### Coordonnées relatives (lot 2.6, remonté volontairement)

Les sommets sont stockés en float32 **relatifs au centre de leur tuile**,
calculés en double ; à chaque image, le décalage `centre − œil` est calculé en
double et converti en float au dernier moment. Validé numériquement avant
écriture : 0,64 mm d'erreur au ras du sol, contre 44 cm de tremblement en
coordonnées monde. Un test JVM rejoue la chaîne float32 complète.

### Coïncidence des bords, structurelle

Les sommets sont paramétrés par leurs indices de grille **globaux** en double :
deux tuiles voisines calculent leurs sommets partagés à partir d'opérandes
identiques, donc obtiennent les mêmes bits. Testé bit à bit, entre tuiles de
même niveau et entre niveaux.

### Jupes calculées

`max(arête × 0,005 ; 1,5 m) + 4 m` — 0,5 % vient de l'étude spectrale du fbm
(écart réel mesuré : 0,21 %, marge ×2,5), le terme fixe couvre la variation
d'amplitude du détail haute fréquence entre niveaux. Un test mesure l'écart
réel sur des paires adjacentes de deux mondes et vérifie la couverture.

### Éclairage au sommet, pas au fragment

Sur Mali, `mediump` fragment est un flottant 16 bits qui sature à 65 504 :
normaliser une position planétaire y produirait des infinis. Tout ce qui
manipule des mètres vit dans le vertex shader ; le fragment ne reçoit que des
grandeurs dans [0, 1].

### Aussi

- Repli sur l'ancêtre prêt le plus profond, sans jamais superposer deux tuiles
  (résolution en deux passes sur clés compactées, zéro allocation).
- Ciel d'attente : dégradé piloté par l'altitude et le soleil — la diffusion
  atmosphérique reste au lot 2.10.
- Gestes de descente : glissement proportionnel à la distance, pincement vers
  le point sous les doigts (lancer de rayon), inclinaison au déplacement
  vertical du pincement.
- **Console minimale** (lot 0.7 avancé) : appui long en mode sol —
  `tp lat lon [portée]`, `monde <nom>`, `mode sol|globe`, `aide`. Grammaire
  dans `:sim`, testée en CI, virgule décimale acceptée.
- Brume de distance : repère de profondeur et voile sur la transition de
  niveau de détail, en attendant le morphing du lot 2.4.
- 15 tests ajoutés (185 attendus au total en CI).

### Limites connues, assumées pour ce lot

- Pas de morphing entre niveaux : un ressaut au changement de niveau, adouci
  par la brume (lot 2.4).
- L'océan est plat et facetté au sol : surface dédiée au lot 2.9.
- Le premier survol d'une zone montre brièvement la tuile parente, le temps du
  maillage — comportement voulu (jamais de trou), réglable par le budget.
- Sol nu : la végétation n'existe pas encore, les collines sont lisses de près.


## v0.7.0 — Fondations de performance

Lot préparatoire au rendu à tuiles, issu d'un audit du projet. Rien de visible :
tout sert à ce que le lot suivant soit un assemblage de pièces éprouvées plutôt
qu'un pari.

### Le défaut principal, trouvé avant qu'il ne frappe

`TileId` exposait sa géométrie en propriétés calculées. Chaque lecture de
`corners` alloue neuf objets ; `center` et `boundingRadius` la rappellent, et
`splitFactor` comme `isVisible` les rappellent à leur tour — soit **environ 85
allocations par tuile évaluée**.

Une sélection visite quelques milliers de nœuds. À soixante images par seconde,
cela aurait représenté **quinze millions d'objets alloués par seconde**. La
descente aurait saccadé alors que le nombre de triangles serait resté correct,
et le diagnostic aurait été long.

`TileSelector` calcule la géométrie une seule fois par nœud, dans des champs
primitifs, avec une pile d'identifiants compactés en entiers longs. Un test
vérifie qu'il rend exactement le même résultat que la version naïve : le coût
change, pas le rendu.

### Élimination par le cône de vision

L'horizon seul ne suffit pas : au ras du sol, tout le tour de la planète reste
géométriquement visible alors que l'écran n'en montre qu'une fraction. `ViewCone`
ajoute un test conservateur — il ne rejette jamais une tuile visible, un test le
vérifie — et retire au moins un quart de la charge au sol.

### Pool de fils d'exécution

Le fil unique convenait pour générer un monde toutes les dix secondes. Le rendu à
tuiles en demande des dizaines par seconde. `TileWorkerPool` répartit sur les
cœurs disponibles, en laissant toujours un cœur au rendu, et **classe les travaux
par priorité** : la tuile au centre de l'écran passe avant celle qui affleure à
l'horizon. Les travaux devenus inutiles sont annulés par un drapeau plutôt que
retirés de la file, opération coûteuse.

### Recyclage des tampons GPU

Lot 0.10, resté en dette depuis la Phase 0. Créer et détruire des centaines de
tampons par seconde force une synchronisation avec le pilote à chaque appel et
fragmente la mémoire. `GpuBufferPool` recycle par casiers de capacité arrondie à
la puissance de deux : une demande de 42 Ko réutilise un tampon de 64 Ko sans
réallouer.

### Banc d'essai automatisé

Lot 0.14, également en dette. Vingt mondes générés, contrôlés individuellement
puis **en distribution**, avec rapport publié dans `build/reports/worlds.txt`.

Sa raison d'être : la planète boule de neige de la v0.3 avait franchi la CI parce
qu'aucun test ne regardait la moyenne sur plusieurs graines. Un contrôle par
monde ne voit pas qu'un défaut est systématique.

## v0.6.1 — Correction du repère géodésique

Deux erreurs de signe dans `Geodesy`, révélées par les tests :

- `eastAt` rendait `ref × haut`, soit un vecteur pointant vers l'**ouest**. Le
  produit correct est `haut × ref`.
- `move` appliquait une rotation de `−angle` : demander le nord envoyait au sud.

Elles se compensaient exactement pour un déplacement vers l'est, ce qui laissait
passer le test de glissement latéral, et s'additionnaient vers le nord.

Le repère local suit désormais la convention directe `nord × est = haut`, et
coïncide avec les dérivées de la paramétrisation à 5·10⁻⁷ près.

**Nouveau test de non-régression** : plutôt que de raisonner sur des intuitions
d'orientation, le repère est comparé aux dérivées analytiques de la
paramétrisation en 306 points répartis sur la sphère. Une erreur de signe y
devient impossible à manquer — c'est le test qui aurait dû exister dès le départ.

## v0.6.0 — Caméra géodésique et lancer de rayon

Lot A du rendu à niveaux de détail. Kotlin pur, testé, sans aucune modification
du moteur de rendu : rien de ce qui fonctionne ne peut être cassé.

### Le modèle de navigation

Celui des globes virtuels : la caméra vise un **point posé sur le sol**, à une
certaine distance, sous un cap et une inclinaison. Glisser déplace ce point,
pincer change la distance.

Ce qui rend la descente naturelle tient dans une seule propriété : **l'amplitude
d'un glissement est proportionnelle à la distance**. À 10 000 km, un geste de
300 pixels parcourt 2 130 km ; à 100 m de portée, le même geste parcourt 21 m —
un rapport de 100 000 pour un rapport de portée de 100 000. Aucun basculement de
mode, aucun seuil : « faire rouler le globe » et « faire défiler le sol » sont la
même formule, et la transition est continue.

L'inclinaison s'ouvre progressivement en descendant, sur une échelle
logarithmique : nulle en orbite, où une vue rasante serait illisible, jusqu'à 82°
près du sol, où elle donne l'horizon. Elle se replie d'elle-même si l'on remonte.

### Double précision

`Vec3d` et `Geodesy` doublent `Vec3` et `Sphere` en 64 bits. À 6 371 km du
centre, le flottant 32 bits ne distingue plus rien en deçà de **cinquante
centimètres** : une caméra posée au sol tremblerait à chaque image. L'état de la
caméra est purement géodésique — latitude, longitude, portée, cap, inclinaison —
et la position métrique n'est produite qu'à la demande.

### Lancer de rayon

`TerrainRaycaster` intersecte un rayon avec le relief par *sphere tracing* : à
chaque pas on avance d'une fraction de la hauteur disponible au-dessus du sol,
puis une bissection affine le contact. Trois usages :

- **zoom vers le doigt** : le point du sol sous les doigts y reste pendant le
  pincement, au lieu de dériver vers le centre de l'écran ;
- **butée de caméra** : `snapToTerrain` empêche l'œil de traverser une montagne,
  en reculant puis, si nécessaire, en redressant la vue ;
- **sélection d'entités**, qui servira en Phase 4.

### Tests

Trente-cinq tests nouveaux : orthonormalité du repère local sur toute la sphère
y compris aux pôles, proportionnalité du glissement à la distance, convergence du
zoom vers sa cible, repliement de l'inclinaison, stabilité après trois mille
manipulations aléatoires, passage au-dessus du pôle, exactitude du point
d'impact, et non-traversée du terrain à inclinaison maximale.

## v0.5.1 — Correctif

- `Geography` : `LAKE_THRESHOLD` et `INLAND_SEA_THRESHOLD` étaient utilisées sans
  être déclarées. La modification automatique qui devait les insérer ciblait le
  bloc avec une indentation de quatre espaces alors qu'il se trouve à huit, dans
  le `companion object` — le remplacement n'a donc rien fait, sans le signaler.

  Le code qui les consomme, lui, avait bien été inséré : d'où deux références
  orphelines et l'échec de `:sim:compileKotlin`.

- Ajout d'un contrôle de références orphelines, exécuté sur l'ensemble des
  fichiers avant chaque livraison. Confronté à la version fautive, il retrouve
  précisément les deux constantes manquantes.

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
