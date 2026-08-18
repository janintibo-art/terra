"""Instruction du lot 3.4 — coloration du feuillage.

But : fixer PAR LA MESURE tous les nombres du modèle de couleur, avant
d'écrire la moindre ligne de Kotlin, et produire les seuils que les tests
inscriront. Ce que ce script ne peut pas faire : juger du goût. Il vérifie
des bornes, des fractions d'année et des contrastes ; le choix des quatre
couleurs cibles (automne, hiver, olive aride, vert froid) reste esthétique
et se jugera sur photo.

Sommaire
  §1  Course annuelle de la température locale, par climat type
  §2  Choix de T_GREEN et de la largeur de sénescence
  §3  Balayage exhaustif : bornes [0 ; 1], luminance, valeurs finies
  §4  Contraste été / automne  → seuil de test
  §5  Contraste climatique en plein été → seuil de test
  §6  Dispersion individuelle → seuil de test
  §7  Coût du 10e float et du calcul par image
"""
import math

# --------------------------------------------------------------------------
# Constantes reprises du code existant (ne pas modifier ici : elles doivent
# rester la copie fidèle de SeasonalClimate.kt / WorldTime.kt / TreeSpecies.kt)
# --------------------------------------------------------------------------
A_MARINE_0, A_MARINE_S = 0.82, 6.38
A_CONT_0, A_CONT_S, A_CONT_S2 = 0.74, 13.10, 20.99
LAG_DAYS_OCEAN, LAG_DAYS_SPAN = 55.0, 28.0
REF_TILT_DEG = 23.4
DAYS_PER_YEAR = 360
TILT_DEG = 23.4

# Couleurs de feuillage par espèce (TreeSpecies.kt). Le cactus n'a pas de
# feuillage : sa tige est du « bois » et ne reçoit que la luminosité
# individuelle — noté comme limite assumée.
SPECIES = {
    'CONIFERE': (0.16, 0.34, 0.19, False),   # (r, g, b, caduc)
    'FEUILLU':  (0.24, 0.46, 0.15, True),
    'PALMIER':  (0.28, 0.48, 0.18, False),
    'ARBUSTE':  (0.26, 0.44, 0.14, True),
    'HERBACEE': (0.38, 0.52, 0.18, True),
    'MOUSSE':   (0.30, 0.50, 0.22, False),
}

# --------------------------------------------------------------------------
# Constantes CANDIDATES du lot 3.4 — c'est ce que ce script doit valider ou
# corriger. Toute valeur retenue ici part telle quelle dans FoliageTint.kt.
# --------------------------------------------------------------------------
T_GREEN_C = 11.0          # au-dessus : feuillage d'été plein
SENESCENCE_SPAN_C = 13.0  # largeur de la descente vers le feuillage d'hiver
PHASE_SPREAD_C = 3.0      # étalement individuel du seuil (± la moitié)

# Aridité par l'indice de De Martonne P/(T+10) et non par la pluie brute :
# 500 mm à 3 °C n'est PAS un climat sec (l'évaporation y est faible), et la
# version « pluie brute » jaunissait la forêt boréale autant que la steppe —
# jaunissement qui annulait exactement l'assombrissement du froid et
# ramenait la boréale à la couleur tropicale (§5, première mouture).
I_ARID, I_HUMID = 12.0, 40.0
ARID_MIX = 0.45
T_COLD, T_MILD = -2.0, 14.0
# 0,60 et non 0,40 : à 0,40 une forêt boréale ne se distinguait d'une forêt
# tropicale que de 0,061 en distance RGB, sous le seuil de perception retenu.
COLD_MIX = 0.60

SHADE_MIN, SHADE_SPAN = 0.86, 0.26
HUE_SPREAD = 0.06

AUTUMN = (0.72, 0.42, 0.10)
WINTER = (0.36, 0.30, 0.22)
ARID_OLIVE = (0.60, 0.56, 0.20)
COLD_GREEN = (0.12, 0.28, 0.20)


