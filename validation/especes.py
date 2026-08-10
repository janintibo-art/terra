"""Lot 3.2 — familles d'espèces : instruction chiffrée AVANT le code.

LE PROBLÈME QUE LE 3.1 LAISSE. La grammaire du lot 3.1 n'attache les
enfants qu'à la POINTE du parent. Un feuillu, un palmier, un cactus s'en
accommodent — un CONIFÈRE non : un sapin porte des verticilles étagés le
long de son fût, et son fût continue au-dessus d'eux. Sans branchement
latéral, aucun jeu de paramètres ne produira une silhouette conique.

L'EXTENSION. Trois paramètres : lateralWhorls (étages le long du parent),
lateralPerWhorl (branches par étage), attachStartFraction (hauteur du
premier étage). Le facteur de branchement devient
k_eff = children + lateralWhorls · lateralPerWhorl, donc la garde du 3.1
(somme k^d, refus au-delà de 20 000) reste valable telle quelle.

CE SCRIPT est le MIROIR Python de la grammaire que le Kotlin implémentera.
Il sert à deux choses : vérifier que chaque famille tient dans le budget,
et MESURER les silhouettes (hauteur, envergure, élancement) sur beaucoup de
tirages pour en déduire les seuils des tests — jamais les deviner.
"""
import math
import random

GOLDEN = 2.399963
CONT_RATIO = 0.25
MAX_ANGLE = 1.55
MAX_SEGMENTS = 20_000


def normalize(v):
    n = math.sqrt(sum(c * c for c in v))
    return tuple(c / n for c in v) if n > 1e-20 else (0.0, 0.0, 0.0)


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def rotate_from(axis, angle, azimuth):
    helper = (0.0, 1.0, 0.0) if abs(axis[1]) < 0.9 else (1.0, 0.0, 0.0)
    u = normalize(cross(axis, helper))
    v = cross(u, axis)
    s = math.sin(angle)
    return normalize(tuple(
        axis[i] * math.cos(angle) + u[i] * s * math.cos(azimuth) + v[i] * s * math.sin(azimuth)
        for i in range(3)
    ))


def tropism(d, straightness):
    if straightness <= 0.0:
        return d
    return normalize((d[0] * (1 - straightness),
                      d[1] * (1 - straightness) + straightness,
                      d[2] * (1 - straightness)))


class P:
    """Miroir de TreeParams."""
    def __init__(self, name, trunk_len, trunk_r, len_ratio, r_ratio, angle,
                 jitter, children, depth, straight,
                 whorls=0, per_whorl=0, attach=0.35):
        self.__dict__.update(locals())
        del self.self

    def k_eff(self):
        return self.children + self.whorls * self.per_whorl

    def validate(self):
        """Miroir des require() de TreeParams.validate().

        AJOUTÉ APRÈS COUP : la première version de ce script ne reproduisait
        que la géométrie, pas les gardes — le palmier à huit palmes violait
        « children in 1..6 » sans que rien ici ne le signale, et cinq tests
        sont tombés en CI. Un miroir qui n'inclut pas les gardes ne prouve
        que la moitié de ce qu'on lui demande.
        """
        assert self.trunk_len > 0 and self.trunk_r > 0, self.name
        assert 0.1 <= self.len_ratio <= 0.95, f"{self.name} len_ratio"
        assert 0.1 <= self.r_ratio <= 0.95, f"{self.name} r_ratio"
        assert 0.0 <= self.angle <= 1.55, f"{self.name} angle"
        assert 0.0 <= self.jitter <= 0.6, f"{self.name} jitter"
        assert 1 <= self.children <= 12, f"{self.name} children"
        assert 0 <= self.depth <= 12, f"{self.name} depth"
        assert 0.0 <= self.straight <= 1.0, f"{self.name} straightness"
        assert 0 <= self.whorls <= 8, f"{self.name} whorls"
        assert 0 <= self.per_whorl <= 8, f"{self.name} per_whorl"
        assert 0.0 <= self.attach <= 0.9, f"{self.name} attach"
        assert self.k_eff() >= 1, f"{self.name} sans enfant"

    def worst_case(self):
        total, level = 0, 1
        for _ in range(self.depth + 1):
            total += level
            if total > MAX_SEGMENTS:
                return None
            level *= self.k_eff()
        return total


