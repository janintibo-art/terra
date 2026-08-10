"""Lot 3.3-a — maillage des arbres : instruction chiffrée AVANT le code.

LE BUT. Donner un volume aux squelettes des lots 3.1 / 3.2. Chaque segment
devient un TUBE à N côtés, du rayon de base au rayon de pointe — deux
grandeurs que le squelette porte déjà, et dont la continuité aux raccords
est un invariant testé depuis le 3.1. C'est elle qui garantit qu'un tube ne
montrera aucune marche à la jonction avec son parent.

CE QUE CE LOT NE FAIT PAS. La dégradation par distance (moins de côtés au
loin, puis panneau) est le lot 3.3-b, et il est indispensable AVANT de
planter une forêt — ce script le montre en chiffres.

Sections : 1. sommets et triangles · 2. fermeture des extrémités ·
3. budget mémoire et GPU · 4. torsion entre segments · 5. seuil de côtés.
"""
import math

# Familles (segments totaux, segments TERMINAUX) — comptes du lot 3.2.
FAMILIES = {
    #            k_eff  D     total  terminaux (= k^D)
    "CONIFERE": (10, 3, 1111, 1000),
    "FEUILLU": (3, 5, 364, 243),
    "PALMIER": (8, 1, 9, 8),
    "CACTUS": (2, 2, 7, 4),
    "ARBUSTE": (3, 4, 121, 81),
    "HERBACEE": (4, 1, 5, 4),
    "MOUSSE": (3, 1, 4, 3),
}

FLOATS_PER_VERTEX = 9          # position 3 + normale 3 + couleur 3


def counts(total, terminal, sides):
    """Sommets et triangles d'un arbre entier.

    Segment COURANT : un anneau de base et un anneau de pointe, reliés par
    `sides` quadrilatères, soit 2·sides triangles et 6·sides sommets (non
    indexé, comme le maillage du globe : sommets dupliqués).

    Segment TERMINAL : l'anneau de pointe se réduit à un POINT — la branche
    se termine en cône. Coût : sides triangles, 3·sides sommets, et surtout
    l'extrémité est FERMÉE (voir §2).
    """
    running = total - terminal
    tris = running * 2 * sides + terminal * sides
    verts = running * 6 * sides + terminal * 3 * sides
    return verts, tris


print("=== 1. Sommets et triangles, par famille ===")
print(f"  {'espèce':<10} {'segments':>9} {'terminaux':>10} "
      f"{'triangles (8 côtés)':>21} {'(5 côtés)':>12}")
for name, (_, _, total, terminal) in FAMILIES.items():
    _, t8 = counts(total, terminal, 8)
    _, t5 = counts(total, terminal, 5)
    print(f"  {name:<10} {total:>9,} {terminal:>10,} {t8:>19,} {t5:>12,}")

print("\n=== 2. Fermeture des extrémités ===")
# Si l'on gardait un anneau de pointe de rayon non nul sur les branches
# terminales, le maillage resterait OUVERT : un trou du diamètre de la
# pointe, visible de près dès qu'on regarde une branche par le bout, et
# pire encore avec l'élagage des faces arrière (on verrait l'intérieur).
PX = 540 / math.tan(0.733038 / 2)
print("  Diamètre du trou qu'on éviterait, si la pointe restait ouverte :")
for name, r0, ratio, depth in (("CONIFERE", 0.26, 0.58, 4),
                               ("FEUILLU", 0.28, 0.60, 6),
                               ("ARBUSTE", 0.06, 0.66, 5)):
    rtip = r0 * ratio ** depth
    print(f"    {name:<9} pointe ⌀ {2 * rtip * 100:5.2f} cm "
          f"→ {2 * rtip / 2.0 * PX:5.1f} px vue à 2 m")
print("  → non négligeable de près. Le cône terminal ferme le maillage et")
print("    coûte MOINS cher qu'un tube (sides triangles au lieu de 2·sides).")
print("  La base du tronc reste ouverte : elle est enfouie dans le terrain.")

print("\n=== 3. Budget ===")
print(f"  {'espèce':<10} {'Mo (8 côtés)':>14} {'Mo (5 côtés)':>14}")
for name, (_, _, total, terminal) in FAMILIES.items():
    v8, _ = counts(total, terminal, 8)
    v5, _ = counts(total, terminal, 5)
    print(f"  {name:<10} {v8 * FLOATS_PER_VERTEX * 4 / 1e6:>12.2f}   "
          f"{v5 * FLOATS_PER_VERTEX * 4 / 1e6:>12.2f}")
