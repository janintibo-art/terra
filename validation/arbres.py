"""Lot 3.1 — générateur d'arbres : instruction chiffrée AVANT le code.

LE BUT. Une grammaire de branchement paramétrée (angle, ramification,
longueur, conicité) qui produit un SQUELETTE déterministe : liste de
segments (base, pointe, rayons, profondeur, parent) dans un repère local
Y-haut, base à l'origine, en mètres. Le maillage est le lot 3.3, les
espèces le 3.2, la répartition le 3.6 : ici, seulement l'ossature — plus
une visualisation fil de fer par console pour juger à l'œil.

Sections : 1. budget de segments et garde · 2. angle d'or · 3. tropisme
(redressement) · 4. continuité des rayons · 5. coût du fil de fer.
"""
import math

print("=== 1. Budget de segments ===")
# Ramification k (enfants par branche, continuation comprise), profondeur D
# (le tronc est la profondeur 0) : nombre de segments = somme k^0..k^D.
def count(k, d):
    return (k ** (d + 1) - 1) // (k - 1) if k > 1 else d + 1

print(f"  {'k':>3} {'D':>3} {'segments':>9} {'mémoire (40 o/seg)':>20}")
for k, d in ((2, 5), (3, 4), (3, 5), (4, 4), (4, 5), (5, 6), (2, 10)):
    c = count(k, d)
    print(f"  {k:>3} {d:>3} {c:>9,} {c*40/1024:>17.1f} ko")
# Un chêne crédible : k=3, D=4-5 → 121-364 segments. Un buisson : k=4,
# D=3 → 85. Les valeurs pathologiques explosent (k=5, D=6 → 19 531 ;
# k=4, D=8 → 87 381). La garde doit REFUSER, pas écrêter en silence :
# écrêter changerait la forme selon la machine à budget, refuser est
# déterministe et bruyant.
MAX_SEGMENTS = 20_000
for k, d in ((3, 5), (4, 5), (5, 6)):
    ok = count(k, d) <= MAX_SEGMENTS
    print(f"  garde à {MAX_SEGMENTS:,} : k={k} D={d} → {'accepté' if ok else 'REFUSÉ'}")
assert count(3, 5) <= MAX_SEGMENTS and count(5, 6) <= MAX_SEGMENTS
assert count(4, 8) > MAX_SEGMENTS
print(f"  → MAX_SEGMENTS = {MAX_SEGMENTS:,} (800 ko au pire) : large pour toute")
print("    espèce du lot 3.2, assez bas pour qu'un paramétrage fou échoue net.")

print("\n=== 2. Angle d'or ===")
golden = math.pi * (3.0 - math.sqrt(5.0))
print(f"  φ d'azimut entre enfants successifs : {golden:.6f} rad = {math.degrees(golden):.2f}°")
# 2,399963 rad. La phyllotaxie dorée évite que les étages de branches
# s'alignent : c'est la valeur par défaut, PAS un nombre magique caché.
assert abs(golden - 2.399963) < 1e-6

print("\n=== 3. Tropisme (redressement vers le haut) ===")
# direction' = normalize(dir·(1−s) + haut·s). Le nlerp suffit : pour
# s ∈ [0, 1] le résultat interpole l'angle de façon monotone. Vérification
# numérique sur une branche à 60° du zénith :
up = (0.0, 1.0, 0.0)
d0 = (math.sin(math.radians(60)), math.cos(math.radians(60)), 0.0)
print(f"  {'s':>5} {'angle au zénith':>16}")
prev = 61.0
for s in (0.0, 0.25, 0.5, 0.75, 1.0):
    v = tuple(d0[i] * (1 - s) + up[i] * s for i in range(3))
    n = math.sqrt(sum(c * c for c in v))
    ang = math.degrees(math.acos(max(-1.0, min(1.0, v[1] / n))))
    assert ang <= prev + 1e-9, "le nlerp devrait être monotone"
    prev = ang
    print(f"  {s:>5.2f} {ang:>14.1f}°")
print("  → monotone, s=1 colle au zénith : nlerp retenu, pas besoin de slerp.")

print("\n=== 4. Continuité des rayons ===")
# rayon(profondeur) = rayon_tronc · ratio^profondeur, et au sein d'un
# segment rayon_pointe = rayon_base · ratio : la pointe du parent et la
# base de l'enfant portent LE MÊME rayon — aucune marche visible au raccord.
r0, ratio = 0.30, 0.62
for depth in range(4):
    base = r0 * ratio ** depth
    print(f"  profondeur {depth} : base {base*100:6.2f} cm → pointe {base*ratio*100:6.2f} cm"
          f"  (= base de la profondeur {depth+1})")
# Da Vinci : la section se conserve à peu près quand ratio² · k ≈ 1.
for k in (2, 3, 4):
    print(f"  k={k} : conservation de section pour ratio ≈ {1/math.sqrt(k):.3f}")
print("  → le défaut d'espèce-test (k=3) prendra ratio ≈ 0,60 : bois plausible.")

print("\n=== 5. Coût du fil de fer (console `arbre`) ===")
segs = count(3, 5)
floats = segs * 2 * 6          # deux sommets par segment, pos+couleur
print(f"  arbre de test k=3 D=5 : {segs} segments → {floats*4/1024:.0f} ko de VBO,")
print(f"  {segs*2:,} sommets en GL_LINES — négligeable, un seul arbre à la fois.")

print("""
CONCLUSION. Grammaire : un enfant « continuation » (angle réduit à 25 %)
plus k−1 branches à l'angle nominal ± dispersion, azimut en phyllotaxie
dorée, tropisme nlerp, longueurs et rayons en progression géométrique,
enfants à la POINTE du parent (l'attache le long du fût attendra qu'une
espèce du 3.2 la réclame). Garde à 20 000 segments par REFUS déterministe.
Tout le calcul dans :sim, testé ; :app ne fait que des lignes colorées.""")
