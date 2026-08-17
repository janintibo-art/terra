"""Lot 3.5 — instanciation massive : instruction chiffrée AVANT le code.

DEUX ARCHITECTURES ESSAYÉES ICI MÊME, LA PREMIÈRE REJETÉE PAR SON ASSERT.

1) LOTS FUSIONNÉS (un tampon par espèce×niveau, chaque arbre copié
   transformé) : classique en GLES2 faute d'instanciation matérielle.
   REJETÉE : un feuillu PLEIN pèse 0,7 Mo de sommets ; 38 arbres pleins =
   27 Mo de VBO dupliqués, plus que le cache de tuiles entier, pour 264
   arbres. La contrainte n'est pas le triangle, c'est la DUPLICATION.

2) MAILLAGES PARTAGÉS + UN APPEL PAR ARBRE : V variantes par
   (espèce, niveau), chaque instance dessine la sienne avec sa position en
   uniforme (ancre−œil soustraite en double sur CPU, invariant n°5). La
   mémoire tombe au coût des variantes seules ; le prix est le nombre
   d'appels de dessin — chiffré au §2, banal à cette échelle. RETENUE.

LES POSITIONS SONT CELLES DES LOSANGES. Même treillis canonique de niveau
15 (43,6 m entre cases), mêmes sels pour la gigue et la densité : un arbre
pousse là où un losange poussait, déterministe par graine et par position,
identique Android/PC. Nouveaux sels : +7 (espèce), +8 (variante).

Sections : 1. combien d'arbres · 2. appels de dessin · 3. mémoire ·
4. variantes · 5. les losanges en dessous.
"""
import math

R = 6_371_000.0
CELL_M = (math.pi / 2 * R) / (7 * (1 << 15))     # treillis niveau 15, N=7
print(f"=== 1. Combien d'arbres ===\n  case du treillis : {CELL_M:.1f} m")
for rng in (200, 400, 800):
    cells = math.pi * rng * rng / (CELL_M * CELL_M)
    print(f"  rayon {rng:>4} m : {cells:>7.0f} cases → au plus autant d'arbres "
          f"(la densité du biome en retire)")
# En forêt tropicale (densité 1,0) à 400 m : ~265 arbres. C'est PEU — le
# treillis actuel est clairsemé (43,6 m entre plantes). La densification
# (pas de 10 m, ×19 en nombre) est un chantier SÉPARÉ, à mesurer d'abord.
cells400 = math.pi * 400 * 400 / (CELL_M * CELL_M)
assert 200 < cells400 < 350

print("\n=== 2. Appels de dessin ===")
# Un appel par arbre, groupés par variante (aucun changement d'état entre
# deux arbres de la même variante : mêmes VBO et programme, seuls trois
# uniformes changent).
n400 = int(math.pi * 400 * 400 / (CELL_M * CELL_M))
print(f"  rayon 400 m, densité 1,0 : {n400} appels au pire — le rendu des")
print("  tuiles en fait déjà ~330 (un par tuile active). Doubler le nombre")
print("  d'appels avec des appels minuscules est le compromis qui achète")
print("  27 Mo de mémoire ; si le profilage (lot 2.18) le contredit un")
print("  jour, la fusion ne concernera que le niveau BAS (83 ko l'arbre).")

print("\n=== 3. Mémoire : les variantes seules ===")
# Sommets = triangles × 3 (non indexé), feuillage compris — les coûts en
# triangles sont ceux MESURÉS par les tests du 3.3-b.
verts = {"CONIFERE": {"FULL": 17_776 * 3, "MEDIUM": 10_110 * 3, "LOW": 638 * 3},
         "FEUILLU": {"FULL": 6_472 * 3, "MEDIUM": 3_721 * 3, "LOW": 771 * 3}}
# Variantes par niveau : 2 pour PLEIN et MOYEN (les gros maillages — la
# variété visuelle vient d'abord de l'azimut et de l'échelle par instance),
# 4 pour BAS (83 ko l'unité, la répétition s'y verrait plus car ils sont
# nombreux et côte à côte).
V = {"FULL": 2, "MEDIUM": 2, "LOW": 4}
per_species = {sp: sum(v * 9 * 4 * V[lv] for lv, v in levels.items())
               for sp, levels in verts.items()}
total = sum(per_species.values())
for sp, size in per_species.items():
    print(f"  {sp:<9} variantes 2/2/4 : {size / 1e6:5.2f} Mo")
