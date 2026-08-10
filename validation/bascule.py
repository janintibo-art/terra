"""Lot 2.7-b — bascule orbite → globe : instruction chiffrée AVANT le code.

LE BUT. Au-dessus du registre orbite, remplacer le quadtree (limbe polygonal,
122 px d'erreur à 1 000 km) par l'icosphère raffinée du mode Globe, ronde par
construction. L'état du projet exige d'instruire avant de s'engager, et la
leçon v0.38.1 rappelle pourquoi : chiffrer une parade peut l'invalider.

CE QUE LA LECTURE DU CODE A APPRIS (et qui change le plan) :

  1. Le globe contemplatif vit dans SON repère : sphère en unités de planète
     (1,0 = mer), caméra à distance 0,02..60, relief EXAGÉRÉ
     (renderRadius = 1 + (a/7000)·0,055). La descente est métrique, relief
     VRAI. La bascule ne peut donc pas être un simple changement de scène :
     il faut rendre le maillage du globe DANS le repère métrique de la
     descente, en relief vrai, sinon la caméra saute et les montagnes
     s'aplatissent au passage.
  2. Les couleurs du globe sont PAR CELLULE (plus proche voisin) ; les tuiles
     peignent en continu. La couture des couleurs est le vrai sujet.
  3. Le limbe reste polygonal SOUS l'orbite : le registre continental
     (180–2 000 km) garde des erreurs de dizaines de pixels. Basculer à
     2 000 km ne répare qu'une partie du symptôme — à mesurer ci-dessous.

Sections : 1. précision float32 en orbite · 2. relief vrai depuis le VBO
exagéré · 3. budgets (mémoire, triangles) · 4. ce que la bascule répare et
ce qu'elle laisse · 5. la couture des couleurs · 6. la collerette (globe
restreint au limbe) comme piste pour le continental.
"""
import math

R = 6_371_000.0
FOV_RAD = 0.733038
SCREEN_H = 1080.0
PX_PER_RAD = (SCREEN_H / 2) / math.tan(FOV_RAD / 2)

MAX_ALT_M = 7_000.0
EXAGGERATION = 0.055
GLOBE_LEVEL = 6                       # plafond de GlobeRefinement
FACES = 20 * 4 ** GLOBE_LEVEL         # 81 920
SHARED_VERTS = 10 * 4 ** GLOBE_LEVEL + 2

ORBIT_ENTER_M = 2_000_000.0 * 1.12    # bascule en montée (hystérésis 2.7-a)
ORBIT_EXIT_M = 2_000_000.0 / 1.12     # retour aux tuiles en descente

def limb_slant(alt):
    return math.sqrt((R + alt) ** 2 - R ** 2)

print("=== 1. Précision float32 du globe métrique en orbite ===")
# Le maillage serait en float32 dans le repère PLANÉTAIRE (positions ~6,4e6 m),
# la translation caméra-relative faite en DOUBLE sur CPU dans la matrice.
# L'erreur absolue d'un float32 à cette magnitude est le chiffre de la leçon :
ulp = R * 2.0 ** -23
print(f"  quantum float32 à 6 371 km : {ulp:.2f} m — le double du 0,38 m de la")
print("  leçon eau v0.34.1 (elle mesurait le demi-quantum de l'arrondi ; on")
print("  prend ici le quantum plein, borne conservatrice du même phénomène)")
# En orbite, cette erreur se projette à une distance ≥ celle du limbe au
# point de sortie du registre (le pire cas : le plus près qu'on puisse être
# du terrain en régime globe).
d_min = ORBIT_EXIT_M                  # le nadir est plus près que le limbe
err_px = ulp / d_min * PX_PER_RAD
print(f"  au plus près (nadir à {d_min/1000:.0f} km) : {err_px:.5f} px")
assert err_px < 0.01, "le float32 planétaire tremblerait en orbite"
print("  → l'invariant n°5 (double pour toute position métrique) est respecté")
print("    LÀ OÙ IL MORD : la chaîne caméra→matrice reste en double ; le VBO")
print("    float32 est légitime car son erreur est 1 000 fois sous le pixel.")
print("    Le même VBO au sol serait interdit — c'est le registre qui protège.")