# --------------------------------------------------------------------------
# Miroir du code Kotlin
# --------------------------------------------------------------------------
def clamp(x, lo=0.0, hi=1.0):
    return lo if x < lo else (hi if x > hi else x)


def amplitude_c(sin_lat_abs, cont, ocean, tilt_deg):
    s = clamp(abs(sin_lat_abs))
    c = clamp(cont)
    marine = A_MARINE_0 + A_MARINE_S * s
    continental = A_CONT_0 + A_CONT_S * s + A_CONT_S2 * s * s
    a = marine + (continental - marine) * c
    if ocean:
        a *= 0.49
    return a * math.sin(math.radians(abs(tilt_deg))) / math.sin(math.radians(REF_TILT_DEG))


def delta_c(sin_lat, cont, day, tilt_deg=TILT_DEG):
    """Écart saisonnier en °C. Miroir de SeasonalClimate.deltaC, exprimé en
    JOURS plutôt qu'en ticks : la conversion ticks→jours est linéaire et
    déjà testée côté Kotlin, la reproduire n'apprendrait rien."""
    if abs(tilt_deg) < 0.01:
        return 0.0
    amp = amplitude_c(abs(sin_lat), cont, False, tilt_deg)
    lag = LAG_DAYS_OCEAN - LAG_DAYS_SPAN * clamp(cont)
    lagged = (day - lag) % DAYS_PER_YEAR
    phase = math.sin(2.0 * math.pi * (lagged / DAYS_PER_YEAR))
    return amp * phase * (1.0 if sin_lat >= 0 else -1.0)


def smoothstep01(edge0, edge1, x):
    """Renvoie 0 en edge0, 1 en edge1, avec une dérivée nulle aux deux bouts.
    Les bornes peuvent être données dans n'importe quel ordre : ici edge1 est
    plus FROID que edge0, la sénescence croissant quand la température baisse."""
    if edge0 == edge1:
        return 0.0 if x >= edge0 else 1.0
    t = clamp((x - edge0) / (edge1 - edge0))
    return t * t * (3.0 - 2.0 * t)


def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def senescence(local_t, caduc, salt_phase):
    if not caduc:
        return 0.0
    on = T_GREEN_C + (salt_phase - 0.5) * PHASE_SPREAD_C
    return smoothstep01(on, on - SENESCENCE_SPAN_C, local_t)


def summer_color(base, annual_t, precip, salt_hue):
    """Couleur d'été : l'espèce, poussée vers l'olive en climat sec et vers un
    vert froid en climat froid. On mélange vers deux CIBLES plutôt que vers la
    couleur du biome : celle du désert est beige, et un feuillage beige n'est
    pas un feuillage sec, c'est du sable."""
    martonne = precip / max(annual_t + 10.0, 1.0)
    aridity = clamp((I_HUMID - martonne) / (I_HUMID - I_ARID))
    cold = clamp((T_MILD - annual_t) / (T_MILD - T_COLD))
    c = mix(base, ARID_OLIVE, ARID_MIX * aridity)
    c = mix(c, COLD_GREEN, COLD_MIX * cold)
    # Dérive individuelle : un peu plus jaune ou un peu plus bleu que ses
    # voisins. Portée sur le seul canal rouge/bleu — décaler aussi le vert
    # ferait varier la luminance, ce dont SHADE se charge déjà.
    h = (salt_hue - 0.5) * HUE_SPREAD
    return (clamp(c[0] + h), c[1], clamp(c[2] - h))


def foliage(species, annual_t, precip, local_t, salt_phase, salt_hue):
    r, g, b, caduc = SPECIES[species]
    base = summer_color((r, g, b), annual_t, precip, salt_hue)
    s = senescence(local_t, caduc, salt_phase)
    if s <= 0.5:
        c = mix(base, AUTUMN, s * 2.0)
    else:
        c = mix(AUTUMN, WINTER, (s - 0.5) * 2.0)
    return tuple(clamp(x) for x in c)


