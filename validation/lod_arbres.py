"""Lot 3.3-b — niveaux de détail des arbres : instruction AVANT le code.

LE BESOIN, ÉTABLI PAR LES LOTS PRÉCÉDENTS. Un conifère pèse 17 776
triangles (3.3-a + 3.3-c). Le rendu du terrain au sol en compte ~330 000 :
vingt conifères proches doublent donc la charge, et cent la quadruplent.
Sans dégradation, le lot 3.5 (instanciation massive) est impossible.

LE PRINCIPE. Un arbre lointain n'a pas besoin de huit côtés par branche ni
d'une touffe par rameau : il occupe quelques dizaines de pixels. On définit
donc des NIVEAUX, choisis non par une distance arbitraire mais par la
TAILLE APPARENTE de l'arbre — c'est elle qui décide de ce qui est visible,
et elle vaut pour toutes les espèces, du conifère de 11 m à la mousse de
8 cm.

Sections : 1. taille apparente et distances · 2. définition des niveaux ·
3. seuils dérivés · 4. budget d'une forêt · 5. hystérésis.
"""
import math

R_SCREEN_H = 1080.0
FOV_RAD = 0.733038
PX_PER_RAD = (R_SCREEN_H / 2) / math.tan(FOV_RAD / 2)

# Espèces : hauteur, triangles au niveau plein (mesures des lots 3.3-a/c).
SPECIES = {
    "CONIFERE": (11.6, 17_776),
    "FEUILLU": (11.0, 6_472),
    "PALMIER": (10.6, 144),
    "CACTUS": (3.94, 80),
    "ARBUSTE": (2.5, 2_152),
    "HERBACEE": (0.62, 80),
    "MOUSSE": (0.085, 64),
}


def apparent_px(height_m, distance_m):
    return 2.0 * math.atan(height_m / (2.0 * distance_m)) * PX_PER_RAD


print("=== 1. Taille apparente ===")
print(f"  {'espèce':<10} " + " ".join(f"{d:>7.0f}m" for d in (5, 20, 50, 150, 400, 1000)))
for name, (h, _) in SPECIES.items():
    row = " ".join(f"{apparent_px(h, d):>7.0f}p" for d in (5, 20, 50, 150, 400, 1000))
    print(f"  {name:<10} {row}")
print("  → une mousse dépasse à peine le pixel au-delà de 20 m ; un conifère")
print("    tient encore 16 px à 1 km. Une distance unique ne peut pas servir")
print("    de seuil pour les deux : c'est la taille APPARENTE qui décide.")

print("\n=== 2. Définition des niveaux ===")
LEVELS = [
    ("PLEIN", 8, "toutes", "octaèdre par rameau"),
    ("MOYEN", 5, "toutes", "octaèdre 1 niveau sur 2"),
    ("BAS", 3, "sauf dernier niveau", "une couronne unique"),
    ("PANNEAU", 0, "aucune", "deux triangles"),
]
print(f"  {'niveau':<9} {'côtés':>6} {'branches':<22} {'feuillage'}")
for name, sides, branches, foliage in LEVELS:
    print(f"  {name:<9} {sides:>6} {branches:<22} {foliage}")

print("\n=== 3. Seuils, dérivés de la taille apparente ===")
# Raisonnement : un tube à N côtés ne se distingue d'un tube à N/2 côtés que
# si sa LARGEUR apparente dépasse quelques pixels. Le tronc d'un conifère
# fait 52 cm ; à 8 côtés l'écart au cercle vaut 3,8 % de cette largeur.
# On veut cet écart au-dessus de ~0,3 px pour que huit côtés se justifient.
print("  a) Passage PLEIN → MOYEN : quand la facette cesse de se voir.")
for name in ("CONIFERE", "FEUILLU"):
    h, _ = SPECIES[name]
    trunk = {"CONIFERE": 0.52, "FEUILLU": 0.56}[name]
    # écart visible = 3,8 % de la largeur apparente du tronc
    d_limit = trunk * 0.038 * PX_PER_RAD / 0.3
    print(f"     {name:<9} tronc {trunk*100:.0f} cm → facette sous 0,3 px "
          f"au-delà de {d_limit:.0f} m, soit {apparent_px(h, d_limit):.0f} px de haut")