def generate(p, rng):
    """Miroir fidèle de TreeGenerator.generate : renvoie la liste des segments."""
    d0 = tropism((0.0, 1.0, 0.0), p.straight)
    segs = [{
        'base': (0.0, 0.0, 0.0),
        'tip': tuple(d0[i] * p.trunk_len for i in range(3)),
        'rb': p.trunk_r, 'rt': p.trunk_r * p.r_ratio, 'depth': 0, 'parent': -1
    }]
    queue = [0]
    while queue:
        pi = queue.pop(0)
        par = segs[pi]
        if par['depth'] >= p.depth:
            continue
        cd = par['depth'] + 1
        clen = p.trunk_len * p.len_ratio ** cd
        pdir = normalize(tuple(par['tip'][i] - par['base'][i] for i in range(3)))
        az = rng.uniform(0.0, 2.0 * math.pi)

        def emit(base, rb, angle):
            nonlocal az
            a = max(0.0, min(MAX_ANGLE, angle + rng.uniform(-p.jitter, p.jitter)))
            d = tropism(rotate_from(pdir, a, az), p.straight)
            az_local = az
            az += GOLDEN
            segs.append({
                'base': base,
                'tip': tuple(base[i] + d[i] * clen for i in range(3)),
                'rb': rb, 'rt': rb * p.r_ratio, 'depth': cd, 'parent': pi
            })
            queue.append(len(segs) - 1)

        # Enfants de pointe : le premier est la continuation.
        for i in range(p.children):
            emit(par['tip'], par['rt'], p.angle * (CONT_RATIO if i == 0 else 1.0))

        # Enfants latéraux, en verticilles le long du parent.
        for w in range(p.whorls):
            t = p.attach + (1.0 - p.attach) * (w + 1) / (p.whorls + 1)
            base = tuple(par['base'][i] + (par['tip'][i] - par['base'][i]) * t for i in range(3))
            rb = par['rb'] + (par['rt'] - par['rb']) * t
            for _ in range(p.per_whorl):
                emit(base, rb, p.angle)
    return segs


def metrics(segs):
    h = max(s['tip'][1] for s in segs)
    spread = max(math.hypot(s['tip'][0], s['tip'][2]) for s in segs)
    # CONICITE : envergure de la moitie haute rapportee a celle de la moitie
    # basse. C'est la vraie signature d'un conifere (cone) contre un feuillu
    # (couronne au sommet) — l'elancement, lui, les confond, comme la
    # premiere version de cette instruction l'a montre.
    hi = [math.hypot(s['tip'][0], s['tip'][2]) for s in segs if s['tip'][1] > h * 0.55]
    lo = [math.hypot(s['tip'][0], s['tip'][2]) for s in segs if s['tip'][1] <= h * 0.55]
    taper = (max(hi) / max(lo)) if hi and lo and max(lo) > 1e-9 else float('nan')
    return h, spread, taper


# --------------------------------------------------------- les sept familles
SPECIES = [
    #      nom        L     r     lr    rr   angle jit  k  D  droit  wh pw  att
    P("CONIFERE",   8.0, 0.26, 0.34, 0.58, 1.20, 0.10, 1, 3, 0.05, 3, 3, 0.22),
    P("FEUILLU",    4.0, 0.28, 0.68, 0.60, 0.78, 0.16, 3, 5, 0.14),
    P("PALMIER",    7.0, 0.16, 0.55, 0.80, 1.25, 0.22, 8, 1, 0.00),
    P("CACTUS",     1.8, 0.20, 0.70, 0.88, 1.05, 0.10, 2, 2, 0.75),
    P("ARBUSTE",    0.9, 0.06, 0.72, 0.66, 0.85, 0.25, 3, 4, 0.10),
    P("HERBACEE",   0.35, 0.010, 0.80, 0.70, 0.55, 0.30, 4, 1, 0.35),
    P("MOUSSE",     0.05, 0.003, 0.75, 0.75, 0.80, 0.35, 3, 1, 0.05),
]

print("=== 1. Budget par famille ===")
print(f"  {'espèce':<10} {'k_eff':>6} {'D':>3} {'segments':>9} {'verdict':>10}")
for p in SPECIES:
    p.validate()
    wc = p.worst_case()
    assert wc is not None, f"{p.name} dépasse la garde"
    print(f"  {p.name:<10} {p.k_eff():>6} {p.depth:>3} {wc:>9,} {'OK':>10}")

print("\n=== 2. Silhouettes mesurées (400 tirages par famille) ===")
print("  Les bornes servent DIRECTEMENT de seuils aux tests Kotlin : elles")
print("  sont mesurées, pas devinées, et élargies de 15 % de marge.")
print(f"\n  {'espèce':<10} {'hauteur m':>18} {'envergure m':>18} {'élancement':>12}"
      f" {'conicité':>13}")