print(f"  Deux espèces dominantes chargées : {total / 1e6:.1f} Mo — contre")
print("  27 Mo en lots fusionnés, et chargées à la DEMANDE : une steppe ne")
print("  paie jamais les conifères.")
assert total / 1e6 < 12, "les variantes devraient rester sous 12 Mo"
V = 4    # pour l'affichage du §4 (le niveau bas)

# Budget de champ, ALLOCATION v0.52.1 : chaque arbre part du niveau que
# sa taille apparente dicte, puis on dégrade depuis la queue si le total
# déborde. La première allocation (gloutonne, « meilleur niveau que le
# budget permet ») donnait 38 pleins puis PLUS RIEN — la « falaise »
# constatée sur photo, écrite ici même sans que j'en voie l'effet.
import random
PX = 1406.8
H = 1.15
def apparent(h, d): return 2 * math.atan(h / (2 * d)) * PX
def allowed(px):
    if px >= 90 * H: return "FULL"
    if px >= 35 * H: return "MEDIUM"
    if px >= 12 * H: return "LOW"
    return None
tris = {"FULL": 6_472, "MEDIUM": 3_721, "LOW": 771}
BUDGET_FIELD = 500_000
sizes = sorted((apparent(11.0, math.sqrt(random.Random(i).random()) * 400 + 20)
                for i in range(n400)), reverse=True)
levels = [allowed(px) for px in sizes]
spent = sum(tris[l] for l in levels if l)
tail = len(levels) - 1
while spent > BUDGET_FIELD and tail >= 0:
    l = levels[tail]
    if l is None:
        tail -= 1
        continue
    down = {"FULL": "MEDIUM", "MEDIUM": "LOW", "LOW": None}[l]
    spent -= tris[l]
    levels[tail] = down
    if down:
        spent += tris[down]
    else:
        tail -= 1
from collections import Counter
counts = Counter(l for l in levels if l)
print(f"  budget {BUDGET_FIELD:,}, allocation progressive : "
      f"{counts['FULL']} pleins, {counts['MEDIUM']} moyens, "
      f"{counts['LOW']} bas, dépense {spent:,}")
assert spent <= BUDGET_FIELD
assert counts["MEDIUM"] >= 20, "la transition doit exister — pas de falaise"
order = {"FULL": 0, "MEDIUM": 1, "LOW": 2, None: 3}
assert all(order[levels[i]] >= order[levels[i - 1]] for i in range(1, len(levels))), \
    "les niveaux doivent se dégrader de façon monotone avec la distance"
print("  → transition pleins → moyens → bas → losanges, monotone : la")
print("    falaise (« 38 pleins puis plus rien ») est structurellement exclue.")

print("\n=== 4. Variantes d'individus ===")
# Générer un maillage PAR ARBRE serait du gâchis : à 400 m on ne distingue
# pas deux feuillus de graines différentes.
built = 2 * 3 * V     # 2 espèces dominantes × 3 niveaux × V variantes
print(f"  V = {V} variantes : {built} maillages sources par forêt type,")
print("  choisis par micro01(sel+8) — la variété visuelle vient surtout de")
print("  l'ORIENTATION (chaque arbre tourne de son propre azimut) et de")
print("  l'ÉCHELLE (±15 % par le sel de taille +4, déjà celui des losanges).")

print("\n=== 5. Les losanges restent en dessous ===")
print("  Les cerfs-volants des tuiles ne sont PAS retirés : au-delà de la")
print("  forêt instanciée ils restent la végétation lointaine, et en deçà")
print("  ils disparaissent DANS les couronnes (un losange de 2,5-7 m dans un")
print("  feuillu de 11 m). Les retirer exigerait de reconstruire les tuiles")
print("  au gré de la caméra — un couplage tuiles↔forêt qu'on refuse ici.")
print("  Si le losange transparaît dans les conifères clairsemés, le")
print("  constat se fera sur photo et la parade (élaguer l'émission des")
print("  cases couvertes) sera un lot de confort.")

print("""
CONCLUSION. TreeField dans :sim : énumère les cases du treillis autour du
point sous l'œil (même face), rejoue les exclusions des losanges (eau, lac,
pente 27 %, densité), choisit l'espèce (sel +7) et la variante (sel +8),
alloue les niveaux sous un budget de champ de 500 000 triangles (par\nniveau de taille apparente puis dégradation depuis la queue ; plafonds
en pixels du 3.3-b ; les BILLBOARD sont laissés aux losanges existants), et
produit des INSTANCES (position double, repère, échelle) pointant vers des
maillages de variantes partagés. Le renderer dessine un appel par arbre,
groupé par variante. Console « foret [rayon] » / « foret off ». Le suivi
automatique de la caméra et la densification du treillis sont des lots
ultérieurs.""")