def shade_of(salt_shade):
    return SHADE_MIN + SHADE_SPAN * salt_shade


def luminance(c):
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def dist(a, b):
    return math.sqrt(sum((a[i] - b[i]) ** 2 for i in range(3)))


# Climats types : (nom, T annuelle °C, précip mm, latitude °, continentalité)
# Les températures et pluies sont celles que la classification de Whittaker
# (Biome.classify) impose au biome correspondant.
CLIMATES = [
    ('tropicale humide',    26.0, 2600.0,  5.0, 0.30),
    ('savane',              25.0,  700.0, 12.0, 0.60),
    ('tempérée océanique',  12.0, 1000.0, 47.0, 0.25),
    ('tempérée continentale', 9.0, 800.0, 47.0, 0.85),
    ('steppe',              10.0,  350.0, 45.0, 0.90),
    ('boréale',              3.0,  500.0, 60.0, 0.80),
    ('toundra',             -1.0,  300.0, 70.0, 0.70),
]

echecs = []


def verifier(condition, message):
    if not condition:
        echecs.append(message)
        print('    ÉCHEC :', message)


# --------------------------------------------------------------------------
print('§1 — Course annuelle de la température locale')
print('    climat                    T annuelle   T min   T max   amplitude')
courses = {}
for nom, t_ann, precip, lat, cont in CLIMATES:
    sin_lat = math.sin(math.radians(lat))
    serie = [t_ann + delta_c(sin_lat, cont, d) for d in range(DAYS_PER_YEAR)]
    courses[nom] = serie
    print('    %-24s %7.1f  %7.1f %7.1f    ±%.1f' %
          (nom, t_ann, min(serie), max(serie), (max(serie) - min(serie)) / 2))

# Contrôle de cohérence avec le calibrage du lot 1.12 : sous les tropiques
# l'excursion doit rester faible, aux hautes latitudes continentales elle
# doit être forte. Si ces deux faits sont faux, c'est le miroir qui est
# faux, pas le modèle de couleur.
amp_trop = (max(courses['tropicale humide']) - min(courses['tropicale humide'])) / 2
amp_bor = (max(courses['boréale']) - min(courses['boréale'])) / 2
verifier(amp_trop < 3.0, 'excursion tropicale trop forte : %.1f °C' % amp_trop)
verifier(amp_bor > 12.0, 'excursion boréale trop faible : %.1f °C' % amp_bor)

# --------------------------------------------------------------------------
print()
print('§2 — Fractions de l\'année par état du feuillage (seuil médian)')
print('    vert : sénescence < 0,15 · automne : 0,15 à 0,70 · hiver : > 0,70')
print('    climat                     vert   automne   hiver')
fractions = {}
for nom, t_ann, precip, lat, cont in CLIMATES:
    serie = courses[nom]
    vert = sum(1 for t in serie if senescence(t, True, 0.5) < 0.15)
    hiver = sum(1 for t in serie if senescence(t, True, 0.5) > 0.70)
    automne = DAYS_PER_YEAR - vert - hiver
    fractions[nom] = (vert / DAYS_PER_YEAR, automne / DAYS_PER_YEAR, hiver / DAYS_PER_YEAR)
    print('    %-24s %6.0f%% %8.0f%% %7.0f%%' %
          (nom, 100 * vert / DAYS_PER_YEAR, 100 * automne / DAYS_PER_YEAR,
           100 * hiver / DAYS_PER_YEAR))

# Ce que le modèle doit produire pour être crédible :
verifier(fractions['tropicale humide'][0] > 0.99,
         'la forêt tropicale doit rester verte toute l\'année')
verifier(fractions['savane'][0] > 0.99,
         'la savane ne roussit pas par le FROID (sa saison sèche viendra '
         'avec la pluie saisonnière, hors de ce lot)')
verifier(0.35 < fractions['tempérée océanique'][0] < 0.75,
         'forêt tempérée océanique : fraction verte hors de [35 ; 75] %%')
