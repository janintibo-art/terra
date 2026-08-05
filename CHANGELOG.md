# Journal des versions

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
