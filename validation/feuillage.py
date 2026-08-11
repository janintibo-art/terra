"""Lot 3.3-c — feuillage : instruction chiffrée AVANT le code.

LE TROU DU PLAN. La feuille de route n'a aucun lot qui CRÉE du feuillage :
le 3.3 ne parle que de niveaux de détail, le 3.4 de coloration, le 3.8 du
cycle saisonnier — tous supposent un feuillage qui n'existe nulle part.
Sans lui, le feuillu restera un arbre d'hiver.

LA CONTRAINTE PROPRE À TERRA. Aucune texture dans le projet : ni image
embarquée, ni atlas. Les cartes de feuilles à découpe alpha, solution
habituelle, sont donc hors jeu — une carte sans texture est un rectangle
opaque. Le feuillage doit être GÉOMÉTRIQUE, et le parti facetté déjà
assumé ailleurs (mosaïque du globe, cerfs-volants de la végétation
existante) s'y prête.

Sections : 1. choix de la forme · 2. où poser les touffes · 3. budget ·
4. taille des touffes · 5. le cas du conifère.
"""
import math

# Squelettes du lot 3.2 : segments par profondeur (k_eff^d) et profondeur max.
SPECIES = {
    #            k_eff  D    rayon tronc  longueur tronc  ratio longueur
    "CONIFERE": (10, 3, 0.26, 8.0, 0.34),
    "FEUILLU": (3, 5, 0.28, 4.0, 0.68),
    "PALMIER": (8, 1, 0.16, 7.0, 0.55),
    "CACTUS": (2, 2, 0.20, 1.8, 0.70),
    "ARBUSTE": (3, 4, 0.06, 0.9, 0.72),
    "HERBACEE": (4, 1, 0.010, 0.35, 0.80),
    "MOUSSE": (3, 1, 0.003, 0.05, 0.75),
}


def segments_at(k, depth):
    return k ** depth


def total_segments(k, d):
    return sum(k ** i for i in range(d + 1))


print("=== 1. Choix de la forme d'une touffe ===")
# Trois candidats, sans texture :
FORMS = {
    "cartes croisées (2 quads)": 4,
    "tétraèdre": 4,
    "octaèdre": 8,
    "icosaèdre": 20,
}
for name, tris in FORMS.items():
    print(f"  {name:<26} {tris:>3} triangles")
print("  → les cartes croisées sont EXCLUES : sans texture alpha, ce sont")
print("    deux rectangles opaques qui se croisent, lisibles comme tels.")
print("  → le tétraèdre est trop anguleux pour lire comme un volume de")
print("    feuilles ; l'icosaèdre coûte 2,5× l'octaèdre pour un gain que la")
print("    distance d'observation ne montrera pas.")
print("  RETENU : l'OCTAÈDRE (8 triangles, 6 sommets), aux trois demi-axes")
print("  réglables — allongé le long du rameau pour un conifère, presque")
print("  sphérique pour un feuillu.")

print("\n=== 2. Où poser les touffes ===")
# Sur les segments les plus fins : les N derniers niveaux de profondeur.
print(f"  {'espèce':<10} {'D':>2} {'terminaux':>10} {'2 derniers niveaux':>20}")
for name, (k, d, _, _, _) in SPECIES.items():
    last = segments_at(k, d)
    two = segments_at(k, d) + (segments_at(k, d - 1) if d >= 1 else 0)
    print(f"  {name:<10} {d:>2} {last:>10,} {two:>20,}")
print("  → le feuillu n'a que 243 rameaux terminaux : les garnir seuls")
print("    donnerait une couronne clairsemée. Deux niveaux (324 touffes)")
print("    remplissent le volume. Le conifère en a déjà 1 000 sur le seul")
print("    dernier niveau : un seul suffit, et deux coûteraient 8 800")
print("    triangles de plus pour un feuillage déjà dense.")

print("\n=== 3. Budget ===")
OCTA_TRIS = 8
# Triangles du bois (lot 3.3-a) : courants 2N, terminaux N, N = 8 côtés.
SPAN = {"CONIFERE": 1, "FEUILLU": 2, "PALMIER": 1, "CACTUS": 0,
        "ARBUSTE": 2, "HERBACEE": 1, "MOUSSE": 1}
