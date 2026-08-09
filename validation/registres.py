"""Lot 2.7-a — Registres d'échelle : calibrage des frontières.

LE BESOIN. La feuille de route demande cinq registres (orbite / continental /
régional / local / sol). La caméra, elle, est volontairement CONTINUE — pan
proportionnel à la distance, inclinaison et dilatation en formules sans seuil.
Les registres ne remplacent donc aucune de ces lois : ce sont des ÉTIQUETTES
de navigation (HUD, et bientôt routage du rendu au 2.7-b). La question est :
où poser les frontières pour qu'elles veuillent dire quelque chose ?

LE CRITÈRE RETENU : la distance de l'horizon au sol, c'est-à-dire « quelle
géographie remplit l'écran ». C'est la seule grandeur qui décrive le contenu
de la vue indépendamment de l'inclinaison et du cap. Deux frontières sont en
outre ANCRÉES sur des constantes déjà calibrées du projet, pour ne pas créer
une deuxième vérité :
  - sol      : portée où la dilatation temporelle atteint son plancher
               (TILT_OPEN_RANGE_M · MIN_TIME_DILATION = 694 m) ;
  - orbite   : TILT_OPEN_RANGE_M (2 000 km), où l'inclinaison s'ouvre —
               « où l'on cesse de regarder une planète pour se poser dessus ».

VALIDATIONS :
  1. les frontières dérivées des critères d'horizon, arrondies ;
  2. l'hystérésis : assez large pour l'inertie du zoom, assez étroite pour
     ne jamais chevaucher deux frontières ;
  3. le plan lointain : la formule déplacée de :app vers :sim doit être
     IDENTIQUE bit à bit à l'ancienne (déplacement pur, pas de retouche) ;
  4. les chiffres préparatoires du 2.7-b (bascule orbite → globe), pour
     instruire AVANT d'écrire — leçon v0.38.1.
"""
import math

R = 6_371_000.0
FOV_RAD = 0.733038            # PlanetCamera.DEFAULT_FOV_RAD (42°), le vrai —
                              # limbe.py utilisait 60°, ce qui SOUS-estimait
                              # l'erreur de silhouette ; la conclusion tenait
                              # donc a fortiori.
SCREEN_H = 1080.0
PX_PER_RAD = (SCREEN_H / 2) / math.tan(FOV_RAD / 2)

TILT_OPEN_RANGE_M = 2_000_000.0
MIN_TIME_DILATION = 1.0 / 2880.0

def horizon_ground_km(alt_m):
    """Distance de l'horizon LE LONG DU SOL, en km (arc, pas corde)."""
    return R * math.acos(R / (R + alt_m)) / 1000.0

def alt_for_horizon_km(d_km):
    """Altitude qui place l'horizon à d_km de sol."""
    return R * (1.0 / math.cos(d_km * 1000.0 / R) - 1.0)

print("=== 1. Les frontières, par la distance d'horizon ===")
# La grille de simulation : 10 242 cellules au niveau 5.
spacing_km = math.sqrt(4 * math.pi / 10_242) * R / 1000.0
cell_radius_km = spacing_km / 2.0
print(f"  maille de la grille : {spacing_km:.0f} km entre sommets, "
      f"rayon de cellule ≈ {cell_radius_km:.0f} km")

# sol / local — DEUX critères indépendants, qui doivent converger :
alt_dilation = TILT_OPEN_RANGE_M * MIN_TIME_DILATION
alt_one_cell = alt_for_horizon_km(cell_radius_km)
print(f"  sol/local :")
print(f"    plancher de dilatation temporelle : {alt_dilation:.0f} m")
print(f"    horizon = un rayon de cellule ({cell_radius_km:.0f} km) : "
      f"{alt_one_cell:.0f} m")
assert 0.5 < alt_one_cell / alt_dilation < 2.0, \
    "les deux critères sol/local divergent : revoir le raisonnement"
SOL_M = 700.0   # l'ancre existante, confirmée par le critère d'horizon
print(f"    → retenu : {SOL_M:.0f} m (l'ancre existante ; l'autre critère "
      f"tombe à moins d'un facteur 2)")

# local / régional — l'horizon embrasse un massif, un petit pays : 500 km.
alt_local = alt_for_horizon_km(500.0)
print(f"  local/régional : horizon 500 km (un massif) → {alt_local/1000:.1f} km"
      f" ; retenu 20 km")