print("\n=== 2. Relief vrai reconstruit depuis le VBO exagéré ===")
# Plutôt qu'un second VBO métrique (+10 Mo), le vertex shader peut
# DÉS-exagérer : a = (r_e − 1)·7000/0,055, puis rayon vrai = R + a.
# Danger classique : (r_e − 1) est une soustraction de presque-égaux.
# En vertex shader GLES2, highp float EST garanti (la leçon v0.31.3 ne
# concernait que le fragment) : erreur absolue sur r_e ≈ 2^-23 à r_e≈1.
err_re = 2.0 ** -23
err_alt = err_re * MAX_ALT_M / EXAGGERATION
err_radius_px = err_alt / d_min * PX_PER_RAD
print(f"  erreur sur (r_e − 1) : {err_re:.1e} → erreur d'altitude "
      f"{err_alt:.2f} m → {err_radius_px:.4f} px au plus près")
assert err_radius_px < 0.05, "la dés-exagération en shader est trop bruitée"
# Et R + a en float32 : quantum 0,5 m, déjà compté en section 1.
print("  → un SEUL VBO partagé avec le mode contemplatif, dés-exagéré par un")
print("    uniforme dans le shader ; la formule vivra dans :sim, testée, le")
print("    shader n'en étant que le miroir (corollaire de l'état du projet).")

print("\n=== 3. Budgets ===")
verts_dup = FACES * 3
vbo_mb = verts_dup * 10 * 4 / 1e6
print(f"  VBO existant réutilisé : {FACES} faces, {verts_dup} sommets "
      f"dupliqués × 10 flottants = {vbo_mb:.1f} Mo (déjà en mémoire, +0)")
# Triangles : le quadtree en orbite vs le globe.
TILE_TRIS = 2 * 16 * 16               # MESH_N = 16
tiles_orbit = 200                      # ordre de grandeur HUD en orbite
print(f"  quadtree en orbite : ~{tiles_orbit} tuiles × {TILE_TRIS} tris = "
      f"~{tiles_orbit * TILE_TRIS:,} tris")
print(f"  globe niveau 6     : {FACES:,} tris")
print(f"  → le globe coûte ~{FACES / (tiles_orbit * TILE_TRIS):.1f}× le quadtree")
print("    en triangles, mais SANS sélection, sans morphing, sans génération")
print("    de tuiles en tâche de fond : en orbite, la file de tuiles se vide.")
print("    À vérifier au HUD sur Mali-G77 ; 82 k tris est modeste en 2026.")

print("\n=== 4. Ce que la bascule répare, ce qu'elle laisse ===")
def quadtree_limb_err_px(alt):
    d = limb_slant(alt)
    level = 0
    while ((math.pi / 2) / 2 ** level) * R / d > 1.4:
        level += 1
    arc = (math.pi / 2) / 2 ** level
    sag = R * (1.0 - math.cos(arc / 2.0))
    return sag / d * PX_PER_RAD

ICO_EDGE_RAD = math.sqrt(4 * math.pi / FACES * 2)
ico_sag = R * (1.0 - math.cos(ICO_EDGE_RAD / 2.0))
print(f"  {'altitude':>9} {'registre':>12} {'limbe quadtree':>15} {'limbe globe':>12}")
for alt in (200e3, 500e3, 1_000e3, 1_785e3, 2_240e3, 5_000e3, 20_000e3):
    reg = ("continental" if alt < 2e6 else "orbite")
    q = quadtree_limb_err_px(alt)
    g = ico_sag / limb_slant(alt) * PX_PER_RAD
    marker = "  ← bascule" if abs(alt - ORBIT_EXIT_M) < 1 or abs(alt - ORBIT_ENTER_M) < 1 else ""
    print(f"  {alt/1000:>7.0f}km {reg:>12} {q:>12.0f} px {g:>9.2f} px{marker}")
print("  → au-dessus de la bascule le limbe devient rond (< 0,2 px). MAIS le")
print("    registre continental garde 30 à 140 px d'erreur : la bascule NE")
print("    RÉPARE PAS tout le symptôme. Le point ouvert devra le dire.")

print("\n=== 5. La couture des couleurs, chiffrée ===")
GRID_CELLS = 10_242
cell_radius_m = math.sqrt(4 * math.pi / GRID_CELLS) / 2 * R
for alt in (ORBIT_EXIT_M, ORBIT_ENTER_M):
    px = 2 * cell_radius_m / alt * PX_PER_RAD
    print(f"  à {alt/1e6:.2f} Mm, une cellule couvre ~{px:.0f} px au nadir")
print("  → au point de bascule, passer des teintes continues des tuiles aux")
print("    aplats de ~140-180 px du globe CLAQUERA. Trois parades possibles,")
print("    à départager SUR APPAREIL (leçon v0.37.0 : instrumenter d'abord) :")
print("    a) fondu temporel entre les deux rendus (coût : dessiner les deux")
print("       pendant ~1 s, tri de profondeur délicat) ;")
print("    b) peindre le globe métrique avec l'échantillonnage CONTINU des")
print("       tuiles (une passe de couleurs à refaire, ~41 k sommets — mais")
print("       ce serait peindre une précision que le mode contemplatif")
print("       refuse par principe ; à cantonner au chemin métrique) ;")
print("    c) assumer la bascule sèche, voilée par le halo d'altitude.")

