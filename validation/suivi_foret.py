"""Lot 3.5-c — suivi caméra du champ d'arbres : instruction AVANT le code.

TROIS PROBLÈMES, TROIS PARADES CHIFFRÉES ICI.

1. TRAVERSÉE DES FACES. L'énumération actuelle balaie un rectangle de
   cases SUR UNE SEULE FACE : près d'une arête du cube, une partie du
   disque reste vide. Plutôt que des tables d'adjacence de faces (sources
   d'erreurs de repère, invérifiables sans appareil), on ÉCHANTILLONNE des
   DIRECTIONS : une grille tangente autour du point sous l'œil, chaque
   direction projetée par fromSphere vers (face, case), dédoublonnée par
   clef canonique. Les arêtes et les coins sont traités par construction —
   fromSphere choisit toujours une face, la même pour tous.

2. HYSTÉRÉSIS SPATIALE. Reconstruire à chaque pas ferait clignoter ;
   jamais reconstruire fait sortir du champ. Seuil : distance au centre du
   dernier champ > 35 % du rayon.

3. INVALIDATION CIBLÉE. L'exclusion des losanges change à chaque
   reconstruction ; vider TOUT le cache de tuiles (mécanique du changement
   de monde) coûterait un re-streaming complet toutes les ~2 minutes de
   marche. On n'évince que les tuiles proches du champ.

Sections : 1. pas d'échantillonnage · 2. couverture aux arêtes ·
3. cadence de reconstruction · 4. éviction ciblée.
"""
import math

R = 6_371_000.0
CELL = (math.pi / 2 * R) / (7 * (1 << 15))      # 43,64 m

print("=== 1. Pas d'échantillonnage ===")
# Une case est un quadrilatère d'environ CELL de côté (déformé par la
# projection cube-sphère : jusqu'à ±33 % vers les coins de face). Pour
# qu'AUCUNE case du disque n'échappe à la grille d'échantillons, le pas
# doit être inférieur au plus petit demi-côté de case.
worst_shrink = 0.75    # une case de coin de face est ~25 % plus petite
step = CELL * 0.5 * worst_shrink
print(f"  case nominale {CELL:.1f} m, la plus déformée ~{CELL*worst_shrink:.1f} m")
print(f"  pas retenu : {step:.1f} m (moitié de la plus petite case)")
for rng in (400, 800, 1500):
    k = math.ceil((rng + CELL) / step)
    samples = 0
    for j in range(-k, k + 1):
        for i in range(-k, k + 1):
            if (i * i + j * j) * step * step <= (rng + CELL) ** 2:
                samples += 1
    cells = math.pi * rng * rng / CELL ** 2
    print(f"  rayon {rng:>5} m : {samples:>6,} échantillons pour ~{cells:,.0f} cases "
          f"({samples / cells:.1f} éch./case)")
# À 400 m : ~1 850 échantillons, fromSphere + hachage chacun — négligeable
# (le placement d'UNE plante coûte déjà trois altitudeAt).

print("\n=== 2. Couverture aux arêtes ===")
# Preuve géométrique plutôt que numérique : la grille tangente est un
# maillage de pas p ; toute case contient un disque inscrit de rayon
# ≥ CELL·0,75/2 = 16,4 m > p·√2/2 = 11,6 m — tout disque de ce rayon
# contient au moins un nœud d'une grille de pas p. Donc toute case du
# disque reçoit au moins un échantillon, quelle que soit la face.
p = step
assert CELL * worst_shrink / 2 > p * math.sqrt(2) / 2, "pas trop grand"
print(f"  disque inscrit d'une case : ≥ {CELL*worst_shrink/2:.1f} m ;")
print(f"  demi-diagonale de la grille : {p*math.sqrt(2)/2:.1f} m → couverture garantie.")
print("  L'ordre d'énumération dépend de la grille, PAS l'ensemble des")
print("  cases : le tri final (taille apparente, puis clef de case) rend")
print("  le résultat indépendant de l'ordre — même champ au bit près.")

print("\n=== 3. Cadence de reconstruction ===")
SPEED = 1.4            # marche, m/s — le mode piéton est plus lent
for rng in (400, 800):
    threshold = rng * 0.35
    period = threshold / SPEED
    print(f"  rayon {rng} m : reconstruction après {threshold:.0f} m, "
          f"soit toutes les {period:.0f} s de marche")
print("  Une reconstruction = ~2 000 cases × 3 altitudeAt + tri : dizaines")
print("  de ms sur le fil de travail, échange atomique — aucune saccade.")
print("  Garde anti-empilement : pas de reconstruction tant que la")
print("  précédente n'est pas livrée.")

print("\n=== 4. Éviction ciblée des tuiles ===")
# Seules les tuiles portant des losanges dans ou près du champ mentent
# après un changement d'exclusion. Critère angulaire : centre de tuile à
# moins de (rayon du champ + rayon de tuile) du centre du champ.
for rng in (400, 800):
    ang = (rng + 700) / R    # marge : tuile de niveau 14 ≈ 610 m d'arête
    print(f"  rayon {rng} m : éviction sous {math.degrees(ang)*60:.1f} minutes d'arc")
print("  Une éviction parcourt le cache (~500 entrées) avec un produit")
print("  scalaire par tuile : négligeable. Les tuiles évincées se")
print("  reconstruisent par la file de priorité — quelques tuiles proches,")
print("  pas les centaines du cache. « foret off » évince pareil autour du")
print("  dernier centre pour restaurer les losanges.")

print("""
CONCLUSION. Énumération par échantillonnage de directions (pas 16,4 m,
couverture prouvée au §2, arêtes et coins gratuits), tri secondaire par
clef de case pour l'indépendance à l'ordre, reconstruction au-delà de 35 %
du rayon sur le fil de travail avec garde anti-empilement, éviction
ANGULAIRE du cache de tuiles au lieu du vidage complet. La commande
« foret » active le suivi, « foret off » le coupe et restaure.""")