verifier(fractions['tempérée continentale'][2] > 0.15,
         'un hiver continental doit ternir le feuillage')
verifier(fractions['toundra'][2] > 0.40,
         'la toundra doit passer plus de 40 %% de l\'année en feuillage terne')

# --------------------------------------------------------------------------
print()
print('§3 — Balayage exhaustif : bornes, luminance, finitude')
pire_max, pire_min_lum = 0.0, 1.0
n = 0
for espece in SPECIES:
    for nom, t_ann, precip, lat, cont in CLIMATES:
        for jour in range(0, DAYS_PER_YEAR, 5):
            local = t_ann + delta_c(math.sin(math.radians(lat)), cont, jour)
            for sp in (0.0, 0.5, 0.999):
                for sh in (0.0, 0.5, 0.999):
                    for ss in (0.0, 0.999):
                        c = foliage(espece, t_ann, precip, local, sp, sh)
                        final = tuple(x * shade_of(ss) for x in c)
                        n += 1
                        for x in final:
                            if not math.isfinite(x):
                                echecs.append('valeur non finie : %s' % espece)
                            pire_max = max(pire_max, x)
                        pire_min_lum = min(pire_min_lum, luminance(final))
print('    %d combinaisons évaluées' % n)
print('    composante maximale après luminosité individuelle : %.3f' % pire_max)
print('    luminance minimale : %.3f' % pire_min_lum)
# Le shader multiplie ensuite par l'éclairage (0,35 à 1,10) : une composante
# à 0,9 y reste sous 1,0. C'est la vraie borne à ne pas franchir.
verifier(pire_max * 1.10 <= 1.0,
         'saturation possible dans le shader : %.3f × 1,10 > 1' % pire_max)
verifier(pire_min_lum > 0.10,
         'feuillage trop sombre (luminance %.3f) : il se lirait comme un trou'
         % pire_min_lum)

# --------------------------------------------------------------------------
print()
print('§4 — Contraste été / automne (distance RGB)')
ecarts = []
for espece, (r, g, b, caduc) in SPECIES.items():
    if not caduc:
        continue
    for nom, t_ann, precip, lat, cont in CLIMATES:
        ete = foliage(espece, t_ann, precip, 30.0, 0.5, 0.5)
        aut = foliage(espece, t_ann, precip, T_GREEN_C - SENESCENCE_SPAN_C / 2, 0.5, 0.5)
        ecarts.append((dist(ete, aut), espece, nom))
ecarts.sort()
print('    plus faible : %.3f (%s, %s)' % ecarts[0])
print('    plus fort   : %.3f (%s, %s)' % ecarts[-1])
verifier(ecarts[0][0] > 0.20,
         'contraste été/automne trop faible : %.3f' % ecarts[0][0])

print('    persistants : écart été/hiver (doit être NUL)')
for espece, (r, g, b, caduc) in SPECIES.items():
    if caduc:
        continue
    e = dist(foliage(espece, 3.0, 500.0, 25.0, 0.5, 0.5),
             foliage(espece, 3.0, 500.0, -15.0, 0.5, 0.5))
    print('      %-10s %.4f' % (espece, e))
    verifier(e < 1e-6, '%s persistant ne doit pas changer de couleur' % espece)

# --------------------------------------------------------------------------
print()
print('§5 — Contraste climatique en plein été (feuillu)')
paires = [
    ('tropicale humide', 'boréale'),
    ('tropicale humide', 'steppe'),
    ('tempérée océanique', 'steppe'),
]
mini = 1.0
for a, b in paires:
    ca = next(c for c in CLIMATES if c[0] == a)
    cb = next(c for c in CLIMATES if c[0] == b)
    fa = foliage('FEUILLU', ca[1], ca[2], 30.0, 0.5, 0.5)
    fb = foliage('FEUILLU', cb[1], cb[2], 30.0, 0.5, 0.5)
    d = dist(fa, fb)
    mini = min(mini, d)
    print('    %-20s vs %-20s : %.3f' % (a, b, d))