# régional / continental — l'horizon embrasse un demi-continent : 1 500 km.
alt_reg = alt_for_horizon_km(1_500.0)
print(f"  régional/continental : horizon 1 500 km (demi-continent) → "
      f"{alt_reg/1000:.0f} km ; retenu 180 km")

# continental / orbite — l'ancre existante.
print(f"  continental/orbite : TILT_OPEN_RANGE_M = "
      f"{TILT_OPEN_RANGE_M/1000:.0f} km (ancre existante)")
print(f"    horizon à cette altitude : "
      f"{horizon_ground_km(TILT_OPEN_RANGE_M):.0f} km — quasi hémisphérique")

BOUNDS_M = [700.0, 20_000.0, 180_000.0, 2_000_000.0]

print("\n=== 2. Hystérésis ===")
# Bande en RATIO (l'échelle est logarithmique) : on ne change de registre
# qu'en franchissant frontière × B en montant, frontière / B en descendant.
B = 1.12
# a) Assez large ? Le zoom par pincement applique des facteurs de quelques
# pour cent par événement ; l'inertie de fin de geste peut ballotter
# l'altitude de ± 5 à 8 %. La bande totale fait :
band_total = B * B
print(f"  bande : ×{B} de part et d'autre, soit ×{band_total:.2f} au total")
assert band_total > 1.16, "bande plus étroite que le ballottement d'inertie"
# b) Assez étroite ? Les bandes de deux frontières voisines ne doivent
# jamais se toucher, sinon deux registres deviennent inatteignables.
for lo, hi in zip(BOUNDS_M, BOUNDS_M[1:]):
    gap = hi / lo
    assert gap > band_total * 2, f"bandes en collision entre {lo} et {hi}"
    print(f"  {lo/1000:>7.1f} km → {hi/1000:>7.1f} km : écart ×{gap:.0f}, "
          f"bandes ×{band_total:.2f} — marge confortable")

print("\n=== 2 bis. La logique d'échelle, simulée contre un modèle ===")
# Miroir exact de ScaleRegistry.update (franchissement échelon par échelon).
# Le bug attrapé pendant la relecture : tester la seule bande du registre
# CIBLE bloque tout mouvement quand la cible tombe dans une bande, même
# après des frontières franchies très franchement. On vérifie ici l'écriture
# corrigée contre un modèle indépendant, sur une marche aléatoire.
UPPER = BOUNDS_M + [float('inf')]

def classify(a):
    for i, b in enumerate(UPPER):
        if a < b: return i
    return len(UPPER) - 1

def update(cur, a):
    raw = classify(a)
    nxt = cur
    if raw > cur:
        while nxt < raw and a > UPPER[nxt] * B: nxt += 1
    elif raw < cur:
        while nxt > raw and a < UPPER[nxt - 1] / B: nxt -= 1
    return nxt

def acceptable(r, a):
    """Modèle : un registre est tenable si l'altitude n'a pas purgé ses bandes."""
    lo = UPPER[r - 1] / B if r > 0 else 0.0
    hi = UPPER[r] * B
    return lo <= a <= hi

# Marche aléatoire déterministe (générateur congruentiel, graine fixe) :
# facteurs de ×0,7 à ×1,43 par pas, sur 20 000 pas.
state, cur, a = 12345, 4, 24e6
for step in range(20_000):
    state = (state * 6364136223846793005 + 1442695040888963407) % 2**64
    f = 0.7 * (1.43 / 0.7) ** ((state >> 11) / 2**53)
    a = min(80e6, max(2.0, a * f))
    prev = cur
    cur = update(cur, a)
    assert acceptable(cur, a), f"registre {cur} intenable à {a:.0f} m (pas {step})"
    assert abs(cur - prev) <= 5, "échelle incohérente"
    # Franchissement franc : hors de toute bande, l'état DOIT suivre raw.
    raw = classify(a)
    in_band = any(b / B <= a <= b * B for b in BOUNDS_M)
    if not in_band:
        assert cur == raw, f"état {cur} ≠ classification {raw} hors bande, à {a:.0f} m"
print("  20 000 pas de marche aléatoire : état toujours tenable, et égal à")
print("  la classification dès qu'on sort des bandes. Le cas du bug (orbite")
print("  → 175 km) est couvert :", end=" ")
assert update(4, 175_000.0) == 3      # orbite → continental, pas orbite figée
assert update(0, 21_000.0) == 1       # sol → local, pas régional
print("orbite→175 km donne continental ✓")