print("\n=== 6. La collerette : globe restreint au limbe, sous l'orbite ===")
# Pour le continental : dessiner le globe AVANT les tuiles, mais seulement
# ses faces proches du limbe ; les tuiles recouvrent le reste. La corde des
# tuiles grossières s'enfonce sous l'arc : au limbe, le globe dépasse et
# rebouche la silhouette. Combien de faces, et quelle épaisseur à l'écran ?
BAND_RAD = 0.06
for alt in (300e3, 1_000e3, 1_785e3):
    r_ratio = R / (R + alt)
    theta_max = math.acos(r_ratio)
    frac = (math.cos(max(0.0, theta_max - BAND_RAD)) - math.cos(theta_max)) / 2
    faces_band = FACES * frac
    # Épaisseur apparente : la bande vue par la tranche se projette environ
    # sur sa flèche par rapport à la sphère, ~ R·(1−cos(band)) / distance.
    thick_px = R * (1 - math.cos(BAND_RAD)) / limb_slant(alt) * PX_PER_RAD
    print(f"  à {alt/1000:>5.0f} km : ~{faces_band:.0f} faces dans la bande, "
          f"frange ≈ {thick_px:.0f} px à l'écran")
print("  → quelques milliers de faces, une frange de quelques dizaines de")
print("    pixels déjà baignée par le halo du limbe. La couture des couleurs")
print("    y serait confinée. PRIX : une sélection de faces par image (test")
print("    de 82 k centres, trivial) et un biais de profondeur à régler pour")
print("    que les tuiles gagnent partout ailleurs — c'est le point délicat,")
print("    à juger sur appareil, pas sur le papier.")

print("\n=== 7. Biais de la collerette (calibrage 2.7-b1) ===")
# La collerette se dessine avant les tuiles ; le biais radial doit dépasser
# le quantum du tampon de profondeur là où les surfaces coïncident, sans
# abaisser visiblement la silhouette. Quantum perspective 24 bits ≈
# d²/(near·2²⁴), near plafonné à 5 000 m en orbite (nearPlaneFor).
NEAR = 5_000.0
print(f"  {'altitude':>9} {'limbe':>9} {'quantum z':>10} {'biais 2q':>9} {'silhouette':>10}")
for alt in (200e3, 300e3, 700e3, 1_785e3, 5_000e3):
    slant = limb_slant(alt)
    q = slant * slant / (NEAR * 2 ** 24)
    bias = 2 * q
    sil = bias / slant * PX_PER_RAD
    assert sil < 0.5, f"silhouette abaissée de {sil:.2f} px à {alt} m"
    print(f"  {alt/1e3:>7.0f}km {slant/1e3:>7.0f}km {q:>8.1f} m {bias:>7.1f} m {sil:>8.2f}px")
print("  → biais(alt) = 2·limbe²/(near·2²⁴) : tranche le z-fighting et reste")
print("    sous le demi-pixel de 200 à 6 000 km — le domaine d'usage de la")
print("    collerette. Au-delà, le mode utile est « globe » entier ; le mode")
print("    collerette y abaisserait la silhouette jusqu'à ~1 px, assumé pour")
print("    un outil de diagnostic. Miroir Kotlin : GlobeMetric.collarBiasM.")

print("""
CONCLUSION D'INSTRUCTION — découpage proposé :

  2.7-b1 (instrumentation, sans bascule automatique) : rendre le globe
  métrique dans le repère de la descente — VBO partagé, dés-exagération en
  shader (formule testée dans :sim), couleurs par cellule pour commencer —
  et une commande console `limbe tuiles|globe|collerette` pour comparer les
  trois rendus sur appareil, captures à l'appui. AUCUN comportement ne
  change sans la console : risque quasi nul pour l'existant.

  2.7-b2 (la décision) : d'après les captures, trancher le mécanisme
  définitif (bascule sèche, fondu, ou peinture continue) et le câbler sur le
  registre orbite du 2.7-a ; statuer sur la collerette pour le continental
  ou l'inscrire aux points ouverts.

Les chiffres valident la faisabilité (float32 sous le centième de pixel,
mémoire +0, dés-exagération saine) et désignent le vrai risque : la couture
des couleurs, qui ne se tranche pas sans écran.""")
