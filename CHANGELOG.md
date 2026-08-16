# Journal des versions

## v0.48.0 — Lot 3.3-b : niveaux de détail et allocation sous budget

L'instruction (validation/lod_arbres.py) a INVALIDÉ la conception que
suggérait la feuille de route. Des seuils de distance ne tiennent pas :
même au réglage le plus serré, une forêt de conifères réclame 2,4 fois le
budget mesuré, parce que le nombre d'arbres croît comme le carré de la
distance et qu'un seuil ne sait pas combien d'arbres il vient d'admettre.

Le lot livre donc deux mécanismes. D'abord quatre NIVEAUX de maillage —
plein (17 776 triangles sur un conifère), moyen (10 110), bas (638),
panneau (4) — dont les plafonds sont exprimés en TAILLE APPARENTE, ce qui
les rend valables de la mousse de huit centimètres au conifère de douze
mètres sans aucune distance codée en dur. L'essentiel du gain vient de
l'élagage des rameaux, pas du nombre de côtés : un conifère porte 1 000 de
ses 1 111 segments au dernier niveau.

Ensuite un ALLOCATEUR : les arbres sont triés par taille apparente et
servis dans cet ordre, chacun recevant le meilleur niveau que le budget
restant permet. Le budget est ainsi tenu par construction, quelle que soit
la densité — c'est la propriété que des seuils ne savaient pas garantir, et
elle est testée sur des forêts de 10 à 20 000 arbres. Le coût du panneau
est payé d'avance pour tous : sans ce plancher, un budget serré donnerait
tout aux premiers arbres et ferait disparaître les suivants.

Budget par défaut : 700 000 triangles, dérivé d'une mesure réelle sur
Mali-G77 (330 000 triangles en 6,6 ms, marge de 30 % conservée pour les
pics et les appareils plus lents).

Console : « arbre conifère bas », « arbre panneau » — les niveaux se
comparent à l'œil. Quatorze tests ajoutés. Mes premiers seuils de test
étaient devinés et faux d'un tiers ; ils ont été remplacés par les valeurs
mesurées.

## v0.47.0 — Lot 3.3-c : le feuillage (un lot que le plan n'avait pas)

La feuille de route ne contenait AUCUN lot créant du feuillage : le 3.3 ne
traite que des niveaux de détail, le 3.4 de la coloration, le 3.8 du cycle
saisonnier — tous supposent un feuillage qui n'existait nulle part. C'était
un trou du plan, pas un oubli d'implémentation : sans ce lot, le feuillu
restait un arbre d'hiver.

Contrainte propre à Terra : aucune texture n'est embarquée, donc les cartes
de feuilles à découpe alpha — la solution habituelle — seraient ici deux
rectangles opaques croisés. Le feuillage est donc GÉOMÉTRIQUE. Une touffe =
un OCTAÈDRE (8 triangles) aux trois demi-axes réglables, centré sur son
rameau et orienté par lui, dimensionné en multiples de la LONGUEUR du
rameau pour que les touffes voisines se recouvrent — sinon la couronne se
lit comme un chapelet de billes. Le conifère les prend très allongés et
plats (rapport 6,4:1) : une palme d'aiguilles, pas un pompon ; le palmier
plus encore ; le cactus n'en reçoit aucune, sa tige est verte.

Les touffes garnissent les `foliageDepthSpan` derniers niveaux : deux pour
les feuillus et arbustes (243 rameaux terminaux seuls donneraient une
couronne clairsemée), un pour les autres. Budget : 17 776 triangles pour un
conifère dont 8 000 de feuillage, 6 472 pour un feuillu — aucun arbre au-delà
de 20 000.

L'orientation des huit faces de l'octaèdre a été vérifiée en Python avant
d'être écrite, puis confiée à un test : une face retournée ferait un trou
dans la couronne, invisible en CI. Cinq tests ajoutés.

## v0.46.1 — Le pied de l'arbre s'enfouit

Le sol DESSINÉ est la surface d'une tuile (grille de 16×16 altitudes
interpolées), pas le terrain exact sur lequel l'arbre était posé. Les deux
s'écartent entre les nœuds : ~36 cm sous une tuile de niveau 18, celle
qu'on obtient à 50 m de distance. Le pied flottait donc au-dessus du sol
visible, avec son anneau de base ouvert en évidence — mot pour mot la leçon
v0.26.1, tirée pour la végétation des tuiles et oubliée ici.

`TreeSkeleton.footSinkM()` calcule l'enfouissement dans :sim, testé :
max(2 × rayon du tronc, 5 % de la hauteur), plafonné au quart de la
hauteur. Soit 58 cm pour un conifère, 56 pour un feuillu, mais 6 mm pour
une mousse — le plafond empêche d'engloutir les petites plantes.

Limite assumée : au-delà d'environ 150 m, la tuile devient assez grossière
pour que l'écart dépasse l'enfouissement. La vraie réponse est celle de la
v0.26.1 — échantillonner la surface de la TUILE — et elle viendra avec la
répartition du lot 3.6, qui donnera accès à la grille d'altitudes.

## v0.46.0 — Lot 3.3-a : les arbres prennent du volume

Le fil de fer du lot 3.1 dessinait des traits d'un pixel et ignorait
totalement les rayons du squelette — d'où des troncs invisibles même de
près, alors que le tronc d'un conifère devrait couvrir 15 px à 50 m et
146 px à 5 m. (Et non, `glLineWidth` n'aurait pas sauvé la mise : la
plupart des pilotes GLES2 plafonnent la largeur de trait à un pixel.)

