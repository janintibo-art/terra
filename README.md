# Terra — v0.3.0

Simulateur de planète de poche. Aucune permission, aucun réseau, aucune donnée
collectée.

## État d'avancement

**Phase 0 — Socle technique** : lots 0.1 à 0.6, 0.8, 0.11, 0.13
**Phase 1 — Monde statique** : lots 1.1 à 1.3
**Consolidation** : persistance, nommage, calques, géographie, temps, empreintes

Prochain lot : 1.4 à 1.8 — tectonique des plaques.

Voir `TERRA-FEUILLE-DE-ROUTE.md` pour le plan complet (143 lots, 9 phases).

## Architecture

```
:core   Kotlin pur — maths, vecteurs, PCG32, graines hiérarchiques, horloge
:sim    Kotlin pur — bruit, icosphère, biomes, génération de monde
:app    Android    — rendu OpenGL ES 2, HUD, gestes
```

`:core` et `:sim` ne dépendent **pas** d'Android : ils tournent sur une simple
JVM, ce qui rend la simulation testable automatiquement en intégration continue.

## Contrôles

| Geste | Effet |
|---|---|
| Glisser | Pivoter la planète (avec inertie) |
| Pincer | Zoomer |
| Appui long | Ouvrir le sélecteur de monde |
| Deux doigts (tape rapide) | Afficher / masquer le HUD |
| Boutons bas-gauche | Changer de calque de données |
| Boutons bas-droite | Vitesse du temps, sélecteur de monde |

Le nom d'un monde est sa graine : noter « Kaleth » suffit à le retrouver à
l'identique, sur n'importe quel appareil.

## Compilation

Chaque `git push` sur `main` lance les tests puis compile l'APK.
Onglet **Actions** → dernier run → artefact `terra-debug-<hash>`.
En cas d'échec, l'artefact `test-reports-<hash>` contient le détail.

## Tests

```
gradle :core:test :sim:test
```

Couverture actuelle : déterminisme du générateur aléatoire, indépendance des
graines dérivées, géométrie de l'icosphère, continuité du bruit, reproductibilité
de la génération, fraction océanique garantie, cohérence des biomes, gradient
thermique équateur-pôles.

## Points de vigilance

- Le déterminisme est un invariant du projet, pas une commodité. Toute
  modification de `Seed` ou `Rng` invalide les mondes existants.
- La convention d'axes (Y = axe polaire) est figée.
- Le relief est exagéré d'un facteur ~50 : à l'échelle réelle, aucune montagne
  ne serait visible depuis l'orbite.
