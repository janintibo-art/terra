# Journal des versions

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