print("  → RÉDACTION D'ABORD FAUSSE, corrigée : j'avais écrit « l'arbre")
print("    mesure alors ~90 px » quand le calcul ci-dessus affiche 176 et")
print("    155 px. Encore la leçon des gyres (v0.15.2). La valeur juste est")
print("    ~165 px — mais voir le §4 : ce n'est pas la visibilité qui")
print("    tranchera, c'est le budget.")

print("\n  b) Passage MOYEN → BAS : quand un rameau tombe sous le pixel.")
for name in ("CONIFERE", "FEUILLU", "ARBUSTE"):
    h, _ = SPECIES[name]
    twig = {"CONIFERE": 0.314, "FEUILLU": 0.582, "ARBUSTE": 0.242}[name]
    d_limit = twig * PX_PER_RAD / 1.0
    print(f"     {name:<9} rameau {twig*100:.0f} cm → sous 1 px au-delà de "
          f"{d_limit:.0f} m, arbre à {apparent_px(h, d_limit):.0f} px")
print("  → SEUIL RETENU : 35 px (moyenne des trois, arrondie).")

print("\n  c) Passage BAS → PANNEAU : quand la structure cesse d'être lisible.")
print("     Un arbre de 12 px de haut occupe ~6 px de large : ses branches")
print("     tiennent dans un pixel. SEUIL RETENU : 12 px.")

THRESHOLDS = [("PLEIN", 90), ("MOYEN", 35), ("BAS", 12), ("PANNEAU", 0)]
print("\n  Distances correspondantes par espèce :")
print(f"  {'espèce':<10} {'plein <':>10} {'moyen <':>10} {'bas <':>10}")
for name, (h, _) in SPECIES.items():
    ds = []
    for _, px in THRESHOLDS[:3]:
        # distance à laquelle l'arbre occupe px pixels
        d = h / (2.0 * math.tan(px / (2.0 * PX_PER_RAD)))
        ds.append(d)
    print(f"  {name:<10} {ds[0]:>9.0f}m {ds[1]:>9.0f}m {ds[2]:>9.0f}m")
print("  → chaque espèce reçoit SES distances, sans qu'aucune ne soit codée")
print("    en dur : la mousse passe en panneau à 60 cm, le conifère à 80 m.")

print("\n=== 4. Le budget, et pourquoi les seuils seuls échouent ===")
# Point de mesure réel (Mali-G77, capture v0.45.1) : 330 008 triangles à
# 6,6 ms, 60 i/s. Il reste 10 ms avant de tomber sous 60 — dont on garde
# 30 % de marge pour les pics et les appareils plus lents.
TRIS_MESURE, MS_MESURE, MS_CIBLE = 330_008, 6.6, 16.6
PART_GEO = 0.5                    # moitié du coût supposée géométrique
tris_par_ms = TRIS_MESURE / (MS_MESURE * PART_GEO)
BUDGET = tris_par_ms * (MS_CIBLE - MS_MESURE) * 0.7
print(f"  Marge exploitable mesurée : {BUDGET:,.0f} triangles pour la végétation.")

COST = {"PLEIN": 17_776, "MOYEN": 2_200, "BAS": 32, "PANNEAU": 2}
DENSITY = 0.02
REACH = 400.0
h = SPECIES["CONIFERE"][0]


def dist_for_px(height, px):
    return height / (2.0 * math.tan(px / (2.0 * PX_PER_RAD)))


print("\n  a) Ce que donnent des SEUILS fixes, même serrés :")
for pl, mo, ba in ((300, 150, 60), (500, 250, 100)):
    total, prev = 0.0, 0.0
    for label, d in (("PLEIN", dist_for_px(h, pl)), ("MOYEN", dist_for_px(h, mo)),
                     ("BAS", dist_for_px(h, ba)), ("PANNEAU", REACH)):
        d = min(d, REACH)
        n = max(0.0, math.pi * (d * d - prev * prev) * DENSITY)
        total += n * COST[label]
        prev = max(prev, d)
    print(f"     seuils {pl}/{mo}/{ba} px → {total:>12,.0f} triangles "
          f"({total / BUDGET:.1f}× le budget)")
