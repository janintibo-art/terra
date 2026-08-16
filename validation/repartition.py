"""Lot 3.6 — répartition de la végétation : instruction AVANT le code.

CE QUI EXISTE DÉJÀ. TileMesh.plantDensity(biome) donne une densité par
biome (calibrée, à l'écran depuis la v0.26) ; la pente au-delà de 27 % et
l'eau excluent déjà les plantes. Ce lot n'y touche pas : il ajoute la
question QUELLES ESPÈCES, et un tirage déterministe par position.

LA RÈGLE DU LOT. Les densités existantes sont DÉPLACÉES telles quelles
(TileMesh déléguera), pas modifiées : changer les valeurs changerait
l'aspect de tous les mondes sans raison. Seule addition visible plus tard :
les mélanges, consommés par l'instanciation du lot 3.5.

Ce script est le MIROIR des mélanges Kotlin. Il vérifie :
1. la couverture (tout biome à densité > 0 a un mélange, somme = 1) ;
2. la vraisemblance écologique (conifères au nord, cactus au sec…) ;
3. le tirage cumulatif et sa TOLÉRANCE DE TEST, mesurée et non devinée.
"""
import math

# --- densités EXISTANTES (TileMesh.plantDensity, recopiées à l'identique)
DENSITY = {
    "RAINFOREST": 1.0, "TEMPERATE_FOREST": 0.9, "BOREAL_FOREST": 0.8,
    "WETLAND": 0.6, "GRASSLAND": 0.5, "SAVANNA": 0.35, "STEPPE": 0.25,
    "TUNDRA": 0.12, "SEMI_DESERT": 0.08,
}

# --- mélanges proposés (miroir de VegetationRules.mixFor)
MIX = {
    "RAINFOREST": [("FEUILLU", 0.62), ("PALMIER", 0.23), ("ARBUSTE", 0.15)],
    "TEMPERATE_FOREST": [("FEUILLU", 0.62), ("CONIFERE", 0.23), ("ARBUSTE", 0.15)],
    "BOREAL_FOREST": [("CONIFERE", 0.80), ("ARBUSTE", 0.12), ("MOUSSE", 0.08)],
    "WETLAND": [("HERBACEE", 0.50), ("FEUILLU", 0.28), ("ARBUSTE", 0.22)],
    "GRASSLAND": [("HERBACEE", 0.72), ("ARBUSTE", 0.18), ("FEUILLU", 0.10)],
    "SAVANNA": [("HERBACEE", 0.58), ("FEUILLU", 0.30), ("ARBUSTE", 0.12)],
    "STEPPE": [("HERBACEE", 0.78), ("ARBUSTE", 0.22)],
    "TUNDRA": [("MOUSSE", 0.62), ("HERBACEE", 0.28), ("ARBUSTE", 0.10)],
    "SEMI_DESert".upper(): [("CACTUS", 0.55), ("ARBUSTE", 0.30), ("HERBACEE", 0.15)],
}

print("=== 1. Couverture et normalisation ===")
for biome, d in DENSITY.items():
    mix = MIX.get(biome)
    assert mix, f"{biome} : densité {d} sans mélange"
    total = sum(w for _, w in mix)
    assert abs(total - 1.0) < 1e-9, f"{biome} : somme {total}"
    print(f"  {biome:<17} densité {d:<5} {len(mix)} espèces, somme 1,000")
assert set(MIX) == set(DENSITY), "mélange orphelin ou manquant"
print("  → chaque biome végétalisé a son mélange ; les biomes nus (désert,")
print("    plage, roche, neige, glace, alpin, banquise, océans) n'en ont pas,")
print("    comme leur densité nulle l'exige déjà.")

print("\n=== 2. Vraisemblance écologique (assertions, pas prose) ===")
def weight(biome, sp):
    return dict(MIX[biome]).get(sp, 0.0)
assert weight("BOREAL_FOREST", "CONIFERE") > 0.7, "la taïga est un pays de conifères"
assert weight("RAINFOREST", "PALMIER") > 0.15 and weight("RAINFOREST", "CONIFERE") == 0
assert weight("SEMI_DESERT", "CACTUS") > 0.5 and weight("SEMI_DESERT", "PALMIER") == 0
assert weight("TUNDRA", "MOUSSE") > 0.5 and weight("TUNDRA", "CONIFERE") == 0
assert weight("TEMPERATE_FOREST", "FEUILLU") > weight("TEMPERATE_FOREST", "CONIFERE")
assert all(weight(b, "HERBACEE") >= 0.5 for b in ("GRASSLAND", "STEPPE"))
print("  huit assertions : taïga aux conifères, tropiques aux palmiers sans")
print("  conifères, semi-désert aux cactus, toundra aux mousses, prairies et")
print("  steppes dominées par l'herbe. Toutes vertes.")

print("\n=== 3. Tirage cumulatif et tolérance du test Kotlin ===")
# speciesAt(biome, u) : u uniforme dans [0,1) contre les poids cumulés.
# Le test Kotlin tirera N échantillons par micro-hachage et comparera les
# fréquences aux poids. Tolérance = 4 écarts-types binomiaux au pire poids,
# CALCULÉE ici : un seuil deviné serait soit une loterie soit une passoire.
N = 20_000
worst = min(w for mix in MIX.values() for _, w in mix)
sigma = math.sqrt(worst * (1 - worst) / N)
tol = 4 * sigma
print(f"  N = {N} tirages par biome ; plus petit poids = {worst}")
print(f"  écart-type binomial = {sigma:.5f} ; tolérance 4σ = {tol:.4f}")
print(f"  → le test exigera |fréquence − poids| < {tol:.3f} : la probabilité")
print("    d'un faux rouge par biome est ~6e-5, et un vrai biais de mélange")
print("    (poids décalé de 2 %) serait attrapé à coup sûr.")
assert tol < 0.02, "tolérance trop lâche pour détecter un biais de 2 %"

print("\n=== 4. Le tirage doit être STABLE par position ===")
print("  speciesAt prend un u ∈ [0,1) fourni par le micro-hachage du terrain")
print("  (profile.micro01), déjà déterministe par graine et par position :")
print("  la même clairière porte le même chêne à chaque visite, sur Android")
print("  comme sur PC. Aucun RNG d'instance : l'état vivrait où ?")

print("""
CONCLUSION. Un objet VegetationRules dans :sim : densityFor (valeurs
DÉPLACÉES de TileMesh, à l'identique), mixFor (les neuf mélanges du §1),
speciesAt (tirage cumulatif sur u). TileMesh délègue sa densité. Console
« flore » : biome, densité et mélange au point visé — la seule partie
visible avant l'instanciation du 3.5. Tolérance des tests de fréquence :
0,008, calculée au §3 (la première rédaction disait 0,013 — un chiffre
recopié de tête qui contredisait le calcul trois lignes plus haut).""")