_, tris_conifer = counts(1111, 1000, 8)
print(f"\n  UN conifère = {tris_conifer:,} triangles. Le rendu au sol en compte")
print("  déjà ~330 000 (relevé sur appareil). Donc :")
for n in (1, 10, 100, 1000):
    print(f"    {n:>5} conifères → {n * tris_conifer:>10,} triangles"
          f"{'   ← intenable sans 3.3-b' if n * tris_conifer > 500_000 else ''}")
assert 100 * tris_conifer > 500_000, "le besoin du 3.3-b ne serait pas démontré"
print("  → le lot 3.3-b (dégradation par distance) n'est pas un confort :")
print("    au-delà de ~50 arbres proches, il devient la condition du 3.5.")

print("\n=== 4. Torsion entre segments ===")
# Chaque anneau est construit dans un repère perpendiculaire au segment,
# monté par le « plus petit axe cardinal ». Deux segments voisins n'ont
# donc pas la même origine d'azimut : l'anneau de pointe du parent et
# l'anneau de base de l'enfant coïncident en POSITION et en RAYON, mais
# leurs sommets ne s'alignent pas — la surface tourne d'un cran.
#
# Conséquence réelle : AUCUNE sur la silhouette (même cercle), aucune sur
# l'ombrage plat, visible seulement si l'on posait une texture continue le
# long d'une branche. Le transport parallèle du repère le corrigerait ; il
# n'a de sens qu'avec des textures, donc pas dans ce lot.
for sides in (5, 8, 12):
    print(f"  {sides:>2} côtés : décalage angulaire max entre deux anneaux "
          f"= {180.0 / sides:5.1f}°, silhouette inchangée")

print("\n=== 5. Combien de côtés ? ===")
# PREMIÈRE RÉDACTION FAUSSE, corrigée ici : j'avais conclu « 0,7 px d'écart
# à 2 m » alors que le tableau que je venais d'imprimer disait 13,9 px. La
# leçon des gyres (v0.15.2) — un calibrage peut contredire son propre code.
#
# Et le critère lui-même était mal choisi : un écart en pixels dépend de la
# distance, donc ne tranche rien. Le bon critère est SANS DIMENSION :
# l'écart du polygone au cercle, rapporté à la LARGEUR APPARENTE du tronc.
# Il vaut (1 − cos(π/N)) / 2 quelle que soit la distance.
print(f"  {'côtés':>6} {'écart/rayon':>13} {'écart/largeur du tronc':>24} {'triangles':>11}")
base = None
for sides in (3, 5, 6, 8, 12, 16):
    sag_ratio = 1.0 - math.cos(math.pi / sides)
    relative = sag_ratio / 2.0
    _, tris = counts(1111, 1000, sides)
    if sides == 8:
        base = tris
    print(f"  {sides:>6} {sag_ratio * 100:>11.1f} % {relative * 100:>22.1f} % {tris:>11,}")
print("  → à 8 côtés, la silhouette s'écarte du cercle de 3,8 % de la largeur")
print("    du tronc : huit facettes lisibles de près, mais un contour qui")
print("    reste rond à l'œil — et cohérent avec le parti pris facetté déjà")
print("    assumé sur le globe en orbite. Passer à 12 côtés diviserait")
_, t12 = counts(1111, 1000, 12)
print(f"    l'écart par 2,2 pour {t12 / base:.1f}× les triangles : pas ce lot-ci.")
print("    DÉFAUT RETENU : 8.")

print("""
CONCLUSION. Tubes à 8 côtés, cônes fermés sur les branches terminales,
sommets dupliqués (pas d'indices) comme le maillage du globe, 9 flottants
par sommet (position, normale, couleur) ; l'écart au cercle vaut alors
3,8 % de la largeur du tronc, sans dimension donc valable à toute distance.
Un conifère pèse 9 776 triangles
et 1,06 Mo — négligeable seul, intenable à cent : le lot 3.3-b est la suite
obligatoire, pas une option. La torsion entre anneaux voisins est réelle et
sans effet visible tant qu'il n'y a pas de texture : à consigner, pas à
corriger ici.""")