print(f"  {'espèce':<10} {'bois':>8} {'touffes':>9} {'feuillage':>10} "
      f"{'total':>9} {'Mo':>6}")
totals = {}
for name, (k, d, _, _, _) in SPECIES.items():
    total = total_segments(k, d)
    terminal = segments_at(k, d)
    wood = (total - terminal) * 2 * 8 + terminal * 8
    span = SPAN[name]
    clusters = sum(segments_at(k, d - i) for i in range(span)) if span else 0
    foliage = clusters * OCTA_TRIS
    tris = wood + foliage
    totals[name] = tris
    mo = tris * 3 * 9 * 4 / 1e6
    print(f"  {name:<10} {wood:>8,} {clusters:>9,} {foliage:>10,} "
          f"{tris:>9,} {mo:>6.2f}")
assert totals["CONIFERE"] < 20_000, "le conifère dépasserait le budget d'un arbre"
print("  → aucun arbre ne dépasse 20 000 triangles. Le feuillage ajoute")
print(f"    {totals['FEUILLU'] - 3880:,} triangles au feuillu et "
      f"{totals['CONIFERE'] - 9776:,} au conifère.")
print("    Rappel du 3.3-a : à cent arbres proches on dépasse le rendu du")
print("    terrain entier. Le feuillage ne change pas ce constat, il le")
print("    renforce — le lot 3.3-b reste la condition du 3.5.")

print("\n=== 4. Taille des touffes ===")
# Une touffe doit couvrir son rameau et rejoindre ses voisines, sinon la
# couronne fait « boules détachées ». Le bon repère est la LONGUEUR du
# segment qui la porte : la touffe vaut un multiple de cette longueur.
for name in ("CONIFERE", "FEUILLU", "ARBUSTE"):
    k, d, r0, l0, lr = SPECIES[name]
    seg_len = l0 * lr ** d
    print(f"  {name:<9} rameau terminal {seg_len:.3f} m ; touffe à 0,9× = "
          f"{0.9 * seg_len:.3f} m de demi-longueur")
    # Écartement typique entre deux rameaux frères : ils divergent de
    # l'angle de branchement sur la longueur du segment.
    angle = {"CONIFERE": 1.20, "FEUILLU": 0.78, "ARBUSTE": 0.85}[name]
    gap = 2 * seg_len * math.sin(angle / 2)
    print(f"            écart entre frères {gap:.3f} m → recouvrement "
          f"{'OUI' if 2 * 0.9 * seg_len > gap else 'NON'}")
print("  → à 0,9× la longueur du rameau, les touffes voisines se")
print("    recouvrent : la couronne se lit comme une masse, pas comme un")
print("    chapelet de billes.")

print("\n=== 5. Le conifère, cas particulier ===")
# Ses rameaux terminaux sont horizontaux (angle 1,20 rad) et courts. Une
# touffe sphérique en ferait un arbre à pompons. Il lui faut un volume
# ALLONGÉ le long du rameau, et aplati verticalement — une palme d'aiguilles.
k, d, r0, l0, lr = SPECIES["CONIFERE"]
seg = l0 * lr ** d
print(f"  rameau terminal : {seg:.3f} m, presque horizontal")
print(f"  touffe proposée : {1.15 * seg:.3f} m le long du rameau, "
      f"{0.45 * seg:.3f} m de large, {0.18 * seg:.3f} m d'épaisseur")
print(f"  rapport d'aplatissement {1.15 / 0.18:.1f}:1 — une palme, pas une bille")

print("""
CONCLUSION. Une touffe = un OCTAÈDRE à trois demi-axes réglables, posée au
milieu de chaque segment des N derniers niveaux (N = 2 pour les feuillus et
arbustes, 1 ailleurs), orientée sur le rameau qui la porte, dimensionnée en
multiple de la longueur de ce rameau pour que les voisines se recouvrent.
Le cactus n'en reçoit aucune. Budget maximal : 17 776 triangles pour un
conifère, dont 8 000 de feuillage.""")