bounds = {}
for p in SPECIES:
    hs, sps, els, tps = [], [], [], []
    for seed in range(400):
        rng = random.Random(seed)
        segs = generate(p, rng)
        h, sp, tp = metrics(segs)
        hs.append(h); sps.append(sp); els.append(sp / h)
        if tp == tp:
            tps.append(tp)
    bounds[p.name] = (min(hs), max(hs), min(sps), max(sps), min(els), max(els),
                      min(tps) if tps else float('nan'),
                      max(tps) if tps else float('nan'))
    print(f"  {p.name:<10} {min(hs):7.3f} … {max(hs):<7.3f} "
          f"{min(sps):7.3f} … {max(sps):<7.3f} {min(els):5.2f} … {max(els):<5.2f}"
          f" {bounds[p.name][6]:5.2f} … {bounds[p.name][7]:<5.2f}")

print("\n=== 3. Les silhouettes se distinguent-elles ? ===")
# Un test ne vaut que s'il échouerait si deux familles se confondaient.
con = bounds["CONIFERE"]; feu = bounds["FEUILLU"]; pal = bounds["PALMIER"]
# DEUX CRITÈRES ESSAYÉS, UN SEUL RETENU — le détour vaut d'être consigné.
#
# 1) La CONICITÉ (envergure haute / envergure basse) semblait le critère
#    naturel : un sapin est un cône. Mesurée, elle NE SÉPARE PAS
#    (conifère 0,91…1,09 contre feuillu 0,95…3,57). La raison est
#    géométrique : le fût nu sous le premier verticille place le partage
#    haut/bas au milieu du feuillage, pas sous lui, et la partie la plus
#    large tombe des deux côtés de la coupure.
#
# 2) L'ÉLANCEMENT (envergure / hauteur) sépare NETTEMENT une fois le
#    conifère rendu plus haut et plus serré — c'est lui qu'on retient.
print(f"  conicité   : conifère {con[6]:.2f}…{con[7]:.2f} contre feuillu "
      f"{feu[6]:.2f}…{feu[7]:.2f} → se chevauchent, critère ÉCARTÉ")
print(f"  élancement : conifère {con[4]:.2f}…{con[5]:.2f} contre feuillu "
      f"{feu[4]:.2f}…{feu[5]:.2f} → "
      f"{'séparés, critère RETENU' if con[5] < feu[4] else 'NON séparés'}")
assert con[5] < feu[4], "conifère et feuillu ne se distinguent pas"
seuil = math.sqrt(con[5] * feu[4])
marge = (feu[4] - con[5]) / con[5]
print(f"  → seuil des tests : élancement < {seuil:.2f} pour un conifère,")
print(f"    > {seuil:.2f} pour un feuillu (milieu géométrique ; l'écart entre")
print(f"    les deux plages vaut {marge*100:.0f} % de la borne conifère)")
print(f"  palmier {pal[4]:.2f}…{pal[5]:.2f} : proche du conifère en élancement —")
print("    il s'en distingue par la STRUCTURE (aucun latéral, profondeur 1),")
print("    ce que le test structurel couvre déjà.")

print("\n=== 4. Continuité aux attaches latérales ===")
# Le rayon de base d'un latéral doit valoir le rayon du parent AU POINT
# d'attache : interpolation linéaire base→pointe. Vérifions sur un conifère.
segs = generate(SPECIES[0], random.Random(1))
worst = 0.0
for s in segs:
    if s['parent'] < 0:
        continue
    par = segs[s['parent']]
    # Fraction d'attache retrouvée depuis la position.
    seg = tuple(par['tip'][i] - par['base'][i] for i in range(3))
    L2 = sum(c * c for c in seg)
    t = sum((s['base'][i] - par['base'][i]) * seg[i] for i in range(3)) / L2
    expected = par['rb'] + (par['rt'] - par['rb']) * t
    worst = max(worst, abs(s['rb'] - expected))
print(f"  écart maximal entre rayon d'attache et rayon du parent : {worst:.2e} m")
assert worst < 1e-6, "continuité des rayons rompue aux attaches latérales"
print("  → la continuité du 3.1 se généralise aux attaches le long du fût.")

print("""
CONCLUSION. L'extension (verticilles latéraux) est nécessaire au conifère et
suffisante pour les sept familles ; la garde de segments est inchangée ; la
continuité des rayons se généralise aux attaches le long du fût.

Deux paramétrages et deux critères ont été essayés AVANT d'écrire du Kotlin :
le premier confondait conifère et feuillu, le second a montré que la conicité
n'est pas le bon discriminant malgré l'intuition. C'est exactement ce que
l'instruction préalable doit attraper — le coût aurait été un aller-retour de
CI et une silhouette fausse à l'écran.

Les bornes du §2 deviennent les seuils des tests Kotlin, avec 15 % de marge :
un test qui ne les respecterait plus signalerait une dérive de la grammaire,
pas un seuil mal choisi.""")