Chaque segment devient donc un TUBE à huit côtés, du rayon de base au rayon
de pointe. La continuité des rayons, invariant testé depuis le 3.1, suffit
à ce qu'aucune marche n'apparaisse aux raccords : il n'y a pas une ligne de
code de couture. Les branches terminales se ferment en CÔNE — sans quoi le
maillage resterait ouvert, avec un trou de 5,9 cm au bout des rameaux de
conifère (41 px vus à deux mètres, à travers lesquels l'élagage des faces
arrière donnerait à voir l'intérieur de la branche) — et le cône coûte
moins cher qu'un tube. Éclairage lambert avec ambiante généreuse, couleur
qui passe du brun d'écorce au vert de feuillage selon le rayon ET la
profondeur (un palmier n'a qu'un niveau de branches et doit verdir quand
même).

L'instruction (validation/maillage_arbres.py) a corrigé une contradiction
de ma propre rédaction — je concluais « 0,7 px d'écart » quand le tableau
au-dessus affichait 13,9 px, la leçon des gyres v0.15.2 — et a remplacé ce
critère par un critère SANS DIMENSION : à huit côtés, la silhouette
s'écarte du cercle de 3,8 % de la largeur du tronc, quelle que soit la
distance.

Un conifère pèse 9 776 triangles et 1,06 Mo. Négligeable seul, intenable à
cent : le lot 3.3-b (dégradation par distance) n'est pas un confort mais la
condition du lot 3.5. Dix tests ajoutés, dont l'orientation des triangles
(un triangle retourné ne se verrait que sur appareil) et le respect exact
des rayons du squelette.

## v0.45.1 — Correctif : le palmier refusé par sa propre garde

`children hors de [1 ; 6] : 8` — cinq tests rouges, un seul défaut. La
borne avait été posée au lot 3.1, avant que les palmiers n'existent, et
elle était arbitraire : ce qui protège réellement est le COMPTE de segments
(20 000), vérifié séparément. Borne élargie à 12, avec la justification en
commentaire.

La cause racine est ailleurs, et c'est elle qui compte : le miroir Python
(validation/especes.py) ne reproduisait que la GÉOMÉTRIE, pas les gardes.
Il mesurait des silhouettes pour un paramétrage que le Kotlin refusait. Le
miroir valide désormais les mêmes bornes que TreeParams.validate() avant
toute mesure — un miroir sans les gardes ne prouve que la moitié de ce
qu'on lui demande.

Aucun test à ajouter : celui qui devait attraper le bug existait et l'a
attrapé (toutesLesFamillesSeGenerentEtTiennentLeBudget).

## v0.45.0 — Lot 3.2 : sept familles d'espèces, et la grammaire qu'elles exigent

Le lot 3.1 n'attachait les branches qu'à la POINTE du parent. Un feuillu,
un palmier, un cactus s'en accommodent — un conifère non : un sapin porte
ses branches le long de son fût. La grammaire gagne donc des VERTICILLES
LATÉRAUX (lateralWhorls, lateralPerWhorl, attachStartFraction) ; le facteur
de branchement devient children + whorls × perWhorl, si bien que la garde
des 20 000 segments reste valable telle quelle. La continuité des rayons du
3.1 se généralise : un latéral naît avec exactement le rayon du parent au
point d'attache, jamais plus gros que le fût qui le porte.

Sept familles : conifère, feuillu, palmier, cactus, arbuste, herbacée,
mousse. Console : « arbre conifère », « arbre palmier 7 », « arbre 7
palmier » (les arguments se reconnaissent à leur forme, l'ordre est libre),
« arbre off ».

L'instruction (validation/especes.py) a fait son travail AVANT le Kotlin :
elle a invalidé un premier paramétrage où conifère et feuillu se
confondaient, PUIS écarté un critère qui semblait évident — la conicité
(envergure haute / envergure basse) ne sépare pas les deux familles, le fût
nu plaçant la coupure au milieu du feuillage. C'est l'élancement (envergure
/ hauteur) qui sépare : 0,30…0,33 contre 0,46…0,59, mesurés sur 400 tirages
par famille. Tous les seuils des tests viennent de ces mesures.

Au passage, « arbre off » n'est plus une graine nulle mais une commande
distincte : le type dit maintenant la bonne chose, et le piège de smart
cast inter-modules de la v0.44.0 disparaît par construction. Huit tests
ajoutés (espèces), la commande console mise à jour.

## v0.44.1 — Correctif : smart cast inter-modules, et compte des tests

MainActivity.kt:905 : « Smart cast to 'Long' is impossible » — cmd.seed
est une propriété publique de :sim, un AUTRE module, et Kotlin refuse le
transtypage après un test de nullité sur une propriété qu'il ne peut pas
garantir stable. Capture dans un val local avant le test. Leçon jumelle du
Float-in-ClosedRange (v0.40.0) : les règles de sûreté de Kotlin se
vérifient à la relecture comme des invariants, pas comme du style.
Au passage : le changelog v0.44.0 annonçait dix tests pour le générateur,
il y en a neuf.

## v0.44.0 — Lot 3.1 : générateur d'arbres (Phase 3 ouverte)

Une grammaire de branchement paramétrée (angle, ramification, longueur,
conicité, dispersion, tropisme) produit des squelettes déterministes en
Kotlin pur : segments avec base, pointe, rayons, profondeur et parent, dans
un repère local Y-haut. Un enfant « continuation » à angle réduit fait les
fûts, les autres branches ouvrent à l'angle nominal, l'azimut tourne à
l'angle d'or (137,51°), le tropisme redresse par nlerp (monotonie vérifiée
dans validation/arbres.py, qui chiffre aussi le budget : garde à 20 000
segments par REFUS déterministe — écrêter changerait la forme). La pointe
d'un parent porte exactement le rayon de base de ses enfants : aucun
ressaut aux raccords, testé au bit près.

Console : « arbre [graine] » plante le squelette de l'espèce-test (k=3,
profondeur 5, 364 segments) au point visé, dessiné en fil de fer teinté
par profondeur — les sommets sont fabriqués et testés dans :sim, :app ne
fait que les téléverser. « arbre off » le retire. Dix tests ajoutés pour le
générateur, un pour la console.

Le maillage (3.3), les familles d'espèces (3.2) et la répartition (3.6)
s'appuieront sur cette ossature.

## v0.43.0 — Lot 2.7-b2 : bascule du limbe câblée sur le registre orbite

L'instruction (§8 de validation/bascule.py) a établi le fait qui tranche :
l'inclinaison étant verrouillée par la portée, le limbe n'entre dans le
champ que sous ~350 km (vue rasante) ou au-delà de 2 959 km (nadir). La
bascule au registre orbite (entrée 2 240 km, hystérésis du 2.7-a) couvre
donc TOUTE la fenêtre haute avec 700 km de marge, et le point ouvert
« limbe continental 30-180 px » était un non-symptôme : hors champ. La
bascule est sèche — au moment du changement, seule la texture du terrain
change, le limbe n'est pas encore visible.

Le mode « limbe auto » devient le défaut : globe métrique en orbite, tuiles
en dessous. Les modes forcés (tuiles, globe, collerette) restent à la
console pour le diagnostic ; le HUD n'affiche que l'écart au nominal. Une
commande « banc limbe [alt_km] » (défaut 12 000) place la caméra au nadir
avec le disque entier dans le champ et le soleil au zénith : une capture
par mode suffit désormais à juger la silhouette. Un test ajouté (banc du
limbe) ; le cas « limbe auto » rejoint le test existant de la commande.

## v0.42.0 — Lot 2.20-a : capture d'écran, et vitesse ×400

Un bouton « Photo » (barre de droite, et commande console `photo`) enregistre
la surface OpenGL en PNG — SANS le HUD ni les boutons, qui sont des vues
Android par-dessus : le mode carte postale est gratuit. À partir de
l'API 29, la photo va dans la galerie (Pictures/Terra) via MediaStore, sans
aucune permission, conformément à la promesse du projet ; sous l'API 29 le
repli est le dossier privé de l'appli, sans permission non plus. La lecture
des pixels se fait en toute fin d'image sur le fil GL, la compression PNG
sur un fil de travail. Le nom de fichier (monde assaini, version, altitude,
horodatage UTC) est construit dans :sim, pur et testé — l'époque du test
d'horodatage a d'ailleurs été vérifiée en Python après qu'une valeur posée
de tête s'est révélée fausse de quatre jours.

La barre des vitesses gagne un cran ×400, demandé pour voir vivre le monde
depuis le sol. Six tests ajoutés (nom de capture ×4, console ×2).

## v0.41.0 — Outillage : bouton Console et commandes rapides

Un bouton « Console » rejoint la barre de droite (l'appui long en mode sol
reste possible), et le dialogue gagne deux rangées de commandes rapides —
limbe tuiles / limbe globe / limbe col, puis teinte / soleil 12 / aide.
Chaque bouton EST la ligne de commande qu'il affiche et passe par le même
runConsole que le champ libre : aucun chemin nouveau, rien de nouveau à
tester. Demandé pendant la campagne de captures du lot 2.7-b1.

## v0.40.1 — Correctif : compilation du HUD

L'insertion de l'indicateur de mode limbe (v0.40.0) avait coupé en deux la
chaîne fluide du HUD : le `.append` suivant s'enchaînait sur la valeur du
`when`, le mettant en position d'expression — donc tenu d'être exhaustif.
Échec de :app:compileDebugKotlin, MainActivity.kt:1063. Le `when` est
désormais une instruction séparée et la suite repart de `sb`. Aucun test ne
peut couvrir ce fichier (:app est sans filet JVM) ; la parade est de
discipline : relire le site d'insertion APRÈS édition, ce qui avait attrapé
les deux bugs du même lot dans PlanetRenderer mais n'a pas été fait ici.

## v0.40.0 — Lot 2.7-b1 : globe métrique et collerette (instrumentation)

Trois rendus du limbe comparables sur appareil par la console — `limbe
tuiles` (état normal, fondu vers le disque du ciel), `limbe globe`
(l'icosphère raffinée entière, dessinée dans le repère métrique de la
descente) et `limbe collerette` (la seule bande de ~1 000-1 500 faces
bordant l'horizon, glissée sous les tuiles, biais de profondeur calibré).
Aucun comportement ne change sans la console ; en modes globe et collerette
le fondu de limbe v0.24.0 est coupé, sinon il peindrait le disque plat
par-dessus la géométrie qu'on cherche à juger.

Le maillage contemplatif est réutilisé tel quel (mémoire +0) : le shader de
sommet dés-exagère le relief (formule et garde d'exagération nulle dans
:sim, testées) et soustrait l'œil — 0,76 m d'annulation au pire, un
millième de pixel en orbite, chiffré par `validation/bascule.py` qui
contient toute l'instruction du lot. Le fragment ne reçoit que des vecteurs
unitaires : un varying en mètres déborderait mediump (leçon v0.31.3
étendue). Sélection de collerette, cadence de resélection et biais vivent
dans :sim (`GlobeMetric`, `LimbBand`), neuf tests ajoutés.

Le but : des captures pour trancher le 2.7-b2 — bascule sèche, fondu, ou
peinture continue du globe — et statuer sur la collerette en registre
continental, où le limbe garde 30 à 180 px d'erreur.

## v0.39.0 — Lot 2.7-a : registres d'échelle

Cinq registres — sol, local, régional, continental, orbite — classés par
l'altitude de l'œil et stabilisés par une hystérésis en ratio (×1,12 de part
et d'autre). Les frontières sont calibrées par `validation/registres.py` sur
la distance d'horizon (quelle géographie remplit l'écran), et deux d'entre
elles sont des ancres existantes : 700 m est le plancher de dilatation
temporelle, 2 000 km est l'ouverture de l'inclinaison. Deux tests
verrouillent ces égalités pour qu'aucune constante ne vive à deux endroits.

Les registres ne re-quantifient AUCUNE vitesse : la loi continue du pan fait
déjà ce travail. Ce sont des étiquettes de navigation — au HUD dès cette
version, routeur de la bascule quadtree ↔ globe au 2.7-b.

Au passage, le plan lointain quitte le rendu pour :sim
(`ScaleRegistry.farPlaneM`), à l'identique — un test d'égalité exacte avec
l'ancienne écriture en ligne garantit que le déplacement est pur. Aucun
changement visuel dans cette version.

## v0.38.1 — Calque « Ressources »

Neuvième calque : la ressource dominante en teinte, sur un fond de relief
grisé. Une cellule peut en porter plusieurs — du bois pousse sur du sol
arable — et n'en montrer qu'une est un choix assumé : superposer six masques
donnerait une bouillie illisible. Les métaux ont les teintes les plus
saturées, parce que ce sont les rares et que ce sont eux qu'on cherche.

Le fond garde une trace du relief plutôt qu'un gris uniforme : sans lui on
ne saurait pas si un gisement est sur une côte ou une crête, et c'est
exactement la question qu'on se pose devant cette carte.

Le test générique des palettes couvre déjà le nouveau calque (bornes de
couleur sur tous les sommets) et, ce faisant, exerce la génération des
ressources sur deux mondes de plus. Un test dédié vérifie que les gisements
se distinguent franchement du fond — une carte où le cuivre se lirait comme
la roche nue ne servirait à rien.

## v0.38.0 — Lot 1.17 : carte de ressources (la Phase 1 est close)

Six ressources par cellule d'icosphère : sol arable, bois, pierre, cuivre,
fer, étain. Chacune est placée par sa géologie — le cuivre aux arcs de
subduction et aux panaches, le fer dans les vieux boucliers continentaux
loin de toute frontière active, l'étain aux seules collisions
continentales, le sol arable aux alluvions de plaine. C'est ce qui rendra
vraie la phrase du lot 6.8 : sans dépôt de cuivre à portée, une tribu reste
à la pierre.

**Méthode : percentile, pas seuil.** Un gisement se décrit spontanément par
un seuil (« du cuivre au-dessus de 0,7 »), mais ce score dépend du monde
tiré : sur trois distributions simulées, le même seuil couvrait 0 %, 12 %
et 79 % des terres (`validation/ressources.py`). On trie donc les cellules
terrestres et on garde la fraction voulue — la couverture devient exacte
par construction, sur tout monde et à toute graine.

Ce que les tests vérifient n'est donc pas la couverture, garantie, mais la
**cohérence géologique** : contraste de médianes entre la population du
gisement et les terres, sur la grandeur qui l'explique. Seuil 0,60, calculé
— un masque qui fonctionne donne 0,26, le hasard ne descend pas sous 1,0.
Les médianes plutôt que les moyennes : une distance angulaire a une
distribution à longue queue.

Champ **dérivé** (invariant n°6) : calculé à la demande depuis des données
déjà figées, hors empreinte et hors sauvegarde. `GENERATION_VERSION` reste
donc à **13** et les mondes existants gardent leur empreinte — ils gagnent
la carte au premier accès.

Deux précautions prises d'avance : un monde peut n'avoir aucune collision
continentale, donc pas d'étain ; le test constate alors l'absence légitime
au lieu d'échouer, mais vérifie d'abord laquelle des deux situations il
observe. Et le percentile ne peut jamais dépasser sa cible, ce qui est
exigé de toutes les ressources, quand l'égalité n'est exigée que de celles
dont le masque couvre forcément des terres.

**La Phase 1 est complète.** Il ne reste de la Phase 2 que 2.7, 2.11 (qui
dépend de la Phase 6) et 2.18–2.20.

## v0.37.0 — Le temps s'explique, le soleil existe, les étoiles se voient

**Le HUD dit enfin ce que le temps fait.** Le bouton annonce un
multiplicateur, mais la descente le divise par la dilatation d'altitude —
jusqu'à 2 880 au ras du sol. Le même ×200 fait donc passer un jour
planétaire en 0,2 s depuis l'orbite et en 12 min à pied, et rien ne
l'expliquait. Nouvelle ligne : vitesse effective, facteur de descente
appliqué, et durée réelle du jour. Aucun réglage n'est modifié — le
comportement était voulu, il était seulement invisible. Le calcul vit dans
`:sim` (`TimeReadout`) et non dans le HUD : un chiffre montré à
l'utilisateur mérite le même filet de tests que la simulation.

**Le ciel sait où est le soleil.** Il n'était qu'un dégradé vertical : au
couchant, tout l'horizon rougeoyait pareil, y compris dos au soleil. Deux
lobes de diffusion avant, largeurs calculées — un halo serré de 8° autour
de l'astre, une nappe de 33° qui couche la couleur sur un quart du ciel —
plus le **disque solaire** lui-même, rayon 0,40° (le vrai Soleil fait
0,265° ; à 60° de champ sur 1080 px cela donne 29 pixels, lisible sans
faire tache), à bord adouci sur 0,1° pour ne pas créneler. Le halo est
atmosphérique et s'éteint avec l'air ; le disque reste visible depuis
l'orbite. La couleur du disque suit la même mesure de crépuscule que le sol
et les tuiles, pour que tout vire ensemble.

**Les étoiles sont dimensionnées en unités d'écran.** `gl_PointSize` est en
pixels physiques : 1,5 px sur un écran à 440 dpi fait un dixième de
millimètre, d'où des étoiles à peine visibles. Elles passent à 2–6 unités
d'écran, soit 5,5 à 16,5 px sur cet appareil et une taille correcte sur un
écran basse densité. Le halo du point était par ailleurs trop piqué
(`a²` éteignait le bord, le disque perçu faisait 60 % du point demandé) :
noyau net plus halo large désormais.

`TimeReadoutTest` : durée du jour, rapport de dilatation, pause, seuils
d'unité, écriture en fraction sous ×1. Génération intouchée.

## v0.36.0 — Lot 2.17 : micro-détail du sol, filtré par la maille

Le sol proche avait trois échelles de teinte (1 274 m, 71 m, 9,1 m),
appliquées **quel que soit le niveau de tuile**. Deux défauts symétriques,
tous deux corrigés ici :

- **Au loin**, l'octave de 9 m était évaluée sur des mailles
  kilométriques — bien sous sa fréquence de Nyquist. Ce n'était plus du
  grain mais du bruit replié : le damier de la leçon v0.31.3, en plus
  discret.
- **De près**, la plus fine des trois faisait 9 m de période : à hauteur
  d'œil, le sol sous les pieds était un aplat. C'est le « sol nu » des
  points ouverts.

Chaque octave est désormais pondérée par un fondu calculé depuis la maille
de sa tuile, et deux échelles fines sont ajoutées : **gravier à 1,20 m** et
**grain grenu à 0,455 m**. Elles n'apparaissent qu'aux niveaux qui peuvent
les porter (20 et 23), donc sans salir aucune vue lointaine — mieux, les
octaves éteintes ne sont même pas évaluées, si bien que le coût de la
teinte retombe aux niveaux grossiers, ceux qui couvrent le plus de tuiles.

Deux réglages corrigés en cours de validation, tous deux par le calcul :
les amplitudes du premier jet faisaient peser le détail fin pour 45 % de la
teinte existante (ramené à 33 %), et le fondu, étalé de 2 à 4 mailles,
franchissait exactement **un** niveau de quadtree — la teinte claquait de
0,11 à chaque bascule. Étalé de 1,5 à 8 mailles (2,4 niveaux), le ressaut
tombe à 0,082, borne calculée et reprise telle quelle par le test.

`GroundTintScaleTest` : extinction aux mailles grossières, présence
effective du détail décimétrique, ressaut de bascule sous seuil, bornes
d'écrêtage tenues avec cinq octaves comme avec trois. Génération
intouchée, `GENERATION_VERSION` reste à 13.

## v0.35.1 — Correctif : NaN au fond des fosses

Test rouge sur un vrai bug, attrapé par un test de JUPE qui n'a rien à voir
avec l'eau — il vérifiait la luminance des couleurs de sommet et les a
trouvées non finies. Cause : la couleur du fond marin s'écrivait comme un
quotient de deux exponentielles, `exp(−2d/λ_c) / exp(−2d/λ_b)`. En float32,
le numérateur s'annule dès 182 m de profondeur et le dénominateur dès
1 664 m : au-delà, `0 / 0 = NaN`, et une fosse de quelques kilomètres
suffisait à peindre toute la tuile en NaN.

Le quotient s'écrit désormais en UNE exponentielle,
`exp(−2d·(1/λ_c − 1/λ_b))` — mathématiquement identique, jamais de division,
underflow propre vers zéro. La décomposition reste validée : écart maximal à
la couleur opaque inchangé à 0,033.

Deux tests ajoutés ou améliorés : `seafloorColor` et `waterLayerAlpha` sont
balayées jusqu'à 12 000 m et doivent rester finies et dans [0,1] ; le test
de jupe distingue maintenant « non fini » de « nul » dans son message — les
deux ne se soignent pas pareil, et la confusion a coûté une lecture de
rapport.

## v0.35.0 — Lot 2.9-b : l'eau devient transparente

Le relief sous-marin se voit à travers la surface, avec parallaxe : c'est la
promesse « transparence en eau peu profonde » de la feuille de route.

**Le problème que la note d'architecture n'avait pas vu.** Au 2.9-a, le
fragment d'eau calculait la couleur complète, fond compris. Rendre la couche
translucide en gardant cette formule compterait le fond DEUX fois — et on ne
peut pas simplement confier l'absorption au mélange matériel : GLES2 mélange
par un alpha SCALAIRE alors que l'absorption est par canal (le rouge meurt
neuf fois plus vite que le bleu), et GLES2 n'offre ni lecture du framebuffer
ni blending par canal.

**La décomposition retenue**, validée par `validation/eau_transparence_b.py` :
chaque surface porte ce qui lui appartient physiquement. Le terrain peint
l'absorption du trajet fond → œil (ce que le fond ÉMET), pré-divisée par la
transmittance du bleu ; la couche d'eau porte sa diffusion propre et un alpha
scalaire égal à `1 − exp(−2d/32)` (ce que l'eau AJOUTE). La pré-division
annule exactement la seconde atténuation du blending. Écart mesuré à la
couleur opaque validée : **nul sur le bleu** (même λ), **0,033 au pire sur le
vert**, à 10,3 m de profondeur — sous le seuil de perception pour une couleur
de cette luminosité. Un test rejoue la composition du matériel sur toute la
plage 0–300 m et exige moins de 0,045.

Détails du rendu : source **prémultipliée** (`ONE / ONE_MINUS_SRC_ALPHA`),
ce qui laisse le spéculaire s'ajouter au lieu d'être éteint par un alpha
faible — un éclat de soleil sur trente centimètres d'eau claire reste un
éclat. Reflet du ciel et écume poussent l'opacité en même temps qu'ils
teintent : ils couvrent, ils ne traversent pas. Au loin, l'alpha rejoint la
brume, sinon le fond marin sombre transparaîtrait à travers un horizon
laiteux. Profondeur testée mais non écrite pendant la passe, état rendu tel
quel ensuite.

Génération intouchée : `GENERATION_VERSION` reste à 13.

## v0.34.2 — Correctifs du premier essai sur appareil (lot 2.9-a)

**Le HUD affichait « v0.32.2 » depuis trois livraisons** : la version vivait
en double, dans le gradle (mise à jour) et dans une constante de
`MainActivity` (jamais). Le HUD lit désormais `BuildConfig.VERSION_NAME` —
une version qui vit à deux endroits finit toujours par mentir à l'un des
deux. `buildFeatures.buildConfig` activé (AGP 8 ne le génère plus par
défaut).

**Mers intérieures en fosses noires depuis l'orbite** (vu sur Virsken) :
l'émission d'eau « par cellule dont un coin est en eau » laissait des trous
aux niveaux grossiers — au niveau 2 une cellule couvre ~156 km et une mer
entière peut y tenir avec ses quatre coins à terre ; le fond marin, peint
sombre, s'exposait alors sans eau dessus. L'émission se décide désormais
par TUILE : un sommet de grille en eau et toutes les cellules sont émises,
l'eau sous les terres restant éliminée par le test de profondeur. Une tuile
continentale ne paie toujours rien. Tests de comptage mis à jour avec la
règle.

Reste ouvert, noté pour le 2.9-b : le dégradé littoral vu d'altitude est
plus dentelé qu'avec l'ancienne frange (la profondeur s'interpole sur UNE
maille là où la frange s'élargissait avec le niveau) ; un élargissement
perceptuel de la profondeur aux niveaux grossiers est le candidat. Le
« saut » de l'image près du sol est en cours de diagnostic — voir message.

## v0.34.1 — Correctif : la surface d'eau se construit en double

Test rouge sur un VRAI bug, attrapé au premier passage de CI par le test de
rayon de `TileWaterTest` : la surface d'eau était à 0,26 m de son rayon pour
une tolérance calculée de 0,0075 m. Cause : les sommets d'eau étaient émis
depuis les tableaux de directions en FLOAT (ceux des normales), et la
quantification float32 d'une direction coûte jusqu'à R × 2⁻²⁴ ≈ 0,38 m sur
le rayon reconstruit — de quoi noyer le biais anti-affleurement de 5 cm et
faire frémir la ligne d'eau contre un terrain construit, lui, en double.
C'est l'invariant n°5 du projet, violé à un endroit et respecté à trois
lignes de là.

Le mailleur conserve désormais les directions en double le long de la
grille et l'émission d'eau les consomme, exactement comme le terrain.
Validation numérique : pire erreur de la chaîne float 0,30 m (l'échec CI :
0,26 m), chaîne double 0,002 m, tolérance 0,0077 m — marge ×4. Le test ne
change pas d'un caractère : il avait raison.

Au passage, les deux avertissements du journal de compilation disparaissent
(paramètre `shoreBlend` orphelin de `colorFor` depuis la mort de la frange
de rivage, lambda de recherche de tuile).

## v0.34.0 — Lot 2.9-a : le fond marin existe, l'eau devient une couche

La mer n'est plus une facette du terrain écrêtée au niveau zéro. Le terrain
est rendu à son altitude VRAIE, fosses comprises, peint en fond de sable qui
s'assombrit avec la profondeur ; par-dessus, une couche d'eau dédiée — tuiles
calquées une pour une sur celles du terrain, sommets à 5 flottants (position
au rayon de la mer + 5 cm, profondeur, morph de profondeur), téléversée dans
le même VBO à la suite du terrain, dessinée après lui. Tous les chiffres
(calque 1:1 imposé par la pente côtière de 30 %, biais anti-affleurement de
5 cm, λ d'absorption 3,5/14/32 m, surcoût VBO ≈ 32 %) sortent de
`validation/eau_transparence.py`.

Écart assumé au découpage annoncé : houle, Fresnel et écume déménagent dans
le shader d'eau DÈS ce sous-lot — une mer opaque ET immobile aurait masqué
toute régression visuelle. Le shader de TUILE, lui, n'est pas touché : la
mer y met simplement son matériau à zéro et ses termes d'eau s'y éteignent
d'eux-mêmes, pendant que les lacs continuent de s'en servir à l'identique
jusqu'au 2.9-c. L'eau est OPAQUE dans ce sous-lot (la transparence est le
2.9-b) ; pas de lueur nocturne sur l'eau (leçon v0.32.2, structurelle ici).

La banquise reste écrêtée au niveau de la mer — c'est une surface solide,
pas une colonne d'eau — et n'émet pas d'eau dessous. La frange de rivage du
terrain disparaît : la couche d'eau, dont la profondeur s'interpole vers
zéro au trait de côte, dessine la ligne d'eau à sa place. La collision
caméra était déjà écrêtée de son côté (`heightAboveTerrain`) : mode piéton
et descente inchangés.

Génération intouchée : `GENERATION_VERSION` reste à 13, empreintes
identiques. Tests : `TileWaterTest` (comptage exact des cellules,
profondeur et morph au bit près en rejouant l'ordre d'émission, rayon de
surface, fond marin à l'altitude vraie sur une tuile équatoriale où la
banquise est impossible, bornes d'absorption reprises du script) ; le test
d'altitude de `TileMeshTest` accepte désormais les deux surfaces légales
sous la mer, la garantie forte étant portée par la tuile équatoriale.

## v0.33.0 — La concurrence entre dans le filet de la CI

`TileWorkerPool` était la seule classe d'`:app` sans rien d'Android — hormis
un appel à `android.util.Log`. Cette ligne unique laissait toute la logique
de concurrence du pool (déduplication par clé, annulation, retrait
conditionnel du registre) hors de portée des tests, alors que deux courses
y ont déjà été trouvées à l'œil sur appareil.

Le pool vit désormais dans `:sim`, et parle à une interface `TerraLogger`
(`:core`) dont `:app` fournit l'adaptateur logcat. Comportement inchangé au
bit près : mêmes fils, mêmes priorités, même registre — seule la ligne de
journalisation passe par l'interface. Aucun changement de génération,
`GENERATION_VERSION` reste à 13, les empreintes ne bougent pas.

Sept tests nouveaux, déterministes par construction : un fil unique, un
travail « bouchon » qui l'occupe, et des verrous à compte à rebours à chaque
frontière entre fils — jamais d'attente aveugle. Le test le plus important
rejoue la course du retrait conditionnel : une tuile annulée pendant son
exécution, resoumise sous la même clé avant de finir, ne doit pas voir son
successeur effacé du registre par son propre `finally`.


## v0.32.2 — Les filaments blancs nocturnes : l'eau ne rétrodiffuse pas

Des fils blancs sinueux couvraient le paysage, y compris en pleine nuit.
C'est ce « en pleine nuit » qui a livré le diagnostic : la nuit, TOUS les
termes multipliés par vDay sont éteints — diffus, spéculaire, halo. Seule
subsiste la lueur nocturne. Les filaments étaient donc blancs DANS LA
DONNÉE, pas blanchis par l'éclairage : ce ne pouvait pas être des reflets.
Leur forme — de fins rubans au fond des vallées — désignait les lacs et
rivières du lot 1.11.

L'erreur est physique. La lueur nocturne simule une RÉTRODIFFUSION de
surface : l'herbe, la roche et le sable renvoient un peu de la lumière du
ciel dans toutes les directions. L'eau ne le fait pas — elle RÉFLÉCHIT, et
un ciel nocturne est noir. Appliquée à l'eau, cette lueur faisait luire les
plans d'eau 1,5 fois plus que la forêt, alors qu'ils devraient être le
point le plus SOMBRE du paysage.

Correctif : la lueur nocturne est atténuée de 88 % sur l'eau. Les rivières
deviendront des rubans sombres la nuit — et, avec le Fresnel de la v0.32.1,
des miroirs du ciel de jour et au crépuscule. Deux comportements opposés
selon l'heure, et tous deux justes.

Note de méthode : ce diagnostic n'a demandé aucune capture supplémentaire.
Éliminer les termes éteints la nuit suffisait à écarter toutes les
hypothèses de reflet — raisonner sur ce que le code PEUT produire vaut
souvent mieux que raisonner sur ce que l'image semble montrer.

## v0.32.1 — L'eau réfléchit enfin le ciel (lot 2.9, volet reflets)

**Réflexion de Fresnel.** Une mer regardée d'aplomb est sombre et laisse
voir sa profondeur ; regardée de biais, elle devient un miroir. C'est LE
trait visuel de l'eau, et il manquait entièrement — la mer de Terra était
une surface diffuse avec une tache de soleil. Approximation de Schlick,
R0 = 0,02 pour n = 1,33 : 2 % à la verticale, 24 % à 75°, 65 % à 85°. La
bande argentée que l'on voit depuis toute plage — et que le mode piéton
regarde précisément de biais.

Deux détails qui font la qualité : la normale utilisée est celle de la
HOULE, si bien que crêtes et creux ne renvoient pas le même ciel et que la
surface scintille au lieu de se vitrifier ; et le reflet reprend la couleur
du ciel du moment (uHaze), donc il vire à l'orange au couchant et
s'assombrit la nuit sans un calcul de plus.

**Écume de rivage.** La frange où le matériau passe de terre à eau est la
laisse, là où la houle butte sur le fond. Elle blanchit, modulée par le
temps de houle pour respirer au lieu de figer un ourlet.

**Ce que ce lot ne fait PAS**, et qui reste au lot 2.9 complet : l'eau
appartient toujours au maillage du terrain, à rayon constant. La
transparence en eau peu profonde — voir le fond depuis la surface —
demande la profondeur au fragment, donc un canal de sommet supplémentaire
ou une surface d'eau séparée. C'est un vrai lot, pas un réglage.

Budget de varyings vérifié avant écriture : 12 flottants sur les 32
garantis par GLES2.

## v0.32.0 — Crépuscule et rugosité des matériaux

Deux petits lots groupés, tous deux issus du croisement graphique.

**1. Le crépuscule, promesse du lot 2.10 enfin tenue.** Ce lot annonçait un
« rougeoiement rasant » ; le ciel se contentait de s'assombrir sans virer.
Quand le soleil rase l'horizon LOCAL, sa lumière traverse une épaisseur
d'atmosphère bien plus grande : le bleu se diffuse au loin, il ne reste que
l'orange. C'est exactement ce que mesure le cosinus du soleil sur la
verticale du lieu — pas une minuterie, une géométrie.

Le ciel et le terrain utilisent la MÊME mesure, sans quoi un sol orangé
sous un ciel resté bleu jurerait. Au couchant, l'horizon vire à un orange
franc et le zénith au violet sombre : c'est ce dégradé, et non une teinte
uniforme, qui fait un crépuscule. Et la lumière DIRECTE seule se teinte —
l'ambiante vient du ciel entier et garde sa couleur, ce qui donne des
ombres bleutées sous un soleil orange, comme au vrai couchant.

**2. Rugosité par matériau.** Le spéculaire n'existait que sur l'eau : un
sol éclairé de biais restait parfaitement mat, donc plat quel que soit son
relief. Toute surface réelle renvoie la lumière rasante — le sable sec et
la neige davantage que la forêt ou la roche humide. La rugosité se lit dans
la COULEUR déjà calculée (un sol clair réfléchit plus qu'un sol sombre) :
aucun canal nouveau, le format de sommet reste intact. Eau : reflet serré
et vif, exposant 90. Sol : lustre large et faible, exposant 8.

Audit systématique des shaders passé en routine après l'épisode uSnow :
uniformes partagés entre étages ET varyings lus sans être écrits. Zéro
divergence.

## v0.31.3 — uSnow, et le damier de l'occlusion

**1. Échec d'édition de liens sur uSnow.** Exactement le piège de la
v0.26.1 : le sommet le déclare en highp par défaut, le fragment le fait
tomber sous son precision mediump, et Mali refuse de lier — la météo était
donc muette. J'avais corrigé uDrift sans auditer les autres uniformes
partagés ; c'est fait cette fois, exhaustivement, sur les quatre couples de
shaders : uSnow était le dernier. Il passe en mediump EXPLICITE des deux
côtés — un drapeau 0/1 n'a aucun besoin de précision, et aligner par le bas
est plus sûr qu'exiger highp d'un fragment.

**2. Quadrillage régulier sur le terrain.** Pas du relief : un repliement
de spectre. Le laplacien à quatre voisins qui mesure la courbure amplifie
d'un facteur 2 le motif alterné d'une maille sur deux — précisément la
fréquence où vit le grain du micro-relief (9 m de longueur d'onde sur des
mailles de 9 m au niveau 16). Le champ d'occlusion est désormais lissé par
une moyenne 3×3 avant application : le damier s'annule (gain ~1/9) tandis
qu'une vraie vallée, large de plusieurs mailles, est préservée (gain ~1).
La séparation d'échelles est structurelle, pas réglée.

Leçon consignée : un filtre différentiel sur une grille amplifie toujours
la fréquence de Nyquist. Toute mesure de courbure sur données bruitées
doit être lissée — ou calculée sur un support plus large que le bruit.

## v0.31.2 — Un garde-fou calibré sur un chiffre faux

Le test de fidélité de la carte d'humidité échouait — mais pas sur la
fidélité : sur son propre garde-fou. Il échantillonnait un sommet sur 37
et exigeait au moins 100 échantillons, seuil calibré en supposant 10 242
sommets. Or les tests génèrent au niveau 4 : 2 562 sommets, donc 70
échantillons. Le garde-fou tombait avant que la projection soit évaluée.

Correctif : parcours EXHAUSTIF. Deux mille lectures de tableau ne coûtent
rien, et il n'y a plus aucun pas à calibrer — la même logique que les
quartiles du test d'occlusion : supprimer la constante plutôt que la
corriger. L'avertissement « Name shadowed: k » des jupes est levé au
passage.

Septième seuil deviné du projet, mais d'un genre nouveau : ce n'était ni
la valeur ni la population, c'était un CHIFFRE DE RÉFÉRENCE faux — le
nombre de sommets de la grille de test, pris de mémoire au lieu d'être lu.

## v0.31.1 — Les nuages suivent enfin les pluies (dette du lot 2.14 soldée)

Le lot 2.14 prescrivait une couche « pilotée par l'humidité SIMULÉE en
Phase 1 » ; la v0.26.0 a livré un bruit pur. Les déserts avaient donc
autant de nuages que les forêts tropicales, alors que le transport
d'humidité sait exactement où il pleut depuis la v0.21.0. C'était le seul
endroit du moteur où l'apparence contredisait la simulation.

Le bruit donne désormais la FORME, l'humidité donne la PRÉSENCE : le seuil
de condensation varie de 1,28 sur un ciel sec à 0,72 sur un ciel saturé.
Au-dessus d'un désert presque rien ne perce ; au-dessus d'une forêt
tropicale le ciel se couvre. Un plancher de 20 % subsiste partout — un
ciel rigoureusement vide sur un tiers de la planète paraîtrait cassé
plutôt que sec, et même le Sahara voit passer des cirrus.

Première texture du moteur : carte équirectangulaire 256×128 en
GL_LUMINANCE, 32 Ko, construite une fois par monde sur le fil de travail.
Puissances de deux et bornage vertical — GLES2 ne garantit le filtrage
linéaire que dans ce cas, et un GL_REPEAT vertical replierait l'Arctique
sur l'Antarctique. Conservée après téléversement, comme le maillage et les
étoiles, pour survivre à une perte de contexte.

**Piège évité en chemin, de la même famille que celui de la v0.26.1 :** ma
première écriture lisait la texture dans le shader de SOMMET, pour l'ombre
des nuages. Or GLES2 autorise GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS à valoir
ZÉRO : sur une partie du parc, le lien aurait échoué. L'ombre utilise
désormais une valeur d'humidité PAR TUILE, calculée sur le processeur —
une cellule d'humidité fait 155 km, une tuile au-delà du niveau 8 tient
dedans : l'approximation est excellente et le risque disparaît.

Trois tests sur la carte : bornes et contraste, fidélité de la projection
aux précipitations de chaque cellule, et contraste désert/forêt vérifié
par quartiles.

## v0.31.0 — Les ombres de nuages : le ciel touche enfin le sol

Meilleure idée du compte rendu graphique, absente de la feuille de route.
Jusqu'ici, nuages et terrain s'ignoraient : une couche flottait, un sol
brillait dessous, sans lien.

Le point délicat est la COHÉRENCE : une ombre doit tomber exactement sous
le nuage qui la porte. Dupliquer la formule de densité dans les deux
shaders l'aurait garantie le jour de l'écriture et jamais ensuite — un
réglage d'un côté aurait décalé les ombres sans prévenir. Le champ de
nuages devient donc une source GLSL unique, insérée dans les deux
programmes : une seule formule, vérifiée présente une seule fois dans le
fichier.

Géométrie : le point du sol est projeté sur la coquille nuageuse LE LONG
DU RAYON SOLAIRE — la direction obtenue porte le nuage responsable. Le
facteur 1/cos allonge l'ombre quand le soleil rase l'horizon, comme un
soir d'été. Calcul au SOMMET et non au fragment : les masses nuageuses
sont bien plus larges qu'une maille de tuile, l'interpolation ne se voit
pas, et le fragment terrain était déjà chargé — coût quasi nul.

L'ombre n'atténue que la lumière DIRECTE, à 55 % au maximum : l'ambiante
vient du ciel entier, qu'un nuage ne masque pas, et une ombre totale
ferait tache d'encre. Le shader de coquille s'allège au passage — son
octave large repasse du sommet au champ partagé.

Prochain lot annoncé : les nuages pilotés par l'HUMIDITÉ simulée, dette du
lot 2.14. Il demande une texture — première du moteur — donc un lot à lui
seul.

## v0.30.2 — Jupes visibles et traits blancs : une seule cause

Les deux derniers défauts visuels de l'audit ont la même origine, et ce
n'était pas celle que je supposais.

Une jupe porte la normale RADIALE — c'est ce qui la fond dans le sol
qu'elle prolonge. Mais un mur vertical ainsi éclairé reçoit la lumière
comme un sol horizontal : au soleil rasant, il devient plus CLAIR que le
terrain voisin. D'où les bandes pâles au premier plan ET les fins traits
blancs sur le sable — le même mur, vu de face ou par la tranche.

Correctif en deux volets :

- **Teinte.** La jupe est assombrie à 55 %. Physiquement, ce rebord est
  dans l'ombre de la lèvre qui le surplombe ; s'il affleure, il se lit
  désormais comme une ombre de ressaut et non comme un mur lumineux.
- **Profondeur.** L'ancienne loi (0,5 % de l'ARÊTE, plancher 5,5 m) se
  trompait dans les deux sens, le calcul l'a montré : trop courte aux
  niveaux grossiers (786 m au niveau 6 pour un besoin de 1 466 m) et
  TRENTE FOIS trop longue au ras du sol — un mur de cinq mètres à côté du
  piéton. Nouvelle loi : 0,30 × la MAILLE, plancher 20 cm. Elle donne
  2 932 m au niveau 6 et 72 cm au niveau 18 : plus généreuse là où les
  fissures menacent, discrète là où l'œil est proche.

Le test existant qui mesure l'écart RÉEL entre niveaux sur trois mondes
reste le juge de la non-régression des fissures. Deux tests ajoutés : la
jupe doit être plus sombre que le bord qu'elle prolonge, et sa profondeur
doit suivre la maille en restant monotone.

## v0.30.1 — Durabilité, cycle de vie, portabilité

Second lot d'audit. Trois correctifs, aucun visuel.

**1. Sauvegarde atomique et durable.** Trois défauts, par gravité : elle
SUPPRIMAIT le fichier avant de renommer le temporaire (fenêtre où aucune
sauvegarde n'existait — une coupure à cet instant perdait le monde) ; elle
ne forçait pas l'écriture sur le support (`writeBytes` rend la main quand
les octets sont dans le cache du noyau, une coupure de courant pouvait
laisser un fichier de la bonne taille au contenu partiel) ; elle ne
vérifiait pas ce qu'elle venait d'écrire. Désormais : encodage relu avant
écriture, `fd.sync()` explicite, renommage PAR-DESSUS sans suppression
préalable — sur un même système de fichiers, `rename` est atomique. Au
chargement, un temporaire résiduel signale une écriture interrompue et est
effacé ; la sauvegarde précédente est intacte par construction.

**2. La simulation s'arrête en arrière-plan.** Seul le rendu était suspendu :
la boucle 10 Hz continuait d'avancer le temps planétaire, de bâtir le HUD et
d'échantillonner la météo, écran éteint. Elle s'éteint maintenant à onPause
et repart à onResume, avec `lastTickNanos` remis à zéro AVANT le relancement
— sans quoi le premier pas après la veille vaudrait toute la durée
d'absence.

**3. `highp` en fragment n'est pas garanti sur GLES2.** C'est une capacité
optionnelle, annoncée par `GL_FRAGMENT_PRECISION_HIGH`. Le Mali-G77 l'a ;
un GPU plus ancien aurait refusé de compiler le shader de nuages, laissant
ces appareils sans nuages ni pluie. Garde `#ifdef` avec repli `mediump`. Le
`uTime` du shader de météo reste en highp : il est dans l'étage SOMMET, où
la précision haute est garantie par la spécification.

## v0.30.0 — Reprise et concurrence : les quatre défauts critiques

Premier lot issu du double audit (le tien, le mien). Quatre correctifs de
fond, aucun visuel.

**1. Globe noir après perte de contexte — l'issue était CERTAINE, pas
possible.** Un filet existait dans onResume, mais il ne pouvait jamais se
déclencher : il testait `drawnTriangles == 0`, or drawGlobe() sort avant la
ligne qui met ce compteur à jour — la condition restait fausse à jamais. Et
s'il s'était déclenché, il aurait reconstruit le globe SANS son raffinement
haute définition. Le renderer conserve désormais son maillage RÉSIDENT et le
reverse lui-même dès que `uploadedVertexCount` retombe à zéro : plus aucune
dépendance à l'ordre entre onResume et onSurfaceCreated. Le filet inopérant
est retiré.

**2. Étoiles perdues à la reprise.** Même famille : le champ était consommé
puis mis à null. Il est conservé, avec un drapeau de téléversement remis à
zéro à la recréation du contexte.

**3. pendingMesh pouvait perdre un maillage.** `lire, téléverser, mettre à
null` effaçait ce que le fil de travail écrivait PENDANT le téléversement.
Remplacé par un échange atomique (getAndSet) : ce qui arrive après le point
d'échange sera vu à l'image suivante, jamais perdu.

**4. Course dans TileWorkerPool.** Le `finally` faisait `pending.remove(key)`
sans vérifier que la clé était bien la sienne : une resoumission intercalée
disparaissait du registre, et la suivante créait un doublon. Retrait
désormais conditionnel. Piège évité en chemin : dans le corps du job, `this`
désigne le POOL et non le job — la comparaison aurait été toujours fausse et
le registre aurait fui à chaque tuile. La référence est capturée
explicitement.

**5. Bonus, une régression de performance que j'avais introduite en v0.27.0 :**
la météo construisait un CoarseSampler NEUF à chaque rafraîchissement du HUD
en mode sol — soit le graphe d'adjacence des 10 242 cellules, dix fois par
seconde. Un seul échantillonneur est désormais construit par monde, partagé
avec le contexte de tuiles.

**Limite assumée, dite franchement :** aucun de ces cinq correctifs n'est
couvert par un test. `onSurfaceCreated`, l'échange atomique avec le fil
OpenGL et la course du pool vivent dans :app, que la CI n'exécute pas — et
le pool dépend d'android.util.Log, donc n'est pas testable en JVM pure en
l'état. Le filet reste la relecture et l'essai sur appareil. Rendre le pool
testable demanderait de le déplacer dans :sim avec une abstraction de
journalisation : c'est un lot en soi, à décider.

## v0.29.4 — Coutures d'ombrage : la normalisation ne doit rien à la tuile

L'occlusion de la v0.29.3 est enfin visible — les dunes se sculptent — mais
elle a introduit des coutures diagonales franches entre tuiles voisines.
Cause directe de son propre correctif : la normalisation utilisait la
rugosité MOYENNE DE LA TUILE. Une tuile de dunes et sa voisine de plaine
assombrissaient donc différemment le même relief à leur frontière commune.
Juste en amplitude, faux en continuité.

Remplacement : la concavité est divisée par le PAS DE GRILLE, ce qui en
fait une COURBURE — sans dimension. Elle ne dépend plus que du terrain et
de l'échelle d'échantillonnage, jamais du contenu de la tuile : deux
voisines de même niveau la calculent identique sur leur bord partagé, et
la continuité devient structurelle au lieu d'être réglée. Bonus, un relief
autosimilaire donne la même courbure à tous les niveaux : l'effet ne varie
plus avec le zoom. Gain 12 : plaine lisse 1 %, dunes 38 %.

Et surtout, LE TEST QUI MANQUAIT : deux tuiles voisines, sommets du bord
commun appariés par leur position dans l'espace, couleurs qui doivent
coïncider à 1 % près. Aucun test ne regardait les frontières — c'est
pourquoi la couture est passée jusqu'à l'écran.

Deux défauts restants, notés pour une capture ciblée : des jupes visibles
au premier plan et de fins traits blancs sur le sable.

## v0.29.3 — L'occlusion était inopérante : le test avait raison

Troisième échec du test d'occlusion, et cette fois il ne s'agissait plus
du test : écart mesuré −9,8·10⁻⁴, autrement dit AUCUN effet. Le défaut
était dans le code de production depuis la v0.28.0.

La normalisation divisait la concavité par la LONGUEUR de la maille — des
centaines de mètres — alors que la concavité est une VARIATION
D'ALTITUDE, quelques mètres. Deux ordres de grandeur d'écart : 0,4 % à
5 % d'assombrissement selon le relief, invisible à l'œil comme au test.
Comparer une hauteur à une longueur horizontale n'avait aucun sens.

Correctif : la référence devient la RUGOSITÉ MESURÉE de la tuile — la
moyenne de ses propres |concavités|, plancher de 5 cm pour qu'une tuile
plate ne voie pas son bruit d'arrondi amplifié en marbrures. Le rapport
concavité/rugosité vaut alors ±1 sur tout relief, et l'écart entre un
creux et une crête atteint 38 % de luminance : visible, et identique à
tous les niveaux du quadtree comme sur tous les terrains.

Deux leçons, la seconde plus utile que la première :

- Une grandeur ne se normalise que par une grandeur de MÊME DIMENSION.
  Diviser des mètres verticaux par des mètres horizontaux passe la
  vérification des unités et reste un non-sens physique.
- Un test qui échoue trois fois n'est pas forcément un mauvais test. Les
  deux premières fois, il l'était ; la troisième, il faisait son travail.
  L'auto-normalisation adoptée pour le sortir d'affaire est exactement le
  correctif dont le code de production avait besoin.

## v0.29.2 — Le test d'occlusion perd son dernier seuil deviné

Deuxième échec du même test, cause différente et nettement plus
embarrassante : sa « zone morte » exigeait 42 m de concavité pour retenir
un sommet, alors qu'un pas de grille de 305 m sur un relief ordinaire en
produit 1 à 5. Elle excluait donc la quasi-totalité des sommets et
laissait les populations vides — un seuil deviné, dans un test écrit pour
corriger un seuil deviné.

Le correctif supprime la constante au lieu de la retoucher : séparation
par QUARTILES. Les sommets sont triés par concavité, et l'on compare le
quart le plus concave au quart le plus convexe. Les deux populations
valent 25 % de l'échantillon par construction, quelle que soit l'amplitude
réelle du relief — il n'y a plus rien à calibrer, donc plus rien à
manquer. La stratification par biome et le cumul pondéré sont conservés.

Leçon consignée : quand un test échoue deux fois de suite, le problème
n'est plus le réglage mais la PRÉSENCE d'un réglage. Une statistique
auto-normalisée (quantiles, rangs) ne se trompe pas d'échelle.

## v0.29.1 — Test d'occlusion réécrit : il mesurait l'océan

Le test de la v0.28.0 échouait avec une dispersion de 7,3·10⁻⁴ — celle
d'une surface parfaitement uniforme. Cause : ses quatre tuiles témoins
avaient des coordonnées arbitraires, et sur un globe couvert à 66 % d'eau
elles sont tombées en mer, où l'occlusion est neutre par construction et
la couleur plate. Le test ne mesurait rien. Cinquième test mal calibré du
projet, deuxième dont la faute est la POPULATION observée et non le seuil.

Réécriture qui teste la propriété elle-même au lieu d'un effet de bord :
à biome égal, un sommet concave doit être plus sombre qu'un sommet
convexe. Trois garanties nouvelles — les tuiles sont CHERCHÉES sur terre
émergée (comme le fait déjà VegetationTest) ; la concavité est recalculée
indépendamment depuis le terrain continu, si bien que le test contrôle
l'implémentation au lieu de la reproduire ; et les populations sont
stratifiées par biome puis cumulées, jamais comparées en moyenne brute —
la leçon des courants (v0.15.3).

## v0.29.0 — Le mode piéton : être quelqu'un sur la planète

Un bouton « Piéton » en mode sol, et l'on cesse de piloter une caméra qui
plane : l'œil se place à 1,70 m du sol et y reste, quel que soit le
relief. Deux règles suffisent, et elles se déduisent du modèle de caméra
existant sans le modifier.

La caméra vise un point du SOL à distance `rangeM` sous un angle `tilt` ;
la hauteur de l'œil vaut donc `rangeM · cos(tilt)`, et l'on inverse :
`rangeM = 1,70 / cos(tilt)`. À l'inclinaison maximale (82°), la portée
tombe à 12 m — le regard porte douze mètres devant les pieds, exactement
la vue d'un promeneur. En se penchant (50°), on regarde le sol à deux
mètres. La plage entière est naturelle, sans constante arbitraire.

Vitesse ABSOLUE, et le calcul l'a imposée : la règle du glissement
(proportionnelle à l'altitude) aurait poussé le piéton à 35 km/h à douze
mètres de portée. Marche à 2,2 m/s manche à mi-course, course à 6 m/s
manche à fond. À cette allure, un tour de planète demande sept mois de
marche continue — c'est ce genre de chiffre qui donne son échelle au monde.

Quatre tests vérifient la géométrie contre elle-même : l'œil à 1,70 m à
toute inclinaison du domaine, les bornes qui empêchent la portée
d'exploser (cos → 0), et des vitesses humaines.

Point ouvert assumé : le regard ne monte pas au-dessus de l'horizontale —
on ne contemple pas les étoiles debout. Il faudrait un second modèle de
caméra (visée libre, sans point au sol) ; noté pour le lot 7.1.

## v0.28.0 — Lot 2.13 : occlusion ambiante, le terrain gagne son volume

La prairie était un aplat vert : ombrage purement diffus, aucun modelé, et
des arbres en silhouettes plates plus sombres que le sol. Ce lot donne du
volume, sans shadow mapping — trop risqué à écrire en aveugle pour ce
qu'il apporterait de plus.

Occlusion ambiante par CONCAVITÉ : la lumière du ciel arrive de tout
l'hémisphère, un fond de vallon en reçoit moins qu'une crête. Mesure
locale sans lancer de rayon — altitude du sommet contre la moyenne de ses
quatre voisins de grille — avec l'échelle de référence prise sur le PAS DE
LA GRILLE et non sur une constante métrique : un vallon de 20 m sur une
maille de 20 m occlut autant qu'un cirque de 20 km sur une maille de
20 km, et l'effet ne disparaît ni de près ni de loin. Bornes : 62 % de la
lumière au fond d'un creux, +8 % sur une crête.

L'occlusion est cuite dans l'albédo des sommets : aucun attribut nouveau,
donc format de sommet, taille de tampon et pool GPU inchangés. C'est
légitime pour une lumière AMBIANTE — elle ne dépend pas de la position du
soleil, seulement du relief.

Les plantes gagnent un dégradé vertical franc : pied à 42 % de la cime,
occlus par son propre feuillage. C'est ce qui donne du volume à une
silhouette sans épaisseur. Deux tests : dispersion de luminance (un aplat
n'en aurait aucune) et bornes de couleur tenues.

Rendu pur : génération et empreintes intactes.

## v0.27.0 — Lot 2.15 : la météo locale devient visible

La simulation savait où il pleut, quelle température il fait et quelle
saison court ; au sol, tous les climats se ressemblaient. Ce lot rend la
météo visible : pluie ou neige en particules, choisies par les données de
la cellule visée.

Toute la DÉCISION vit dans :sim et se teste — seuil sec à 250 mm/an (les
déserts restent secs), saturation à 2 200, bascule pluie/neige à 1,5 °C
sur la température DU MOMENT, saison comprise. C'est le point qui fait la
différence : la même plaine tempérée reçoit de la pluie en été et de la
neige en hiver. Le cycle des saisons devient enfin visible au sol.

Le rendu n'obéit qu'à cet état : une colonne de particules en repère
CAMÉRA — l'averse accompagne l'œil, comme dans la réalité — et SANS AUCUN
état persistant : la position de chaque particule se déduit du temps et de
son indice par un repli modulo. Rien à mettre à jour entre deux images, le
déterminisme est structurel. La neige tombe huit fois plus lentement que
la pluie et tourbillonne ; elle est plus clairsemée, des flocons rares se
voient quand une averse doit strier l'écran. Affichage sous 3 km
seulement : plus haut, on est au-dessus de l'averse et les nuages jouent
leur rôle.

Quatre tests sur la décision. Rendu pur : génération et empreintes
intactes.

## v0.26.3 — Test recalibré : acos n'est pas une façon de mesurer un angle nul

Le test mère/filles rougissait sur SON PROPRE bruit numérique. Il mesurait
l'écart tangentiel par acos(cos)×R — or près de zéro, acos amplifie
l'erreur d'arrondi en sqrt(2ε) : 13 à 38 cm au rayon terrestre, pour un
seuil de 1 cm. La végétation était saine ; la formule ne l'était pas.

Correctif : distance entre directions UNITAIRES × rayon — même angle, sans
acos, bruit de l'ordre du micromètre, six ordres de grandeur sous le
seuil. Le contrat reste identique et le seuil du centimètre est conservé,
donc rien n'est assoupli. La borne radiale de 60 m est revérifiée par le
calcul : la maille du niveau 15 fait 19 m, l'écart entre surfaces de
niveaux voisins vaut la variation du terrain sur cette distance.

Nouvelle entrée au catalogue des leçons — quatrième test mal calibré du
projet, mais le premier dont la faute est l'ARITHMÉTIQUE de la mesure :
un seuil au centimètre exige de vérifier la précision de la méthode qui
mesure, pas seulement la valeur du seuil. L'avertissement « Name shadowed:
off » est levé au passage (paramètre renommé gridOff).

## v0.26.2 — Correctif : les plantes flottaient au-dessus des tuiles lointaines

Constaté sur appareil : des plantes suspendues en l'air à moyenne distance.
Cause structurelle — le pied était posé sur le terrain continu EXACT, mais
le sol visible à distance est une tuile grossière dont la surface s'écarte
de l'exact de plusieurs mètres entre ses nœuds. Près de la caméra les deux
coïncident ; au loin, la plante lévitait au-dessus du visible.

Principe du correctif : la plante appartient à sa tuile, son pied se pose
sur la SURFACE DE LA TUILE — interpolation bilinéaire de sa propre grille
d'altitudes, au niveau de détail où elle vit. Quand la tuile change de
niveau, le pied suit la surface : le même saut que le sol sous la plante,
donc invisible. La mesure de pente reste sur le terrain fin (c'est la
pente réelle qui décide si un arbre tient).

Le test mère/filles est recalibré en conséquence : la canonicité se
vérifie au centimètre en TANGENTIEL — la position sur le treillis ne bouge
pas d'un poil — tandis que la composante radiale diffère légitimement
entre niveaux, bornée à 60 m. Reste transitoire assumé : pendant le
morphing d'une tuile, le sol glisse et la plante non — un flottement bref,
borné par l'écart mère/fille, à traiter si l'œil l'attrape.

## v0.26.1 — Correctif : le programme nuages ne liait pas sur Mali

La remontée d'erreurs du lot 0.6 a fait son travail : « L0001, uDrift du
fragment ne concorde pas avec le sommet — la précision diffère ». Le
sommet déclarait uDrift en highp par défaut, le fragment le faisait tomber
sous son precision mediump global ; Mali exige la concordance exacte, le
programme échouait à l'édition de liens et le ciel restait vide — tout le
reste tournait.

Correctif : uDrift en highp EXPLICITE côté fragment (le G77 le supporte).
L'alternative mediump aurait fait trembler la dérive par pas visibles.
Audit au passage : uDrift est le seul uniform partagé entre étages des
trois nouveaux programmes (étoiles, lune, nuages) — aucun autre cas.

## v0.26.0 — Lot 2.14 : la couche nuageuse

Une coquille sphérique à 9 km d'altitude, dont l'alpha est un bruit de
valeur calculé au fragment — pas de texture dans ce moteur, le bruit EST
la texture. Deux octaves au fragment posées sur une octave large au
sommet : la structure continentale des nuages vient du sommet, le détail
du pixel. Éclairée par le soleil : terminateur sur les nuages depuis
l'orbite, gris bleuté la nuit.

Un seul shader sert les deux modes : en descente, coquille en mètres
relatifs caméra après le terrain (le test de profondeur élimine la face
lointaine au-delà de la planète) ; au globe, coquille au rayon relatif
1 + 9 km/R, la matrice portant déjà la rotation propre. Vu de dehors on
regarde la face externe, vu de dessous la face interne : l'élagage bascule
avec l'altitude — sans quoi l'une des deux vues serait vide.

La dérive suit l'horloge du monde : à ×200, le ciel court. Budget dit
d'avance : chaque octave de fragment coûte huit sinus par pixel — si les
i/s plient sur le Mali, on retirera une octave, la constante est isolée.

Rendu pur, sans test JVM possible ; génération et empreintes intactes.

## v0.25.1 — Correctif de compilation : trois imports manquants

Une seule vraie faute : `Rng` non importé dans CelestialSky — sa signature
avait été vérifiée, pas son import, la moitié de la leçon. Toutes les
erreurs « Double vs Float » n'étaient que la cascade du type en erreur de
nextFloat(). Le renderer aurait échoué juste derrière : `CelestialSky` et
`Icosphere` y manquaient aussi.

Le filet s'étend : un contrôle d'imports (toute classe du projet utilisée
est importée ou pleinement qualifiée) a été prototypé pour cette livraison
et rejoint les passes statiques de la prochaine archive de relance, aux
côtés du contrôle des propriétés d'instance promis à la v0.23.1.

## v0.25.0 — Lot 2.12 : le ciel nocturne

Chaque monde a désormais son ciel : onze cents étoiles semées par la
graine (`ciel/etoiles` — magnitudes en u², beaucoup de poussière, peu de
phares) et une lune en orbite inclinée propre au monde (`ciel/lune`,
lunaison de 22 à 34 jours planétaires). Le champ est fixe dans le repère
monde et ramené en local par la rotation propre inverse, comme le soleil :
le ciel défile parce que la planète tourne.

La lune est une vraie sphère (icosphère niveau 2) éclairée par la
direction du soleil : LES PHASES SORTENT DE LA GÉOMÉTRIE, gratuitement —
croissant, quartier, pleine lune au fil de la lunaison, sans une ligne de
code de phase. Les étoiles sont des points additifs qui n'apparaissent que
quand le ciel s'éteint : la nuit au sol, ou en altitude quand l'atmosphère
disparaît — même rampe que le ciel lui-même.

Dessiné entre le ciel et le terrain dans la passe descente (le relief
recouvre, l'écriture de profondeur est suspendue). Mode globe : à suivre,
noté en point ouvert. Deux tests JVM sur la partie :sim — ciel unitaire,
borné, déterministe et propre au monde ; orbite unitaire, périodique à la
lunaison près, inclinaison dans les bornes.

## v0.24.0 — Le fondu de limbe : la silhouette redevient un cercle

Dernier défaut visuel consigné des captures v0.19.1 : au-delà de ~1 500 km
en mode sol, la silhouette de la planète était dessinée par les bords
droits de tuiles de niveau 3-4 — un limbe polygonal, avec sa « tuile
flottante » vue par la tranche.

Le correctif est un FONDU DE LIMBE, pas la grande bascule de moteur des
registres d'échelle (2.7) : chaque sommet de tuile compare l'angle de son
rayon de visée au nadir avec l'angle analytique de l'horizon, et se
dissout dans le disque du ciel sur les derniers 15 % du rayon angulaire.
Trois choix rendent le correctif petit :

- il emprunte le canal vFog de la brume de distance, SANS varying
  nouveau : la brume meurt à 120 km d'altitude, le fondu naît à 600 —
  les deux régimes s'excluent, le canal est libre ;
- la couleur cible est la formule EXACTE du disque du ciel (drawSky),
  recopiée : les tuiles se dissolvent dans le fond qui les remplace,
  sans couture de teinte, de jour comme de nuit ;
- montée en puissance de 600 à 1 500 km : rien ne change en dessous, où
  brume et halo font déjà le travail.

Correctif purement Android (shader + uniforms), sans test JVM possible ;
à juger sur les captures des mêmes points de vue que la v0.19.1.

## v0.23.2 — Test recalibré : le rembourrage des plantes n'est pas une normale

Un seul test rouge sur 292, et pas un vrai bug : SmoothShadingTest balaie
le tampon entier d'une tuile de niveau 10 — qui ne porte AUCUNE plante —
et butait sur le rembourrage à zéro de la nouvelle section végétation,
dont la normale nulle n'est pas unitaire. Contrat antérieur à la v0.23.0.

Recalibrage qui renforce le test au lieu de l'assouplir : tout sommet est
désormais soit de la géométrie réelle à normale unitaire sortante, soit un
rembourrage INTÉGRALEMENT nul situé dans la section des plantes — une
normale nulle dans le terrain ou les jupes reste une faute, et un sommet à
normale nulle mais position non nulle aussi. Le paramètre inutilisé
signalé en avertissement est purgé au passage.

## v0.23.1 — Correctif de compilation : TileId expose x/y, pas gx/gy

Erreur de compilation pure (aucun test n'a rougi, :core est passé) : le
code de végétation supposait des propriétés `gx`/`gy` sur TileId, qui
s'appellent `x`/`y` — la leçon v0.15.1, « lire l'API avant de l'appeler »,
enfreinte une fois de plus. Les ambiguïtés compareTo n'étaient que la
cascade des types en erreur ; les boucles du treillis passent au passage
sur des bornes entières précalculées.

Trou du filet identifié : le contrôle statique n°6 ne vérifie que les
membres d'objets compagnons, pas les propriétés d'instance — à étendre
dans la prochaine archive de relance.

## v0.23.0 — Végétation minimale : la planète s'habille

Avant-goût de la Phase 3, taillé pour ce moteur : des plantes en pure
géométrie colorée par sommet — deux quads « cerf-volant » croisés, pied
brun, houppier du biome assombri — INTÉGRÉES au maillage des tuiles avec un
budget fixe de 49 emplacements. Le pool GPU, l'élagage, le morphing et le
renderer n'ont pas changé d'une ligne.

La pièce maîtresse est le TREILLIS CANONIQUE : chaque plante du monde vit à
une case fixe d'une grille de niveau 15, et chaque tuile — quel que soit
son niveau — émet les plantes canoniques de son emprise. Même arbre, même
position, même taille à tout niveau de détail : un test le vérifie au
centimètre entre une tuile mère et ses quatre filles. Densité au sol
constante (une tuile de niveau 17 porte ~3 plantes, pas 49), aucune
explosion en descendant, aucun saut en montant.

Répartition : forêts saturées (tropicale 100 %, tempérée 90 %, boréale
80 %), savane clairsemée d'arbres, prairies et steppes en touffes, milieux
arides ou glacés nus. Ni sur l'eau, ni dans les lacs, ni au-delà de 27 %
de pente. Le pied est posé par le terrain continu : la plante touche
exactement le sol rendu, par l'invariant n°3. Tout le tirage sort d'un
hachage salé par la graine du monde : deux appareils plantent la même
forêt au brin près.

Coûts, dits d'avance : +1 176 sommets par tuile de pool (+60 %, ~39 Mo de
réserve GPU au lieu de 24), à lire au HUD. Les emplacements vides sont des
triangles dégénérés, écartés sans coût de remplissage. Rendu pur :
GENERATION_VERSION inchangé, empreintes intactes.

## v0.22.0 — Lot 2.17 a : le micro-détail du sol

La plaine rapprochée était un aplat vert : une seule échelle de moucheture
(~70 m, luminosité pure) portait tout le premier plan. Trois étages
désormais, chacun choisi pour ce que l'œil attend à son échelle :

- **Taches** (~1,3 km) : dérive de TEINTE herbe ↔ herbe sèche — le rouge
  monte, le bleu descend, des parcelles de végétation inégale ;
- **Moucheture** (~70 m) : la luminosité historique, conservée ;
- **Grain** (~9 m) : la texture du premier plan. Jamais plus fin : à cette
  fréquence, l'erreur du flottant sur la direction vaut déjà 7 % de la
  longueur d'onde.

Et la **roche des pentes** : au-delà de la pente de repos, l'herbe ne tient
plus — la couleur glisse vers un gris-brun de roche, par un seuil d'ANGLE
lu dans la normale par sommet (rien jusqu'à 14°, roche pleine à 32°, la
fourchette des pentes de repos des sols meubles). Aux niveaux grossiers,
la normale est lissée sur des kilomètres et les pentes s'effacent d'elles-
mêmes : l'orbite garde ses couleurs de biome pures, le premier plan gagne
ses affleurements — l'échelle fait le travail, aucun seuil de niveau.

Le tout est CPU, dans :sim, déterministe et testé (bornes par construction,
variation mesurée, seuils d'angle calculés, tuiles bit à bit reproductibles
aux niveaux 3 et 12). Rendu pur : GENERATION_VERSION inchangé, empreintes
intactes — le monde ne bouge pas, on le voit mieux.

## v0.21.0 — Lot 1.14 : le transport d'humidité (GENERATION_VERSION 13)

Le sommet de la Phase 1. Les bandes latitudinales provisoires du lot 1.3 ne
répartissent plus la pluie : elles ne fixent plus que le BUDGET d'eau du
monde, et le transport la distribue. Évaporation océanique selon la
température, advection par les vents du 1.13 (deux voisines au vent
pondérées, double tampon — déterminisme structurel), et trois mécanismes de
pluie : condensation modulée par les mouvements verticaux des trois
cellules, soulèvement orographique, capacité thermique de l'air
(Clausius-Clapeyron). La subsidence VENTILE en plus la couche humide.

Rien n'est imposé : l'équateur pleut parce que les alizés y convergent,
les déserts subtropicaux naissent de la subsidence, les déserts d'abri
derrière les montagnes. Validation numérique complète sur banc synthétique
(continent barré d'une cordillère, vents réels) : ITCZ ×6,5 sur les
subtropiques, second maximum tempéré, pôles secs, ombre pluviométrique ×2,
façade d'alizés ×8 sur l'intérieur. Trois leçons de calibrage consignées
dans le script — dont : retenir la pluie sans assécher fait pleuvoir quand
même, et la subsidence lue dans le vent de surface s'étale sur 30°.

La normalisation sur le budget de l'ancien modèle préserve par construction
l'équilibre global des biomes — glaces et seuils du banc d'essai. Le nombre
de passes suit la RÉSOLUTION (la longueur de transport est une distance,
pas un nombre de mailles).

Génération changée : GENERATION_VERSION 13, empreintes à re-figer — la
référence est retirée du dépôt à cette livraison, le test repasse en mode
enregistrement, on fige depuis l'artefact du prochain run vert. Risque
assumé : les tests climatiques calibrés sur l'ancienne répartition
(continentalité, couvertures) peuvent bouger — le budget conservé devrait
les tenir, la CI tranchera, et un échec éventuel se diagnostiquera comme
toujours : vrai défaut ou seuil à recalculer.

## v0.20.0 — Lot 1.13 : la circulation atmosphérique

Chaque monde a désormais ses vents de surface : alizés d'est autour de 15°,
vents d'ouest autour de 45°, vents polaires d'est vers 75°, est équatorial —
et les branches méridiennes des cellules de Hadley, Ferrel et polaire, qui
convergent vers l'équateur (la future zone de convergence intertropicale,
celle qui fera pleuvoir l'équateur au lot 1.14) et s'inversent d'un
hémisphère à l'autre. Le zonal encode déjà Coriolis : les alizés sont d'est
parce que l'air descendant vers l'équateur est dévié vers l'ouest.

Le profil est calibré contre la climatologie du vent de surface : onze
latitudes de 0° à 85°, toutes dans les tolérances, zéros de régime aux
positions terrestres (~29° et ~63°). Unités physiques, m/s. Chaque monde y
ajoute sa respiration — rotation de direction et modulation de vitesse par
un bruit à grande longueur d'onde, graine dérivée `climat/vents`.

Un huitième calque « Vents » : direction en teinte (la roue chromatique
parcourt la rose des vents), vitesse en luminosité. Les bandes zonales
sautent aux yeux, ondulées par la respiration du monde.

Flux de graine indépendant, tableaux hors empreinte : AUCUN bit des mondes
existants ne bouge, et les empreintes figées le prouvent au push —
GENERATION_VERSION reste à 12. C'est le lot 1.14, en faisant entrer ces
vents dans les précipitations, qui l'incrémentera. Cinq tests ajoutés.

## v0.19.2 — Correctifs de moyenne altitude : dents de scie et voile laiteux

Les captures instrumentées de la v0.19.1 ont permis le diagnostic. Trois
défauts distincts, deux corrigés :

**1. Le trait de côte en dents de scie (corrigé).** Le mélange de rivage
était calibré pour une pente côtière de 4 % ; or les côtes générées
franchissent le socle isostatique (+200 / −900 m) en une ou deux mailles —
pente apparente de 10 à 30 %. La frange retombait dans une seule maille et
la transition redevenait une marche, exactement comme le prédisait son
propre commentaire. Recalibrage : pente de 30 % avec saturation à 1 400 m
(le dénivelé côtier par maille est borné par le relief côtier total — une
frange linéaire aurait noyé les plateaux continentaux en turquoise).
Frange aux niveaux 5-8 : de 0,4-0,8 km à 0,7-1,4 km. Le test de contrat
est réécrit avec les nouvelles bornes, calculées.

**2. Le voile laiteux d'altitude (corrigé).** La brume de distance en
0,5/horizon voilait encore un tiers des tuiles lointaines à 400 km
d'altitude — au-dessus de l'atmosphère, où il n'y a plus d'air à traverser.
Extinction linéaire de 20 km (pleine brume) à 120 km (nulle), là où le halo
de limbe prend le relais (60-300 km). Depuis l'orbite basse, le terrain
redevient net.

**3. Le limbe polygonal des tuiles géantes (point ouvert, assumé).** À
1 500-2 000 km, la silhouette de la planète est dessinée par des tuiles de
niveau 3-4 aux bords droits — la « tuile flottante » enneigée des captures
en était une, vue par la tranche dans le voile. Le correctif de fond
appartient aux registres d'échelle (lot 2.7) : au-dessus d'un seuil, rendre
le disque planétaire par le globe haute définition et réserver les tuiles
au champ proche. Chantier notable, à décider séparément.

GENERATION_VERSION inchangé : tout est rendu, rien n'est monde.

## v0.19.1 — Instrumentation : la teinte des tuiles passe à la console

Des artefacts de tuiles apparaissent à moyenne altitude en mode sol — grand
quadrilatère pâle en travers de la vue, aplat sombre, couture visible — et
s'effacent près du terrain. Diagnostic préliminaire : le chemin des tuiles
n'a pas été touché par la v0.19.0 (le globe haute définition a son tampon
dédié, hors du pool des tuiles ; la passe descente ne dessine pas le globe)
et la profondeur des jupes est kilométrique au pire — le mur géant vient
d'ailleurs. Ces défauts sont donc très probablement antérieurs, révélés par
les tournées au joystick à des altitudes intermédiaires peu visitées.

Plutôt qu'un correctif à l'aveugle — la doctrine du projet est « le HUD de
debug avant la fonctionnalité » — ce lot rend le diagnostic possible sur
appareil : la teinte des tuiles par niveau de subdivision, qui existait
derrière une constante de compilation, devient la commande console
« teinte [on|off] ». Un défaut teinté devient lisible : sa tuile, son
niveau, sa frontière, au lieu d'une tache anonyme.

Grammaire testée en CI comme le reste de la console.

## v0.19.0 — Globe haute définition

Le mode par défaut — le globe contemplatif — était le maillon graphique
faible : dessiné sur la grille de simulation elle-même, 10 242 sommets, des
côtes en escalier et des facettes de 250 km, quand le mode sol affichait
déjà un terrain fin. Le lot corrige l'asymétrie sans toucher au style.

Le principe est une récolte directe de l'invariant n°3 : le terrain est une
fonction CONTINUE, exacte aux sommets de la grille. La géométrie du globe
est donc évaluée un niveau plus fin — quatre fois plus de triangles, ~82 000
— sur cette fonction : le trait de côte passe là où le terrain croise
réellement le niveau de la mer, plus au bord des cellules. Les facettes
demeurent (c'est le style assumé du globe), quatre fois plus fines.

Les COULEURS restent par cellule, au plus proche voisin : les données
climatiques n'existent qu'à la résolution de la grille, les interpoler
peindrait une précision mensongère. Tous les calques en profitent —
Biomes, Température, Pluie, Plaques — avec un cas de couture traité : un
sommet fin sous le niveau de la mer dont la cellule la plus proche est
terrestre reçoit la mer côtière, pas des pixels bruns dans l'eau.

Le raffinement (~40 000 évaluations du terrain continu) est calculé UNE
fois par monde, sur le fil de travail ; le changement de calque le
réutilise et ne refait que la passe de couleurs. Coûts attendus, à lire
dans le HUD : « gen » +0,5 à 1 s, « maille » du même ordre qu'avant,
tampon de sommets 9,8 Mo (plafonné : le niveau 7 en pèserait 39).

Cinq tests ajoutés, dont la coïncidence bit à bit entre grille et
raffinement sur les sommets partagés — l'invariant n°3 vu du globe — et la
cohérence mer/terre du trait de côte fin. GENERATION_VERSION inchangé :
le rendu s'affine, le monde ne bouge pas d'un bit.

## v0.18.0 — Lot 1.12 : l'insolation et les saisons thermiques

Ouverture du chantier atmosphérique (1.12–1.14), mené en trois livraisons
avec UN SEUL incrément de génération, au 1.14 : celle-ci n'en a pas besoin.

Le monde généré reste une moyenne annuelle — les empreintes figées le
prouvent à chaque poussée — et la saison devient une modulation pure,
évaluée à la volée : T(cellule, t) = moyenne + A · saison(t). L'amplitude A
est calibrée sur dix-sept stations terrestres, de Singapour (±1 °C) à
Iakoutsk (±29 °C) : erreur médiane 13 %, toutes dans un facteur 2. Le
calibrage a corrigé une erreur de forme au passage : l'excursion
d'insolation croît en sin φ dès les basses latitudes — la première mouture
en sin²φ écrasait la bande subtropicale.

L'amplitude suit sin(inclinaison) : une planète droite n'a pas de saisons,
une planète couchée en a de féroces — le curseur d'inclinaison du lot 1.18
prend enfin tout son sens. Le pic thermique retarde sur le solstice, 27
jours au cœur des continents, 55 en mer, interpolé par la continentalité —
que le générateur conserve désormais dans PlanetData (dérivée du relief,
sans aléa propre : hors empreinte par construction).

Le HUD affiche les extrêmes du jour sous la ligne climat : « aujourd'hui
−52,1 … 31,4 °C », recalculés seulement au changement de jour planétaire.
À ×200, on regarde l'hiver ronger l'hémisphère nord puis refluer.

Sept tests ajoutés. Le calendrier est vérifié contre le pic de déclinaison
MESURÉ, pas contre un jour codé en dur : la convention de phase de
WorldTime peut changer sans casser le test.

Point ouvert, assumé : la couleur du calque Température reste annuelle —
teinter les maillages au fil des saisons demandera une reconstruction
incrémentale, à traiter avec le morphing du lot 2.4.

## v0.17.2 — Correctif : le joystick s'inversait près du sol

Le manche allait dans le bon sens en altitude et à rebours au ras du sol.
Ce n'était pas une erreur de signe de plus : le diagnostic montre que la
direction-monde du « haut de l'écran » n'est pas la même dans les deux
régimes de caméra. À forte inclinaison, la géométrie impose que le haut de
l'écran soit le cap — l'horizon qu'on regarde ; en vue plongeante, la
convention d'écran validée sur appareil en v0.8.4 lui est opposée. Le
joystick v0.17.1, câblé sur la sémantique écran de pan(), ne pouvait être
juste que dans un régime.

Le correctif ne choisit pas un camp et n'ajoute pas de signe conditionnel,
qui aurait consacré l'incohérence : la base du manche est désormais
PROJETÉE depuis les axes que le rendu utilise réellement — « pousser en
haut » suit up() de la caméra projeté sur le plan tangent au sol,
« pousser à droite » suit right(), tangent par construction. C'est le
même vecteur que celui qui dessine les pixels : juste à toute inclinaison,
par construction. up() et forward() couvrent mutuellement leurs
dégénérescences (horizon rasant et vue plongeante), et la vitesse garde
l'échelle du doigt, mètres par pixel à la distance courante.

Reste ouverte, notée pour plus tard : l'incohérence de convention d'écran
entre régimes, qui appartient à pan()/up() et non au joystick. Elle
resurgira au lot 7.1 (refonte des contrôles tactiles) ; le glissement au
doigt près du sol mérite d'être ré-examiné à cette occasion.

## v0.17.1 — Joystick de déplacement en mode sol

Un joystick virtuel apparaît en bas à gauche dès qu'on passe en mode sol, et
disparaît au retour au globe. Le doigt sur le terrain continue de fonctionner
comme avant : les deux modes de déplacement coexistent.

Choix de conception :

- Le joystick passe par `cam.pan()`, la même mécanique que le glissement du
  doigt : la vitesse reste proportionnelle à l'altitude sans aucun code
  dédié — plein manche équivaut à un glissement soutenu de 900 px/s.
- Sa boucle de déplacement tourne à 16 ms mais ne vit que pendant que le
  manche est engagé : zéro coût au repos, et la boucle UI à 100 ms n'est pas
  touchée.
- La vue consomme ses événements tactiles : un doigt sur le joystick ne fait
  jamais tourner le globe derrière.
- Convention « pousser en haut = avancer », dérivée du contrat de pan() ;
  l'historique (v0.8.4) prouve que ces signes ne se devinent pas de salon —
  si le sol part à rebours, l'inversion tient en une ligne, commentée.

Fonctionnalité purement Android, sans test JVM possible.

## v0.17.0 — Lot 1.18 b : le curseur d'activité tectonique

Un treizième curseur, de 0 à 2 : il multiplie l'amplitude de tout le relief
d'origine tectonique — chaînes, cordillères, fosses, arcs, dorsales, rifts,
et désormais aussi les édifices de points chauds, car un monde à activité
nulle doit être mort, Hawaï comprise. Le socle isostatique et le bruit
d'habillage n'en dépendent pas : à zéro, il reste des continents plats qui
flottent ; à deux, des orogenèses doublées, bornées par la compression
finale qui garantit les plafonds par construction.

Le point central du lot : **GENERATION_VERSION reste à 12, empreintes
intactes**, contrairement à ce qui était annoncé. Le curseur agit APRÈS les
tirages aléatoires — aucun flux n'est consommé différemment — et à sa valeur
d'usine la multiplication par 1,0 est exacte en arithmétique IEEE : aucune
recette existante ne change d'un bit. Ce pari est verrouillé par un test au
bit près, dont le commentaire est explicite : s'il casse un jour, la réponse
est d'incrémenter la version, pas d'assouplir le test. C'est aussi pourquoi
le NOMBRE de plaques n'est pas paramétré : son tirage consomme le flux, le
paramétrer aurait tout décalé.

Autres verrous : à activité nulle, le champ structural ne contient exactement
que ±socle (tout terme qui échapperait au curseur ferait échouer le test) ;
l'étendue du relief croît strictement avec l'activité ; format de sauvegarde
3 avec migration testée depuis les flux v1 et v2.

## v0.16.2 — Interface : deux tiroirs latéraux rétractables

Les barres empilées de la v0.16.1 mangeaient le bas de l'écran. À la place,
deux tiroirs coulissants : les calques s'ouvrent depuis la gauche, le temps
et le monde depuis la droite. Fermés, seules deux poignées en chevron
subsistent aux coins inférieurs — l'écran redevient une fenêtre sur la
planète, ce qui est la vocation du mode contemplatif.

Détails qui comptent :

- Les barres deviennent des colonnes mais gardent leurs enfants dans le même
  ordre : `refreshButtonStates()` indexe les boutons par position, et cette
  invariance lui évite toute retouche.
- La position fermée est recalée à chaque layout, pas mesurée une fois : une
  rotation d'écran change la largeur des panneaux, et un tiroir replié doit
  le rester exactement.
- La cible du coulissement vient de la largeur mesurée, jamais d'une
  constante : « Sol » devient « Globe », la police d'accessibilité grossit,
  et une largeur codée en dur finirait par laisser dépasser un bord.

Correctif purement Android, sans test JVM possible.

## v0.16.1 — Correctif d'interface : les barres se chevauchaient

Le bouton « Régl. » de la v0.16.0 a fait dépasser la largeur cumulée des deux
barres du bas, ancrées l'une à gauche et l'autre à droite de la même ligne :
« Eaux » passait sous « ×1 ». Les barres sont désormais empilées — temps et
monde sur la rangée du haut à droite, calques en bas à gauche — et ne peuvent
plus se chevaucher, quels que soient l'écran, l'orientation et les boutons à
venir (console du lot 0.7, notamment).

Correctif purement Android : pas de test JVM possible, la CI vérifie la
compilation et la non-régression du reste.

## v0.16.0 — Lot 1.18 : l'éditeur de paramètres en jeu

Douze curseurs sur la recette de la planète — fraction océanique, altitudes,
climat complet, précipitations, inclinaison, exagération du relief,
subdivisions — avec régénération immédiate du monde courant. La feuille de
route promettait que ce lot ferait gagner des semaines sur toutes les phases
suivantes : chaque réglage climatique futur pourra désormais s'essayer à la
main avant d'être calibré.

Tout le contenu de l'éditeur (bornes, pas, libellés, écriture) vit dans
`ParamEditor` en Kotlin pur, couvert par des tests génériques : ajouter un
paramètre = ajouter une ligne au registre, les tests suivent d'eux-mêmes.
La couche Android ne fait que dessiner des curseurs.

Trois points durcis d'emblée :

1. **Piège du pas flottant.** La grille d'un curseur ne retombe pas toujours
   au bit près sur la valeur d'usine (23,4° devient 23,400002 après
   aller-retour par l'index). L'interface ne réécrit donc QUE les curseurs
   réellement déplacés — sinon, ouvrir le panneau et régénérer sans rien
   toucher aurait suffi à changer le monde.

2. **Bug latent de sauvegarde, corrigé avant d'exploser.** Le format 1
   n'enregistrait ni `oceanThermalInertia` ni `continentalityC` : invisible
   tant qu'ils n'étaient pas éditables, fatal après — une valeur réglée
   serait revenue à l'usine au redémarrage. Format 2 avec migration : les
   sauvegardes v1 se relisent, les nouveaux champs prennent leurs valeurs
   d'usine, qui sont celles de leur génération.

3. **`affectsGeneration` documenté et testé.** L'inclinaison axiale pilote
   les saisons et le ciel, pas le climat moyen annuel : un test le fige, et
   son commentaire prévient qu'au lot 1.12 (insolation) il faudra l'inverser.

Deux absents délibérés, pour que ce lot ne change pas un bit des mondes
existants (GENERATION_VERSION reste à 12, empreintes intactes) : le rayon
planétaire, calibré partout pour 6 371 km, et l'activité tectonique, qui
n'existe pas encore comme paramètre — ce sera le lot 1.18 b, avec son
incrément de version de génération.

## v0.15.3 — Correctif de test : mon propre test était mal calibré

Le run de la v0.15.2 a validé le correctif climatique — Gaia 16,0 → 15,7 % de
glaces, moyenne 13,9 %, tous les tests de climat et d'empreinte verts — mais le
**nouveau** test d'inversion subpolaire a échoué sur Kaleth : façades ouest à
−12,8 °C contre est à −11,6 °C.

Ce n'est pas le modèle. Un Monte Carlo sur 20 000 tirages
(`validation/test_inversion_fiabilite.py`) montre que la comparaison naïve des
moyennes de façades échoue par pur hasard d'échantillonnage sur **27 % des
mondes** : dans la bande 54°–69°, la latitude pèse jusqu'à 14 °C et l'altitude
3 °C, quand le signal des courants — dilué par les sommets intérieurs où
`reach ≈ 0` — n'en pèse que 1 à 3. Troisième occurrence de la même leçon : un
test mal calibré coûte autant qu'un bug.

Correctif en deux étages :

1. **`gyreStrength` extraite en fonction de compagnie** et testée directement :
   signe subtropical à 30°, signe subpolaire à 60°, front nul à 43°, bornes.
   Une fonction pure se teste sans bruit géographique — c'est ce test qui
   verrouille l'inversion. L'expression est reprise opération pour opération :
   la génération ne change pas d'un bit, `GENERATION_VERSION` reste à 12.

2. **Tests de façades réécrits** avec la statistique validée par le Monte
   Carlo : sommets côtiers (< 500 km) et bas (< 500 m) seulement, stratification
   par sous-bandes de latitude de 5°, cumul des trois mondes. Zéro échec sur
   20 000 tirages. Le test subtropical reçoit la même armature — il aurait fini
   par tomber dans le même piège.

Les empreintes de référence sont figées dans cette livraison, depuis
l'artefact du run 4d56a8e : le prochain run vert prouve à la fois le
déterminisme et la neutralité binaire du refactor.

## v0.15.2 — Correctif climatique : les gyres subpolaires existent

Le premier run CI de la v0.15.1 a mis trois tests au rouge, pour trois causes
distinctes.

### 1. Le motif est/ouest était faux au-delà de 45° (vrai bug)

Le lot 1.15 appliquait « côte est chaude, côte ouest froide » à toutes les
latitudes. Or son propre calibrage le contredisait : Bergen (60°, côte
**ouest**) affiche +7,6 °C quand Nuuk gèle. Au-delà du front des gyres
(~43°), les gyres **subpolaires** inversent le motif : dérive nord-atlantique
chaude sur les façades ouest, Labrador et Oyashio froids sur les façades est.
Correctif : `strength = sin(2·|lat|) · tanh((43° − |lat|) / 8,5°)`. Sur six
couples terrestres, le signe passe de 3/6 à 6/6 correct
(`validation/gyres_subpolaires.py`).

Conséquence directe sur les glaces : la v0.15.1 refroidissait de 5 °C des
côtes subpolaires que la Terre réchauffe. Et l'argument « l'effet est
symétrique donc la glace ne bouge pas » était faux : le gel est un effet de
**seuil** — une anomalie froide crée de la glace qu'une anomalie chaude
ailleurs ne fait pas fondre. Gaia est monté à 16 % de surface glacée.
L'inversion subpolaire réduit de 22 % l'énergie thermique injectée.

### 2. L'effet des courants ignorait l'altitude (vrai bug)

Un courant marin tempère les basses terres côtières, pas un sommet à 2 400 m.
L'effet est désormais atténué en `exp(−alt / 1500 m)`. C'est ce qui gelait le
sommet 1249 de Gaia (7,6 °C sous les tropiques).

### 3. Le test des tropiques passait par chance (test mal calibré)

Le seuil « > 8 °C sous 2 500 m » n'a jamais été garanti : le pire cas
calculable du modèle à 2 500 m est +3,6 °C, courants ou pas. Le test tenait
uniquement parce qu'aucun sommet tiré n'avait cumulé tous les effets froids.
Nouveau couple, calculé : > 8 °C sous **1 500 m**, borne garantie +9,4 °C,
marge 1,4 °C.

### Empreintes

La génération change (GENERATION_VERSION 12) : la référence
`src/test/resources/fingerprints.txt` — qui provenait de toute façon d'un
artefact périmé, voir ci-dessous — est retirée. Le test repasse en mode
enregistrement ; on figera les empreintes depuis l'artefact du prochain run
vert.

## v0.15.1 — Correctif : la latitude n'était pas là où je la croyais

Erreur de compilation. J'ai appelé `Vec3.latitude`, puis `Geodesy.latitude` —
deux suppositions successives — alors que la fonction vit dans `Sphere`.
`Geodesy` en possède bien une, mais pour des `Vec3d` : une erreur de **type**,
pas d'existence.

La parade n'est pas un outil, c'est une discipline : lire l'API avant de
l'appeler. Une heuristique textuelle ne distingue pas `latitude(Vec3)` de
`latitude(Vec3d)`, et prétendre le contraire donnerait une fausse sécurité.

Une septième passe de contrôle statique est tout de même ajoutée — elle
détecte les appels à un membre **absent** d'un `object`, ce qui reste une
classe d'erreurs réelle, même si elle n'aurait pas vu celle-ci. Les
constructeurs de classes imbriquées en sont exclus, sans quoi elle signalait
deux cas légitimes.

## v0.15.0 — Lot 1.15 : les courants océaniques

Jusqu'ici, la température d'un point ne dépendait que de sa latitude, de son
altitude et de sa distance à la mer. Deux côtes de même latitude, l'une à
l'est d'un continent et l'autre à l'ouest, étaient donc identiques — alors
que sur Terre elles diffèrent de trois à neuf degrés.

### Pas besoin d'identifier les bassins

Les gyres subtropicaux ramènent de l'eau équatoriale le long du bord ouest de
chaque bassin et de l'eau polaire le long du bord est. Vu depuis la terre,
cela signifie qu'une côte **orientale** de continent est baignée par un
courant chaud — Gulf Stream, Kuroshio, courant du Brésil — et une côte
**occidentale** par un courant froid — Californie, Canaries, Benguela.
Savoir de quel côté est la mer suffit donc, et le BFS qui calculait déjà la
distance à l'océan propage sans surcoût le sommet marin le plus proche.

Amplitude de six degrés, calibrée sur les couples terrestres à latitude
comparable, modulée par sin(2·|lat|) — maximale vers 45°, nulle à l'équateur
et aux pôles — et par une portée de 450 km vers l'intérieur.

### Un bug attrapé par la simulation, pas par un test

La première version employait sin(2·lat) sans valeur absolue. Le sinus change
de signe au sud de l'équateur : le transport s'y inversait, et les côtes
orientales de l'hémisphère sud devenaient froides alors que le courant du
Brésil est chaud comme le Kuroshio. Une simulation de contrôle a montré que
**seule la moitié des cas allait dans le bon sens** ; avec la valeur absolue,
la totalité, pour un contraste moyen de 6,2 °C entre façades.

Aucun test JVM n'aurait signalé ce défaut : il aurait produit un climat
cohérent, simplement inversé sur la moitié de la planète.

Quatre tests : neutralité thermique planétaire, contraste est/ouest effectif,
déterminisme, et glaces dans leurs bornes.

`GENERATION_VERSION` passe à 11 — le climat change, donc les biomes.
**Les empreintes seront à re-figer**, comme annoncé.

## v0.14.1 — La plaque du panache, publiée plutôt que redevinée

Deux rouges sur la v0.14.0, de natures opposées.

**Le test d'alignement** annonçait une chaîne à 121° du mouvement — ni 0° ni
180°, donc pas une inversion de signe : le test et le champ n'identifiaient
pas la même plaque. Le champ prend celle du sommet de grille le plus proche,
le test celle dont la graine de Voronoï est la plus proche ; près d'une
frontière, les deux diffèrent.

Le champ **publie** désormais la plaque de chaque panache. Une seule source
pour une seule vérité : redeviner une donnée que le code possède déjà, c'est
créer deux réponses à la même question, et cette divergence-là ne pouvait
qu'apparaître. Vérifié par simulation : avec la bonne plaque, l'alignement
est de 0,99 au minimum sur deux cents essais, très au-dessus du seuil.

**Les empreintes de référence** ne correspondent plus, et c'est **attendu** :
les volcans modifient le relief, `GENERATION_VERSION` est passé à 10. Elles
sont à re-figer depuis un run vert, comme après le lot 1.6.

## v0.14.0 — Lot 1.7 : les points chauds

Retour à la Phase 1. Un point chaud est un panache **fixe** dans le manteau ;
c'est la plaque qui défile au-dessus. Chaque édifice percé est donc emporté
par elle, et le suivant naît en amont — d'où ces chaînes d'îles alignées dont
l'âge croît avec la distance, comme la chaîne hawaïenne.

Rien n'est dessiné : la tectonique fournissait déjà l'axe de rotation et la
vitesse de chaque plaque, et il suffit de **remonter** ce mouvement pour
placer les édifices successifs. Un panache sous une plaque rapide produit une
longue traîne d'îles espacées ; sous une plaque lente, un massif compact. Le
même panache donnerait donc une chaîne différente selon la plaque qu'il perce.

Calibrage mesuré contre la Terre : 8 à 20 panaches par monde, douze édifices
par chaîne espacés de 20 à 100 km selon la vitesse de la plaque — soit 240 à
1 200 km de long —, édifice actif à 2 600 m décroissant d'un facteur e tous
les quatre rangs.

Sur les bornes, la leçon des lots 0.9.x est appliquée d'emblée : le pire
empilement (socle + collision + volcan + bruit) atteindrait 9 100 m, mais il
n'y a aucun calibrage à ajuster — `softLimit` comprime l'ensemble sous le
plafond planétaire, par construction.

Le calque **Plaques** montre les édifices en orangé : ils traversent les
frontières sans égard pour elles, ce qui distingue au premier coup d'œil une
île de point chaud d'un arc insulaire.

Cinq tests, dont le central : la direction de la chaîne doit suivre la
**vitesse de la plaque** au panache, sans quoi l'alignement serait décoratif
au lieu d'être causé. `GENERATION_VERSION` passe à 10.

## v0.13.3 — Correctif : une substitution trop large

Erreur de compilation. En simplifiant la sortie de `waterColor`, ma
substitution automatique a aussi frappé la fin de `colorFor`, qui écrit dans
d'autres tableaux : la fonction se retrouvait à écrire dans un `out` qu'elle
ne connaît pas.

C'est exactement le risque documenté dans l'état du projet — « une
substitution automatique qui ne trouve pas son motif échoue en silence » —,
ici sous sa forme inverse : un motif trouvé **deux fois** quand on n'en
visait qu'une.

### Une sixième passe de contrôle

`tableau[i] = x` où `tableau` n'est ni paramètre, ni local, ni champ est une
erreur de compilation certaine, et aucune de mes cinq passes ne pouvait la
voir. La nouvelle passe unit les portées de toutes les fonctions englobant
l'écriture — sans quoi elle signale à tort chaque fonction imbriquée, ce que
la première version faisait sur quatre cas légitimes.

Vérifiée dans les deux sens : silencieuse sur le code correct, et elle
désigne `colorFor() écrit dans 'out'` dès qu'on réintroduit le défaut.

## v0.13.2 — Lot 2.9b : la frange de rivage

Les côtes se dessinaient encore en marches d'escalier vues de loin. C'était
le dernier basculement **par seuil** d'un rendu devenu partout continu :
couleurs, normales, altitudes s'interpolent, mais le passage terre/eau
tombait d'un coup au franchissement du niveau de la mer. En vue orbitale, où
une maille couvre des kilomètres, la transition se produisait donc dans une
seule maille — d'où la marche.

Terre et eau se mélangent désormais sur une frange dont la largeur suit le
niveau de tuile : **3 km au niveau 4, 195 m au niveau 8, deux mètres à partir
du niveau 16**. Assez large de loin pour effacer l'escalier, assez fine de
près pour qu'une plage reste franche. La formule vient d'une mesure : la
frange doit couvrir la variation d'altitude d'une maille sur une côte de
pente typique, faute de quoi la transition retombe dans une maille et
redevient une marche. Vérifié par simulation avant écriture — la couleur
glisse sans saut à tous les niveaux testés.

### Nettoyage au passage

`waterColor` écrivait ses trois canaux dans trois tableaux distincts, ce qui
la rendait inutilisable pour le mélange de rivage. Elle rend maintenant un
seul tableau de trois, avec des tampons de travail par fil — allouer trois
flottants par sommet ferait un demi-million d'allocations par seconde en
descente, exactement ce que le lot B0 avait supprimé.

Trois tests sur la frange : elle rétrécit quand le niveau s'affine, elle
couvre une maille de loin, elle reste fine de près.

## v0.13.1 — Le test de morphing cherchait en pleine mer

Test mal ciblé, pas un défaut du morphing. Il examinait une tuile désignée
par des indices arbitraires — autant tirer au sort sur une planète couverte
aux deux tiers d'océan, où l'écart de morphing est nul par conception. D'où
le message « aucun sommet morphé ».

Les tests partent désormais du sommet de grille le plus élevé du monde et
descendent jusqu'à la tuile qui le contient. Vérifié par simulation sur un
relief à l'échelle d'une tuile de niveau 14 : l'écart médian vaut 1,65 m,
largement détectable.

Leçon voisine de celle des lots précédents : un test qui échantillonne au
hasard sur une sphère majoritairement océanique ne mesure pas ce qu'il croit.
Comme pour l'incision — qui cherchait des vallées loin des fleuves — il faut
**viser l'endroit où l'effet existe**.

## v0.13.0 — Lot 2.4 : le morphing entre niveaux

Dernier artefact structurel du rendu adaptatif : quand une tuile bascule de
niveau, sa géométrie sautait d'un coup. Chaque sommet porte désormais
l'altitude qu'il aurait au niveau parent, et le shader interpole vers elle à
mesure que la caméra s'éloigne. À l'instant de la bascule, les deux maillages
coïncident exactement — le ressaut disparaît.

### Un flottant, pas trois

Le morphing classique interpolerait la position entière du sommet : trois
flottants de plus, 30 % de mémoire GPU, et la capacité du pool tombant de 328
à 252 tuiles alors que la descente en demande jusqu'à 480. Or ce déplacement
est presque purement **radial** — les sommets pairs coïncident déjà avec le
parent, et l'écart tangentiel des impairs vaut la sagitta de la corde, un
micromètre au niveau 14. Une seule valeur d'altitude suffit donc, et le
surcoût tombe à 10 %.

Fenêtre d'interpolation : les trente derniers pour cent avant la bascule. La
géométrie fine reste vraie le reste du temps, ce qui compte de près.

### Un défaut découvert au passage

Le lot 2.4 a mis au jour un oubli du lot précédent : les **normales lissées
étaient calculées à chaque tuile et jamais utilisées** — l'émission
continuait de poser celles des facettes. Le lissage constaté à l'écran ne
venait que de l'interpolation des couleurs. C'est corrigé, et le terrain
proche devrait s'adoucir nettement.

Mon audit de livraison vérifiait que le calcul existait, pas qu'il servait :
le piège des constantes orphelines, transposé aux variables locales. Une
**cinquième passe de contrôle statique** détecte désormais tout tableau
rempli puis jamais lu.

Trois tests sur la donnée de morphing : les sommets pairs ne bougent pas,
l'écart reste borné par le relief, et la mer ne morphe jamais — l'onduler au
gré des bascules serait pire que le défaut corrigé.

## v0.12.3 — Correctif de compilation : une variable hors de sa portée

Premier échec de **compilation** du projet, et il tenait à une inattention :
la phase de la houle lisait `dt`, variable locale à `onDrawFrame`, depuis
`drawDescent` où elle n'existe pas. Le calcul est remonté là où le temps de
trame vit ; l'uniform, lui, reste au moment du dessin.

Les 229 tests de simulation étaient verts sur ce même commit : seul le module
Android ne compilait pas.

### Une passe de contrôle en plus

Mes trois contrôles statiques — équilibrage, références orphelines, audit des
substitutions — ne pouvaient pas voir ce défaut : le premier ne compte que
des accolades, le deuxième ne regarde que les constantes en majuscules. Une
quatrième passe vérifie désormais la **portée des variables locales** : tout
identifiant lu dans une fonction sans y être déclaré, ni paramètre, ni champ
de la classe. Faute d'analyseur syntaxique, elle procède par heuristique et
demande un vocabulaire de mots connus, mais elle attrape la classe d'erreurs
qui vient de coûter un aller-retour.

## v0.12.2 — Le test de l'eau mesurait deux effets à la fois

Test mal conçu, pas un défaut du rendu. La monotonie vérifiée — la couleur
s'éloigne du fond quand la profondeur croît — n'est vraie que **hors de la
frange d'écume** : sous un mètre et demi, l'écume éclaircit fortement la
couleur, si bien que la distance au fond y est déjà grande et peut décroître
en s'enfonçant.

L'échantillonnage commence désormais au-delà de la frange, et l'écume garde
sa vérification propre, juste après.

C'est la deuxième fois que je commets cette erreur — `MicroReliefTest`
mesurait de la même façon trois effets superposés après l'arrivée des vallées.
Règle retenue : **un test par effet, sur un domaine où les autres sont
inactifs.**

## v0.12.1 — Lot 2.9 : l'océan

L'eau couvre deux tiers de chaque monde et restait rendue comme un plan de
terrain colorié. Elle devient de l'eau.

### La couleur, calculée au maillage

Le dégradé bathymétrique cède la place à une atténuation de Beer-Lambert
entre la couleur du **fond** — celle du biome sous-marin — et celle de la
colonne d'eau. Coefficient 0,09 par mètre, calibré sur l'eau de mer claire :
le fond reste visible à moitié vers huit mètres, au quart à quinze,
disparaît au-delà de quarante. Un haut-fond de sable vire donc au turquoise
et une fosse au bleu nuit, continûment. L'écume s'ajoute sous un mètre et
demi de fond et s'évanouit à six : une frange qui suit le rivage au lieu de
blanchir des kilomètres de littoral. Les lacs suivent la même physique, avec
la terre pour fond.

### La houle, calculée au sommet

Deux trains d'ondes croisés (80 et 53 m de longueur d'onde) déplacent la
surface le long de la verticale locale et **inclinent la normale** — c'est
cette inclinaison, plus que le déplacement, qui rend la mer vivante : le
reflet du soleil se brise en scintillement au lieu de former une tache
unique. Dérivées analytiques, pas de différences finies.

Trois précautions : la phase avance avec le temps réel et non le temps simulé
(une mer figée en pause donnerait une photographie, et à ×200 un
clignotement) ; l'amplitude s'annule près du rivage, pour qu'aucune vague ne
déplace le trait de côte par rapport à la grille ; et elle s'annule aussi sur
les tuiles grossières, où une houle de 80 m échantillonnée tous les 150 m
deviendrait du bruit.

Trois tests sur la formule de mélange : la couleur s'éloigne du fond quand la
profondeur croît, l'écume ne paraît qu'au rivage, les composantes restent
bornées.

## v0.12.0 — Fin du pavage : normales lissées, couleurs interpolées

Deux défauts d'apparence signalés à l'essai, deux causes distinctes, la même
racine : le rendu montrait la structure de ses données au lieu du paysage.

### Les losanges au sol

Chaque triangle portait sa propre normale — l'ombrage plat du parti pris
low-poly d'origine. Au ras du sol, cela se lisait comme de la pixellisation.
Les normales sont désormais calculées **par sommet**, en différences centrées
sur la grille de la tuile.

Pour que cela reste continu d'une tuile à l'autre, la grille est **étendue
d'un anneau** : les sommets supplémentaires ne produisent ni triangle ni
jupe, ils fournissent seulement les voisins manquants au calcul des normales
de bord. Comme leurs positions viennent des indices globaux, elles sont
identiques bit à bit à celles de la tuile d'à côté : la continuité est
structurelle, pas approchée. Coût : 25 % de sommets calculés en plus.

### Les hexagones au globe

La couleur était copiée depuis le sommet de grille le plus proche, ce qui
dessinait les cellules de Voronoï de l'icosphère — des polygones de 115 km.
Elle est maintenant **interpolée** entre les trois sommets du triangle, via
un `sample3` qui localise une seule fois pour les trois canaux. Le biome
lui-même reste au plus proche voisin : une catégorie ne s'interpole pas, sa
couleur si.

Trois tests : continuité des normales à travers un bord de tuile (mesurée sur
les sommets partagés de deux tuiles voisines), normales unitaires et
tournées vers l'extérieur, couleur strictement intermédiaire à une frontière
de biomes — ce qu'un plus proche voisin ne peut produire.

## v0.11.4 — Le débit interpolé écrête les pics

Le message du test disait **0,14 %** : le réseau avait presque disparu, il
n'avait pas débordé. J'avais supposé l'inverse et j'aurais corrigé dans le
mauvais sens — attendre le chiffre exact aura évité un aller-retour de plus.

La cause : le débit consulté par l'incision est **interpolé** entre les
sommets de la grille, ce qui écrête fortement les pics. Un fleuve drainant
400 cellules ne garde cette valeur qu'au sommet exact et retombe vers 1
quelques kilomètres plus loin. Un plancher à 8 ne laissait donc survivre que
des points isolés. Plancher ramené à 3, référence de 400 à 120.

### Le test change de nature

La couverture exacte du réseau est un réglage **esthétique**, sensible au
calibrage et à la variance entre mondes ; deux allers-retours s'y sont usés,
une fois pour trop, une fois pour trop peu. Le test ne vérifie donc plus
qu'une borne haute — le réseau ne recouvre pas tout — et laisse la mesure qui
a du sens à l'assertion suivante : le creusement doit dépasser dix mètres
près des sommets les plus drainés. Une propriété, plutôt qu'un réglage.

`GENERATION_VERSION` passe à 9.

## v0.11.3 — Les vallées calibrées sur une distribution mesurée

Le test avait raison de rougir, et il désignait un vrai défaut de calibrage.

J'avais documenté `Noise.ridged` comme « massé vers le bas, médiane ~0,30 » —
**sans jamais le mesurer**. Sa médiane réelle est **0,46**, et 70 % des points
dépassaient le seuil de 0,35 prévu pour en qualifier un sur quatre. Le tracé
couvrait donc la moitié du globe : ce n'était pas un réseau de vallées, mais
une ondulation générale de quelques mètres.

- Seuil porté à **0,62**, calibré sur la distribution mesurée.
- **Plancher de débit** (8 cellules drainées) : une ligne de crête ne draine
  qu'elle-même et ne doit rien creuser. Il manquait, et c'est lui qui donnait
  un creusement partout.
- Couverture résultante : environ 2 % du sol, avec des creusements jusqu'à
  120 m sur les axes principaux.
- La documentation de `Noise.ridged` porte désormais les valeurs mesurées, et
  non supposées.

C'est la troisième fois qu'une hypothèse non vérifiée sur une distribution de
bruit coûte un correctif (plage de `fbm` en v0.8.2, arithmétique signée en
v0.9.7, médiane de `ridged` ici). La règle est désormais : **aucune constante
calibrée sur une distribution sans avoir échantillonné cette distribution.**

`GENERATION_VERSION` passe à 8.

## v0.11.2 — Deux tests devenus aveugles

Premiers rouges après le retour de GitHub Actions, et ni l'un ni l'autre
n'est un défaut du code : ce sont deux tests que les lots précédents ont
rendus faux.

**`MicroReliefTest`** comparait la surface rendue au champ de base et exigeait
un écart borné par l'amplitude du micro-relief. Depuis l'incision (−140 m au
plus) et les lacs, cette surface porte trois effets : le test en mesurait
trois en croyant n'en mesurer qu'un. Il écarte désormais explicitement les
points sous vallée ou sous lac, et vérifie qu'il lui reste assez
d'échantillons pour conclure.

**`ValleyIncisionTest`** cherchait le creusement maximal par tirage
**uniforme**. Or les vallées profondes n'existent que sur les rares cellules
à fort débit : la probabilité d'en toucher une au hasard est infime, et le
test échouait pour cette seule raison. Il visite maintenant les soixante
sommets les plus drainés de la grille et leurs alentours. Vérifié par
simulation avant correction : autour d'un tel sommet, un tiers des points
dépassent dix mètres de creusement — la recherche ciblée aboutit avec quasi-
certitude, là où l'uniforme échouait presque toujours.

Un avertissement de compilation nettoyé au passage (paramètre `level` devenu
inutile depuis l'unification de la surface).

## v0.11.1 — Lot 1.11 : les lacs

L'hydrologie avait déjà tout calculé : `fillDepthM` **est** la profondeur
d'eau des cuvettes comblées, séparée de la roche depuis la v0.9.9. Ce lot ne
fait que décider lesquelles méritent le nom de lac, et poser leur surface.

### Le niveau vient de la grille, le contour du terrain

Une cellule de grille couvre 13 000 km² — plus que le Baïkal. Si le contour
des lacs en dépendait, ils seraient énormes et polygonaux. Ici, la grille ne
donne que le **niveau de l'eau** ; c'est ensuite le terrain fin qui décide de
la rive, l'eau remplissant tout ce qui passe sous ce niveau. Un promontoire
reste sec, une anse se remplit, et les rives sont détaillées sans que la
grille ait à l'être.

### Une seule surface, encore

Les lacs entrent dans `renderedAltitudeAt`, pas dans une couche séparée —
leçon de la v0.10.4, où deux surfaces concurrentes enterraient la caméra.
Elle se pose donc sur l'eau et non au fond. L'incision des vallées passe
avant le remplissage : une vallée creusée peut se remplir, l'inverse n'aurait
pas de sens.

### Calibrage et rendu

Seuil à 30 m de comblement : les lacs occupent 1 à 2 % des terres, l'ordre de
grandeur terrestre. L'eau douce est teintée du ciel plutôt que du fond,
s'assombrit avec la profondeur, et devient spéculaire avec le même fondu de
rive que la mer. Le calque **Eaux** distingue désormais les lacs retenus
(turquoise franc) des cuvettes écartées par le seuil (turquoise éteint) : on
voit ce que le seuil a laissé de côté.

Six tests : existence en proportion terrestre, planéité de la surface, jamais
sous le niveau de la mer, appartenance à la surface rendue, neutralité hors
lac, monde simulé inchangé. `GENERATION_VERSION` passe à 7.

## v0.11.0 — Lot 1.10b : les vallées

Le terrain rendu se creuse enfin de vallées kilométriques, là où l'hydrologie
dit que l'eau se concentre.

### Le nœud, et comment il se dénoue

Le débit de l'hydrologie sait **où** l'eau passe, mais à 115 km de
résolution — mille fois trop grossier pour une vallée. Un bruit en crêtes
fournit donc le **tracé** fin, naturellement ramifié, et le débit en règle la
**profondeur** : une vallée existe là où le tracé est plausible ET où la
simulation fait passer de l'eau.

Quant à l'ordre — l'hydrologie a besoin des altitudes, le terrain a besoin du
débit — il se dénoue en remarquant que l'incision est un **enjolivement du
terrain rendu**, pas une entrée de la simulation. Grille, climat, érosion
travaillent tous sur `altitudeAt`, que l'incision ne touche pas. On calcule
donc l'hydrologie sur le terrain nu, puis on branche son débit pour le seul
rendu : pas de boucle, et la grille ne peut pas diverger de la fonction.

### Calibrage mesuré avant écriture

Longueur d'onde 40 km, seuil 0,35 : vallées d'environ 1,5 km de large
couvrant 7 % du sol. Creusement de 15 m pour un affluent, 68 m pour un
fleuve, versants à 4° au plus — lisibles en descente, jamais des canyons.
Fondus aux extrémités : rien sous 25 m d'altitude (les plaines littorales
sont déjà plates, et creuser au rivage ferait entrer la mer), atténuation
au-dessus de 2 500 m (les hautes crêtes sont glaciaires, pas fluviales).

### Borné par construction

Les deux facteurs du creusement vivent dans [0, 1] : il ne peut pas dépasser
140 m, quoi qu'il arrive. Leçon des lots précédents, où le calibrage seul
avait échoué trois fois. Cinq tests : borne respectée et incision agissante,
jamais de mer ouverte, rivage intact, monde simulé inchangé, déterminisme.

`GENERATION_VERSION` passe à 6 — les empreintes de grille restent valides,
mais l'aspect du terrain change. À re-figer si la CI le demande.

## v0.10.7 — Le garde du cône mesurait au niveau de la mer

Le défaut revenait sur un plateau : `sol 2 m` mais `niv max 17` à 387 m
d'altitude, contre 23 au niveau de la mer. Cette fois, la simulation fidèle du
sélecteur l'a reproduit avant toute correction, et la trace de la descente a
donné la cause exacte.

Le garde de proximité du cône de vision — celui qui empêche d'éliminer une
tuile dont la caméra est proche — comparait des distances mesurées **au
niveau de la mer**. Sur un plateau de 387 m, il croyait les tuiles à 389 m au
lieu de deux : il cessait de protéger dès le niveau 16, et le cône éliminait
la branche contenant l'observateur, donc toutes ses descendantes fines. Le
premier plan disparaissait. Au niveau de la mer, la distance était juste et
le défaut restait invisible — ce qui explique que la v0.10.5 ait paru
résoudre le problème.

Le centre de tuile est désormais ramené au rayon du terrain dans le test du
cône comme dans la subdivision. Vérifié par simulation avant écriture :
niveau 23 sur tous les plateaux testés (contre 17 et 16), sans effet là où il
n'y avait pas de défaut.

Au passage, la position de la caméra passe en **double** dans le sélecteur —
invariant n°5 du projet, que ce code violait : sur la sphère unité, deux
mètres valent deux ulp et demi de flottant 32 bits.

Deux tests ajoutés : le niveau atteint à deux mètres du sol ne doit pas
dépendre de l'altitude du plateau, et il doit s'affiner en descendant.

## v0.10.6 — Premier plan résolu, diagnostic éteint

Confirmé à l'essai : `sol 2 m · near 0,10 m · niv max 23`, et le terrain
couvre l'écran jusqu'en bas. La teinte par niveau a montré la structure
attendue — des anneaux concentriques de niveaux décroissants autour de
l'observateur.

**Cause finale** : le sélecteur mesurait ses distances par rapport à la
sphère au niveau de la mer. Bloqué au niveau 14, il entourait l'observateur
de tuiles de 152 m dont le maillage n'échantillonnait le sol que tous les
9,5 mètres — bien trop grossier pour une caméra posée à deux mètres, qui
passait sous la surface interpolée. En mesurant par rapport au terrain local,
la subdivision atteint le niveau 23 et les tuiles proches font un mètre.

Cette version éteint la teinte de diagnostic, conservée derrière son drapeau.

### Ce que ce chantier aura appris

Sept versions pour un défaut. La cause tenait en une ligne, mais quatre
correctifs ont visé à côté faute de mesure : le plan de coupe, deux fois le
cône, la course entre fils — chacun un vrai défaut, aucun *le* défaut. Ce qui
a débloqué la situation, ce sont les trois instruments : le magenta (l'aplat
est-il du ciel ?), le HUD (que valent réellement `sol` et `near` ?) et la
teinte par niveau (où s'arrête la couverture ?). Règle retenue et appliquée
désormais : **au deuxième correctif inefficace, on cesse de corriger et l'on
instrumente.**

## v0.10.5 — Le détail suit le sol, et un diagnostic par couleur

Les captures innocentent définitivement le plan de coupe (`sol 10 m ·
near 0,50 m`, et le bas manque quand même) et révèlent le motif : le terrain
s'arrête à 150–300 m, **la taille exacte d'une tuile** au niveau affiché.

### Le sélecteur mesurait au niveau de la mer

À dix mètres au-dessus d'un plateau de 874 m, il jugeait la caméra à 874 m du
sol et plafonnait la subdivision au niveau 14 — d'où des tuiles de 152 m
autour de l'observateur. Les distances se mesurent désormais par rapport au
rayon du terrain local, et la finesse suit la hauteur réelle. Un test vérifie
la propriété : à hauteur égale, le niveau atteint ne doit pas dépendre de
l'altitude du plateau.

### Diagnostic : une teinte par niveau

Aucun compteur ne dit *où* s'arrête la couverture proche. Chaque tuile est
donc peinte selon son niveau de subdivision, en six teintes vives cycliques.
Une capture montrera si les tuiles voisines sont dessinées, et où la
couverture cesse — la question à laquelle je n'arrive pas à répondre par
déduction. Drapeau `DIAGNOSTIC_LEVEL_TINT`, à éteindre ensuite.

## v0.10.4 — Une seule surface de terrain

Le HUD de diagnostic a donné la réponse : `sol 2 m · near 0,10 m` — la
caméra et le plan de coupe étaient corrects — mais **`niv max 16`**. Le
sélecteur raisonne sur la sphère au niveau de la mer : à 332 m d'altitude
marine, il croit la caméra à 332 m du sol et s'arrête au niveau 16, alors
qu'elle est à deux mètres d'un plateau.

Or l'amplitude du détail dépendait du niveau de tuile : 24 m au niveau 16,
52 m au niveau 23. Le lancer de rayon ancrait donc la caméra sur la surface
du niveau maximal pendant que l'écran affichait celle du niveau 16 — deux
surfaces distantes de plusieurs dizaines de mètres. Quand la seconde passait
au-dessus, la caméra se retrouvait **enfouie** : toutes les faces vues de
dos, éliminées, écran vidé. C'est le défaut de la v0.7.1 revenu par une autre
porte, mon correctif d'alors ayant supposé que les tuiles étaient rendues au
niveau maximal.

`renderedAltitudeAt` ne prend plus de niveau : il n'y a **qu'une surface**,
partagée par le mailleur, la collision et tout le reste. Une caméra ne peut
plus se retrouver sous le sol qu'elle regarde, et le terrain ne change plus
d'altitude à chaque bascule de niveau de détail.

Le prix, assumé et documenté : les tuiles lointaines échantillonnent le
relief fin avec un pas grossier, d'où un moiré possible au loin — le morphing
entre niveaux (lot 2.4) le traitera. La cohérence vaut ce prix.

`GENERATION_VERSION` passe à 5 : l'aspect du terrain change.

## v0.10.3 — Le premier plan, cause trouvée : une course entre fils

Le magenta a tranché : l'aplat du bas d'écran était bien le ciel, donc un
manque de géométrie ; et `ScreenCoverageTest` innocentait la sélection. Il ne
restait que le plan de coupe — et la ligne de coupe rectiligne des captures
en était la signature.

La cause exacte : `raycaster` était écrit sur le fil de génération et lu sur
le fil d'interface **sans `@Volatile`**. Rien ne garantissait que
l'affectation devienne visible ; le fil d'affichage retombait alors sur le
repli `?: eyeAltitudeM()` — l'altitude au-dessus du niveau de la mer — et
plaçait le plan de coupe à une cinquantaine de mètres sur un plateau. Tout le
premier plan disparaissait, sans qu'aucun test ne puisse le voir : le défaut
vivait dans la visibilité mémoire entre fils, pas dans une formule.

- `@Volatile` sur le raycaster : la course est fermée.
- **Seconde borne indépendante** : le plan de coupe prend le minimum de la
  hauteur au-dessus du sol et de la portée de la caméra, toujours disponible
  et toujours juste. Une valeur aberrante ne peut plus passer — il faudrait
  que les deux le soient. Un test le vérifie avec une hauteur de 900 m et une
  portée de 4 m.
- Le magenta de diagnostic est éteint mais conservé derrière son drapeau.

## v0.10.2 — Version de diagnostic : identifier l'aplat du bas d'écran

Le correctif du plan de coupe n'a pas suffi. Depuis quatre versions, je
suppose que l'aplat clair vu sous l'horizon est le fond de brume du ciel —
**sans l'avoir jamais vérifié**. Si c'était de l'eau ou du terrain mal
coloré, toutes les pistes suivies seraient fausses depuis le début.

Cette version ne corrige rien : elle rend le défaut lisible.

- Le fond de brume du ciel est peint en **magenta vif**, couleur qui
  n'existe nulle part ailleurs dans le rendu. Si le bas de l'écran devient
  magenta, c'est bien le ciel et il manque du terrain ; s'il reste bleu-gris,
  c'est de la géométrie, et le défaut est dans sa couleur ou sa position —
  deux diagnostics opposés, une seule capture pour les départager.
- Le HUD gagne `sol X · near Y · niv max Z` : la hauteur réelle au-dessus du
  terrain, le plan de coupe effectif, et le niveau de subdivision le plus fin
  atteint par la sélection.

Le drapeau `DIAGNOSTIC_SKY_GROUND` sera retiré une fois la cause connue.

## v0.10.1 — Le premier plan manquant : le plan de coupe

Diagnostic enfin ferme. Le sélecteur, reproduit fidèlement en Python et
balayé direction par direction aux altitudes signalées, couvre **la totalité
du champ de vision** — la sélection était donc innocente, et j'avais patché
deux fois au mauvais endroit.

Le coupable est le **plan de coupe proche**, calculé sur l'altitude au-dessus
du niveau de la mer au lieu de la hauteur au-dessus du sol. Sur un plateau à
390 m, la caméra ancrée à deux mètres du sol recevait un plan à 7,8 m : le
sol visible au bas de l'écran, à cinq ou six mètres en visée rasante, était
supprimé. Le défaut grandissait à la descente — la hauteur réelle diminuant
pendant que l'altitude marine restait celle du plateau — ce qui correspond
exactement au symptôme rapporté (« le bleu apparaît quand on zoome »).

`CameraSnapshot` porte désormais la hauteur au-dessus du terrain, mesurée par
le lancer de rayon sur la surface rendue, et `PlanetCamera.nearPlaneFor` en
dérive un plan qui reste sous le dixième de cette hauteur, plancher à dix
centimètres pour préserver le tampon de profondeur. Trois tests portent sur
la propriété — « voit-on le sol sous ses pieds ? » — plutôt que sur la
formule.

## v0.10.0 — Diagnostic du premier plan, et filet de sécurité

Le défaut du bas d'écran en visée rasante résiste au correctif de la v0.9.5,
et les compteurs du HUD l'innocentent : tuiles sélectionnées et dessinées
coïncident (208/208). J'avais corrigé à l'aveugle ; je ne recommence pas.

### Un test qui localise au lieu de deviner

`ScreenCoverageTest` reproduit la situation exacte — caméra à 3 m, 500 m,
1,8 km et 27 km, visée de rasante à plongeante — et **échantillonne l'écran**
direction par direction : chaque rayon visant le sol doit tomber dans une
tuile sélectionnée. En cas d'échec, le message donne la fraction manquante et
la hauteur d'écran concernée ; en cas de succès, la sélection est innocentée
et le rendu désigné. Le prochain rapport de CI tranchera.

### Filet de sécurité, indépendamment du diagnostic

Le repli sur l'ancêtre suppose que la chaîne remonte jusqu'à quelque chose.
Les six tuiles racines couvrent la planète entière pour 460 Ko : elles sont
désormais demandées d'office et **jamais évincées**. Au pire, une direction
montre un terrain très grossier — ce qui se voit infiniment moins qu'un trou
de ciel sous l'horizon.

## v0.9.9 — Roche et eau enfin distinguées

Erreur de **conception**, pas de calibrage : je publiais comme relief le
terrain dont les cuvettes avaient été comblées par le priority-flood. Or ce
comblement — plusieurs centaines de mètres — n'est pas du dépôt sédimentaire,
c'est un artefact nécessaire au routage de l'eau. Chaque cuvette devenait
donc une colline, et l'enveloppe de dépôt sautait légitimement.

Deux terrains désormais distincts :

- **la roche** (`erodedM`), seule chose que l'érosion modifie et seule chose
  publiée comme relief ;
- **le terrain de routage**, roche + cuvettes comblées, reconstruit à chaque
  passe (sinon les comblements s'empileraient) et utilisé uniquement pour
  décider où va l'eau.

`fillDepthM` porte la hauteur de comblement : c'est exactement la profondeur
d'eau que le lot 1.11 posera par-dessus la roche. Un lac n'est pas de la
roche — la structure de données le dit maintenant.

Les tests de réseau suivent la même distinction : l'écoulement descend sur
« roche + comblement », le terrain que l'eau voit, et tout chemin terrestre
atteint bien la mer.

## v0.9.8 — L'érosion bornée par construction

Le tri corrigé en v0.9.7 a rendu l'accumulation juste — et donc le débit réel
bien plus fort que dans ma simulation. L'érosion mordait au-delà des bornes
du test.

Plutôt que de rechercher le coefficient qui tomberait juste — pari déjà perdu
deux fois ici (relief tectonique, micro-relief) et intenable avec un débit
qui s'étale sur trois décades —, l'érosion reçoit une **enveloppe par
cellule**, appliquée à chaque passe : au plus 25 % de l'altitude locale et
500 m d'abaissement, au plus 120 m de dépôt. Physiquement défendable (un
massif ne se rabote pas entièrement, l'incision ralentit quand la pente
s'aplanit) et, surtout, vraie par construction : le test vérifie l'enveloppe
elle-même, plus un calibrage, avec un garde-fou exigeant que l'érosion ait
réellement agi.

L'enveloppe s'applique dans la boucle et non en fin de passe : une altitude
hors bornes fausserait le réseau d'écoulement de la passe suivante avant
d'être corrigée.

## v0.9.7 — Correctif : la clé de tri de l'hydrologie triait à l'envers

Trois tests rouges sur la v0.9.6, une seule cause : dans `packKey`, le
décalage de 32 bits plaçait le bit de poids fort de la clé sur le **bit de
signe du Long**. Java comparant les Long en signé, les altitudes positives
se triaient avant les fonds marins — l'accumulation de débit remontait donc
le réseau à l'envers, d'où la conservation violée, la croissance vers l'aval
inversée et l'érosion hors bornes.

Ma validation Python de cette fonction avait raisonné en entiers **non
signés** et n'avait rien vu. Elle a été refaite en simulant l'arithmétique
signée de Java, seule pertinente ici, et le correctif (XOR avec le bit de
signe — la transformation standard) y est vérifié : ordre préservé,
relecture exacte, ex æquo départagés par indice.

Trois tests nouveaux portent maintenant sur la clé **elle-même** : son bug
n'était visible que par des effets lointains, ce qui allonge inutilement tout
diagnostic. Une primitive dont la justesse n'est pas évidente mérite son
test direct. Deux avertissements de compilation nettoyés au passage.

## v0.9.6 — Lot 1.9 : érosion et réseau d'écoulement

### Une limite constatée avant d'écrire, pas après

À 115 km entre deux cellules de la grille, **aucune vallée ne peut se
creuser** — une vallée fait 1 à 10 km de large. Trois ordres de grandeur de
coefficient balayés en simulation : le relief finit par fondre, jamais par se
ciseler. Les vallées visibles viendront du terrain fin, au lot suivant,
guidées par ce que ce lot calcule.

### Ce que le lot livre

- **Débit cumulé** par cellule — la matière première des rivières (1.10), des
  lacs (1.11) et de l'incision fine à venir.
- **Directions d'écoulement** : toute cellule terrestre a un chemin
  strictement descendant jusqu'à la mer, garanti par un **priority-flood**
  (algorithme de Barnes, une passe, O(n log n)) — la relaxation itérative ne
  converge jamais sur une cuvette large.
- **Cuvettes comblées** avec leur profondeur conservée : les futurs lacs.
- **Abaissement différencié** : médiane 8 m, 90ᵉ centile 56 m. Les versants à
  fort débit s'usent, les crêtes tiennent, le sédiment se dépose en piémont.
  Effet régional, assumé comme tel.

Coût mesuré en simulation puis estimé : ~100 ms pour 25 passes, sur les
2 200 ms de génération.

### Visible dès cet APK

Nouveau calque **Eaux** : débit en bleu croissant sur échelle logarithmique
(trois décades — une échelle linéaire ne montrerait que le fleuve principal),
cuvettes comblées en turquoise.

### Tests

6 tests de propriétés plutôt que d'apparence : tout écoulement descend
strictement, tout chemin terrestre atteint la mer sans cycle, le débit se
conserve (somme aux exutoires = nombre de cellules) et croît vers l'aval,
l'abaissement reste dans les bornes mesurées, la mer est intacte,
la génération est déterministe.

## v0.9.5 — Correctif : le premier plan disparaissait en vue rasante

Signalé à l'essai : au ras du sol, un aplat bleu-gris sous l'horizon — le
fond de brume, là où l'eau proche aurait dû se dessiner. Diagnostic : la
copie sans allocation du test de cône dans le sélecteur avait **perdu le
garde-fou « caméra dans la tuile »** que la version de `ViewCone` possède.
En vue rasante, les tuiles qui contiennent l'observateur ont leur centre
derrière l'œil : toute la pile, jusqu'à la racine, était rejetée, et le
premier plan n'existait pas.

Le garde est restauré et élargi à trois rayons (une tuile voisine immédiate
remplit l'écran même le centre hors du cône), les deux écritures du test sont
réalignées, et un test de **couverture du nadir** — 24 caméras au ras du sol
en visée tangente, le point sous chacune doit appartenir à une tuile
sélectionnée — verrouille l'ensemble. C'est la troisième fois qu'une double
écriture diverge (gestes, parenté, cône) : chacune a désormais son test
jumeau.

## v0.9.4 — Qualité graphique : rivages fondus et halo de limbe

Lot graphique ciblé sur les deux défauts que les captures d'essai désignaient.

### Fin des côtes en dents de scie

Le trait de côte sautait de la couleur terre à la couleur mer au pas de la
maille, et le reflet de l'eau était binaire par facette. Le matériau devient
**continu par sommet** (0 terre, 1 eau, fondu sur ~23 m d'altitude autour du
rivage) : le reflet s'éteint en dégradé sur la frange littorale. Côté
couleur, une **frange humide** fond la terre vers l'eau claire sous douze
mètres d'altitude. L'escalier devient un dégradé de plage.

### Halo atmosphérique en mode sol

Vu d'orbite, le rendu à tuiles n'avait pas le halo bleu du limbe que le globe
classique possède — d'où son aspect « sec » de loin. Le même halo entre dans
le shader des tuiles, calculé au sommet et **modulé par l'altitude** : plein
au-delà de 300 km, nul au sol, car en vue rasante la direction de visée frôle
la sphère partout et le halo voilerait la scène entière de bleu.

Aucun changement de génération : `GENERATION_VERSION` reste à 4, les
empreintes figées restent valides.

## v0.9.3 — Correctif : la tectonique suit le caractère du monde

Deux tests de glace encore rouges après la v0.9.2, et la simulation fidèle
des deux pipelines a montré la cause exacte : le caractère de relief tiré par
monde (`reliefScale`) ne modulait que le bruit — chaque monde recevait des
chaînes pleines, et les **pénéplaines douces**, celles qui tenaient la
moyenne de glace du banc d'essai sous son seuil, avaient disparu du tirage.

La tectonique suit désormais le caractère du monde (`reliefScale^0.8`, socle
isostatique exclu : la flottaison n'est pas de l'orogenèse). Vérifié par
simulation : le rapport de surface en altitude nouveau/ancien passe de 2,8 à
1,4, trois mondes sur quatre sous l'ancien régime, plus aucune terre au-delà
de 3 000 m sur les mondes doux. Amplitudes affinées au passage (CC 3 000 m
sur σ 230 km, cordillères 2 600). Les seuils des tests statistiques passent
au tiers de l'effet : un monde doux atténue tout, et un test doit attraper un
modèle débranché sans rougir sur une pénéplaine légitime.

## v0.9.2 — Correctif : le pire cas du relief, borné par construction

Quatre tests rouges convergents sur la v0.9.1 — et c'était le modèle qui
avait tort : ma validation avait calibré le cas **moyen** et laissé le pire
cas (intensité maximale × relief fort × queue du bruit) percer les plafonds
physiques (pics au-delà de 9 000 m, fonds sous −11 000), pendant que l'excès
de surface montagneuse gelait certains mondes au-delà du seuil de 16 % de
glaces. Erreur de méthode autant que de constantes.

- **`softLimit`** : compression douce C¹ au-delà de 70 % des bornes, avec les
  bornes pour asymptotes — `maxAltitudeM` et `maxDepthM` ne peuvent plus être
  dépassés, par construction, comme SimTest l'exige au mètre près. Monotone,
  donc le percentile du niveau de la mer la traverse sans bouger. Appliquée à
  l'identique sur la grille et dans la fonction : invariant n°3 intact.
- Amplitudes et largeurs resserrées (CC 3 300 m sur σ 287 km, intensités
  [0,5 ; 1,25], bruit amorti à 0,40) : la surface au-dessus de 3 500 m tombe
  de 13,8 % à 1,7 % dans la simulation de distribution — c'est elle qui
  fabriquait les boules de neige.
- Pire cas vérifié par simulation avant livraison, cette fois : pic 5 980 m,
  fond −5 917 m.
- Le test de bornes maison s'aligne sur l'exigence stricte de SimTest.

## v0.9.1 — Lot 1.6 : le relief a une cause

**Tous les mondes changent** (`GENERATION_VERSION = 4`) : les chaînes de
montagnes naissent désormais des collisions, les fosses des subductions, les
dorsales des divergences. Le bruit est rétrogradé au rôle d'habillage (amorti
à 45 %).

### Le modèle

Par sommet de grille : un socle isostatique (+250 m continental, −900 m
océanique, qui tire les côtes vers les frontières sans les rendre
polygonales), plus les profils tectoniques calibrés contre la Terre —
collision continentale +3 800 m sur 290 km de mi-hauteur (Tibet ~5 000 avec
le bruit), cordillère culminant à 130 km de sa fosse (−4 300, largeur 70 km,
Pérou-Chili), arcs insulaires, dorsales larges de 400 km, rifts à
épaulements. L'ampleur suit la vitesse relative de chaque frontière.

### L'architecture tient l'invariant

Le champ structural entre dans `TerrainProfile` par le `FieldSampler`, exact
au bit près sur les sommets : grille et fonction restent le même objet
(TerrainLodTest le vérifie). Un **second calibrage** — percentile de la somme
bruit + structure — garantit la fraction océanique demandée malgré l'addition
du relief. La conversion du bruit devient une fonction de companion, partagée
entre le générateur et le profil : aucune formule dupliquée.

### Tests

5 tests statistiques sur trois mondes réels, seuils à la moitié de l'effet
calibré : soulèvement aux convergences, fosses sous le plancher, bombement
des dorsales, fraction océanique, bornes physiques.

## v0.9.0 — L'infrastructure du relief tectonique (lot 1.8, remonté)

Prérequis du lot 1.6, livré seul pour garder le risque maîtrisé : rien ne
change encore au relief, `GENERATION_VERSION` reste à 3.

### FieldSampler : les champs de grille deviennent des fonctions

Interpolation barycentrique par déterminants sur les triangles de
l'icosphère : exacte aux sommets, continue à travers les arêtes, partition de
l'unité — trois propriétés **par construction**, chacune testée (dont la
reproduction d'un champ linéaire à l'erreur de corde près, calculée). C'est
le pont qui permettra au relief tectonique — puis à l'érosion, l'hydrologie,
le climat — d'entrer dans `TerrainProfile` sans violer l'invariant n°3.

### Champ de distance aux frontières

Dijkstra multi-source par type de frontière, transportant l'intensité et,
pour les convergentes, la nature des croûtes (collision continentale,
subduction océan-continent, arc océan-océan) — tout ce que le lot 1.6
transformera en chaînes, cordillères, fosses et dorsales. Les ex æquo de la
file sont départagés par un ordre total : sans cela, le relief aurait pu
différer d'un appareil à l'autre le long des médiatrices entre frontières.

### Visible dès cet APK

Le calque Plaques gagne un **halo** : la teinte de chaque frontière déteint
sur ~600 km proportionnellement à la proximité — la portée du futur relief se
lit, pas seulement son trait.

- 8 tests ajoutés.

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