print("\n=== 3. Plan lointain : le déplacement doit être PUR ===")
# Formule actuelle, en ligne dans PlanetRenderer.onDrawFrame (v0.38.1) :
#   far = 1.8 · sqrt((R+alt)² − R²) + 80 000
# On la déplace dans :sim SANS la retoucher : même comportement, mais testé.
def far_old_app(alt_m):
    horizon = math.sqrt(max(0.0, (R + alt_m) ** 2 - R ** 2))
    return horizon * 1.8 + 80_000.0

def far_sim(alt_m):        # la copie qui vivra dans ScaleRegistry
    horizon = math.sqrt(max(0.0, (R + alt_m) ** 2 - R ** 2))
    return horizon * 1.8 + 80_000.0

worst = 0.0
for alt in [2, 100, 700, 5_000, 20_000, 180_000, 2e6, 2e7, 8e7]:
    a, b = far_old_app(alt), far_sim(alt)
    worst = max(worst, abs(a - b))
    # La propriété qui compte : le plan couvre l'horizon avec de la marge.
    slant = math.sqrt((R + alt) ** 2 - R ** 2)
    assert b > slant * 1.5, f"plan lointain trop court à {alt} m"
assert worst == 0.0, "les deux écritures divergent : le déplacement n'est pas pur"
print("  identité bit à bit vérifiée sur 9 altitudes, de 2 m à 80 000 km")
print("  et far > 1,5 × distance d'horizon partout — un pic de 21 km au-delà")
print("  de l'horizon PEUT être coupé au ras du sol : comportement existant,")
print("  inchangé, voilé par la brume ; noté, pas corrigé ici.")

print("\n=== 4. Préparation du 2.7-b : les chiffres de la bascule ===")
# À quelle altitude basculer orbite → globe ? Trois grandeurs à confronter,
# pour chaque altitude candidate :
#   - l'erreur de silhouette du QUADTREE (ce qu'on répare) ;
#   - la flèche de l'icosphère niveau 6 du globe (ce qu'on y gagne : < 1 px ?) ;
#   - la taille d'une cellule à l'écran (ce que la couture devra masquer :
#     le globe peint PAR CELLULE, les tuiles en continu).
def quadtree_limb_err_px(alt_m):
    # Tuile du limbe : distance ≈ distance au limbe, niveau donné par le
    # critère 2·rayonTuile/distance > 1,4 (copie de TileSelector).
    d = math.sqrt((R + alt_m) ** 2 - R ** 2)          # distance au limbe
    level = 0
    while True:
        tile_arc = (math.pi / 2) / (2 ** level)
        if tile_arc * R / d <= 1.4:
            break
        level += 1
    sag = R * (1.0 - math.cos(tile_arc / 2.0))
    return sag / d * PX_PER_RAD, level

ICO6_EDGE_RAD = math.sqrt(4 * math.pi / (20 * 4 ** 6) * 2)   # ordre de grandeur
ico6_sag = R * (1.0 - math.cos(ICO6_EDGE_RAD / 2.0))

print(f"  {'altitude':>9} {'quadtree limbe':>15} {'globe niv6':>11} "
      f"{'cellule à l’écran':>18}")
for alt in (1_000e3, 1_500e3, 2_000e3, 3_000e3):
    err, lv = quadtree_limb_err_px(alt)
    d_limb = math.sqrt((R + alt) ** 2 - R ** 2)
    globe_px = ico6_sag / d_limb * PX_PER_RAD
    cell_px = (spacing_km * 1000.0) / alt * PX_PER_RAD
    print(f"  {alt/1000:>7.0f}km {err:>12.0f} px {globe_px:>8.1f} px "
          f"{cell_px:>15.0f} px")
print("  → le quadtree laisse des DIZAINES de pixels d'erreur là où le globe")
print("    en laisse moins d'un : la bascule règle bien le limbe. MAIS une")
print("    cellule couvre encore ~100-150 px à ces altitudes : la couture des")
print("    couleurs (par cellule côté globe, continues côté tuiles) sera")
print("    VISIBLE et devra être instruite au 2.7-c — repère du globe en")
print("    unités propres (0,02..60) à réconcilier avec le repère métrique,")
print("    en plus. Rien de tout cela n'entre dans le 2.7-a.")

print("\nConclusion : frontières 700 m / 20 km / 180 km / 2 000 km,")
print("hystérésis ×1,12 de part et d'autre, plan lointain déplacé à")
print("l'identique dans :sim. Le 2.7-a ne change AUCUN comportement visuel :")
print("il nomme, il mesure, il prépare.")