verifier(mini > 0.08,
         'deux climats très différents donnent presque la même couleur : %.3f'
         % mini)

# --------------------------------------------------------------------------
print()
print('§6 — Dispersion individuelle (10 000 individus, feuillu tempéré)')
lum = []
for i in range(10000):
    ss = (i * 7919 % 10000) / 10000.0
    sh = (i * 5011 % 10000) / 10000.0
    c = foliage('FEUILLU', 12.0, 1000.0, 30.0, 0.5, sh)
    lum.append(luminance(tuple(x * shade_of(ss) for x in c)))
moy = sum(lum) / len(lum)
ecart = math.sqrt(sum((x - moy) ** 2 for x in lum) / len(lum))
print('    luminance moyenne %.4f · écart-type %.4f · étendue %.4f'
      % (moy, ecart, max(lum) - min(lum)))
verifier(ecart / moy > 0.03,
         'variation individuelle imperceptible : %.1f %% de la moyenne'
         % (100 * ecart / moy))
verifier(ecart / moy < 0.12,
         'variation individuelle trop forte : la forêt serait tachetée '
         '(%.1f %%)' % (100 * ecart / moy))

# Étalement phénologique : au seuil, tous les arbres ne roussissent pas
# ensemble. C'est ce qui fait une forêt d'automne plutôt qu'un aplat.
# À MI-DESCENTE, et non au seuil : au seuil, la définition même de
# smoothstep veut que tout le monde soit encore vert, et la première version
# de ce contrôle mesurait donc un étalement nul sur un modèle sain.
mi = T_GREEN_C - SENESCENCE_SPAN_C / 2
etats = [senescence(mi, True, i / 1000.0) for i in range(1000)]
print('    à mi-descente (%.1f °C) : sénescence de %.2f à %.2f'
      % (mi, min(etats), max(etats)))
verifier(max(etats) - min(etats) > 0.10,
         'l\'étalement individuel du seuil est invisible')

# --------------------------------------------------------------------------
print()
print('§7 — Coûts')
# Sommets des maillages de variantes, pris des coûts en triangles mesurés au
# lot 3.5 (TreeField.triangleCost), × 3 sommets, × 4 octets.
COUTS = {'CONIFERE': (17776, 10110, 638), 'FEUILLU': (6472, 3721, 771),
         'ARBUSTE': (2152, 1237, 255), 'AUTRES': (150, 90, 15)}
VARIANTES = (2, 2, 4)   # plein, moyen, bas
total_sommets = 0
for espece, couts in COUTS.items():
    for i, c in enumerate(couts):
        total_sommets += c * 3 * VARIANTES[i]
octets = total_sommets * 4
print('    sommets de variantes (pire cas, 4 espèces chères) : %d' % total_sommets)
print('    10e float : +%.2f Mo de VBO' % (octets / 1024 / 1024))
verifier(octets / 1024 / 1024 < 1.5,
         'le canal matériau coûte plus d\'1,5 Mo : %.2f' % (octets / 1024 / 1024))

# Calcul par image : la teinte est recalculée pour chaque arbre à chaque
# image, pour que la couleur suive la saison sans attendre la reconstruction
# du champ (qui n'a lieu qu'après 35 % de déplacement).
ARBRES = 264
FLOPS = 60   # majorant très large pour deltaC + mélanges
print('    %d arbres × ~%d opérations = %d op/image (~%.2f M op/s à 60 i/s)'
      % (ARBRES, FLOPS, ARBRES * FLOPS, ARBRES * FLOPS * 60 / 1e6))
verifier(ARBRES * FLOPS * 60 < 5e6, 'coût par image non négligeable')

# --------------------------------------------------------------------------
print()
if echecs:
    print('VERDICT : %d échec(s)' % len(echecs))
    for e in echecs:
        print('  -', e)
else:
    print('VERDICT : toutes les vérifications passent.')