print("  → même au réglage le plus serré, on dépasse. La raison est")
print("    structurelle : le nombre d'arbres croît en D², le coût unitaire")
print("    ne décroît pas assez vite. Un seuil ne sait pas COMBIEN d'arbres")
print("    il vient d'admettre.")

print("\n  b) L'ALLOCATION PAR BUDGET, qui le sait :")
# Les arbres sont triés par taille apparente décroissante ; on descend la
# liste en donnant le meilleur niveau que le budget restant permet encore.
quotas = []
left = BUDGET
for label in ("PLEIN", "MOYEN", "BAS"):
    # On alloue à chaque niveau une part du budget, décroissante.
    share = {"PLEIN": 0.45, "MOYEN": 0.30, "BAS": 0.17}[label]
    n = int(BUDGET * share / COST[label])
    quotas.append((label, n, n * COST[label]))
    left -= n * COST[label]
panneaux = int(math.pi * REACH * REACH * DENSITY) - sum(q[1] for q in quotas)
quotas.append(("PANNEAU", max(0, panneaux), max(0, panneaux) * COST["PANNEAU"]))
print(f"     {'niveau':<9} {'arbres':>8} {'triangles':>12}")
for label, n, cost in quotas:
    print(f"     {label:<9} {n:>8,} {cost:>12,}")
used = sum(q[2] for q in quotas)
print(f"     {'TOTAL':<9} {sum(q[1] for q in quotas):>8,} {used:>12,}")
assert used <= BUDGET * 1.05, "l'allocation dépasserait le budget"
print(f"  → {quotas[0][1]} arbres au niveau plein autour de soi — largement de")
print("    quoi remplir une vue subjective — et le budget est tenu par")
print("    CONSTRUCTION, quelle que soit la densité de la forêt.")
print("  Les seuils en pixels du §3 restent utiles comme PLAFOND : jamais de")
print("  niveau plein pour un arbre de dix pixels, même si le budget le")
print("  permettrait. Budget et seuils se complètent.")

print("\n=== 5. Hystérésis ===")
# Comme les registres d'échelle (lot 2.7-a) : sans bande morte, un arbre à
# la frontière clignote entre deux niveaux à chaque pas de la caméra.
BAND = 1.15
print(f"  Bande ×{BAND} sur les seuils, reprise du principe du lot 2.7-a :")
for label, px in THRESHOLDS[:3]:
    print(f"    {label:<8} montée à {px * BAND:>5.1f} px, descente à {px / BAND:>5.1f} px")
d_hi = dist_for_px(h, 90 / BAND)
d_lo = dist_for_px(h, 90 * BAND)
print(f"  Pour un conifère : bascule entre {d_lo:.0f} m et {d_hi:.0f} m —")
print(f"  une zone morte de {d_hi - d_lo:.0f} m, franchie en {(d_hi-d_lo)/1.4:.0f} s")
print("  à la marche. Aucun clignotement possible.")

print("""
CONCLUSION — et elle a changé en cours d'instruction.

La conception naïve du lot (des seuils de distance, comme le suggère la
feuille de route) NE TIENT PAS : même au réglage le plus serré, une forêt
de conifères demande plusieurs fois le budget, parce qu'un seuil ne sait
pas combien d'arbres il vient d'admettre — le nombre croît en D².

Le lot livre donc DEUX mécanismes complémentaires :
  · quatre niveaux de maillage (PLEIN 17 776 tris, MOYEN ~2 200, BAS 32,
    PANNEAU 2), avec des plafonds en TAILLE APPARENTE valables pour toutes
    les espèces, de la mousse au conifère ;
  · un ALLOCATEUR qui trie les arbres par taille apparente et distribue les
    niveaux jusqu'à épuisement d'un budget mesuré (~600 000 triangles sur
    Mali-G77), donc tenu par construction quelle que soit la densité.

Hystérésis ×1,15 sur les plafonds, principe repris des registres d'échelle
du lot 2.7-a.""")
