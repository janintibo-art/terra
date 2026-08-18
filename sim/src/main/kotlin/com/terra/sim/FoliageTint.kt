package com.terra.sim

import kotlin.math.max

/**
 * Couleur du feuillage — lot 3.4.
 *
 * ## Pourquoi cette couleur n'est pas dans le maillage
 *
 * Depuis le lot 3.5, les maillages d'arbres sont des VARIANTES PARTAGÉES :
 * deux feuillus voisins dessinent le même VBO, seul leur repère diffère.
 * Peindre une couleur par individu obligerait à dupliquer ces maillages —
 * exactement les 27 Mo que l'instruction du 3.5 avait écartés. La couleur
 * est donc un UNIFORME par arbre, et le maillage ne porte plus qu'une
 * couleur de référence plus un canal disant, sommet par sommet, s'il est
 * du bois ou du feuillage (`TreeMesh.OFFSET_MATERIAL`). Sans ce canal, la
 * teinte d'automne roussirait aussi les troncs.
 *
 * Conséquence : ce calcul tourne pour chaque arbre à chaque image (264 au
 * pire, ~0,95 M opérations par seconde — mesuré §7 de
 * `validation/couleur_feuillage.py`). C'est voulu : le champ n'est
 * reconstruit qu'après 35 % de déplacement de la caméra, et une couleur
 * figée à la construction ne suivrait pas la saison sous les yeux d'un
 * observateur immobile.
 *
 * ## Les trois causes de la couleur
 *
 * 1. **Le climat** décide de la couleur d'été. Deux mélanges : vers un
 *    olive en climat sec, vers un vert sombre en climat froid.
 * 2. **La saison** décide de la sénescence, par la TEMPÉRATURE LOCALE du
 *    moment — pas par un calendrier. L'hémisphère, le retard thermique et
 *    l'inclinaison du monde sont alors gratuits : ils sont déjà dans
 *    [SeasonalClimate.deltaC]. Une planète sans inclinaison n'a pas
 *    d'automne, et c'est le même code qui le dit.
 * 3. **L'individu** décide de sa luminosité, de sa nuance et de sa date de
 *    roussissement, par trois sels de position (+9, +10, +11).
 *
 * ## L'aridité n'est pas la pluie
 *
 * L'aridité suit l'indice de De Martonne `P / (T + 10)` et non les
 * millimètres bruts. La première mouture jaunissait la forêt boréale
 * (500 mm) autant que la steppe ; ce jaunissement annulait presque
 * exactement l'assombrissement dû au froid, et une forêt boréale finissait
 * à 0,061 de distance RGB d'une forêt tropicale — invisible. Le §5 de
 * l'instruction a attrapé le défaut avant la première ligne de Kotlin.
 *
 * ## Ce que ce lot ne fait pas
 *
 * Les feuilles ne TOMBENT pas : un feuillu d'hiver est brun, pas nu. La
 * chute est le lot 3.8. La savane ne jaunit pas non plus en saison sèche :
 * seule la température est saisonnière dans Terra, pas la pluie — vérifié
 * explicitement au §2 de l'instruction, pour que ce trou soit un choix et
 * non un oubli. Enfin les losanges des tuiles lointaines gardent leur
 * couleur de biome : les faire suivre exigerait de reconstruire les tuiles
 * à chaque nuance.
 */
object FoliageTint {

    // ----------------------------------------------------- sénescence

    /** Au-dessus de cette température locale, feuillage d'été plein. */
    const val T_GREEN_C = 11f

    /** Largeur de la descente : à `T_GREEN_C − SENESCENCE_SPAN_C`, le
     *  feuillage est entièrement passé au brun d'hiver. Calibrée pour que
     *  la forêt tempérée océanique reste verte 65 % de l'année et la
     *  toundra 55 % ternie (§2 de l'instruction). */
    const val SENESCENCE_SPAN_C = 13f

    /** Étalement individuel du seuil, en °C, réparti autour de [T_GREEN_C].
     *  C'est lui qui fait une forêt d'automne plutôt qu'un aplat : à
     *  mi-descente, les individus s'échelonnent de 0,33 à 0,67. */
    const val PHASE_SPREAD_C = 3f

    // ----------------------------------------------------- climat d'été

    /** Bornes de l'indice de De Martonne `P/(T+10)` : aride en deçà de 12,
     *  humide au-delà de 40. Ce sont les seuils usuels de l'indice. */
    const val I_ARID = 12f
    const val I_HUMID = 40f

    /** Poids maximal du jaunissement en climat aride. */
    const val ARID_MIX = 0.45f

    /** Bornes du froid, sur la température moyenne ANNUELLE. */
    const val T_COLD = -2f
    const val T_MILD = 14f

    /** Poids maximal de l'assombrissement en climat froid. 0,60 et non
     *  0,40 : sous 0,45, une forêt boréale ne se distinguait pas d'une
     *  forêt tropicale (§5). */
    const val COLD_MIX = 0.60f

    // ----------------------------------------------------- individu

    /** Luminosité individuelle, dans [0,86 ; 1,12]. Écart-type mesuré :
     *  7,6 % de la moyenne — assez pour que la forêt vibre, trop peu pour
     *  qu'elle paraisse tachetée (§6). */
    const val SHADE_MIN = 0.86f
    const val SHADE_SPAN = 0.26f

    /** Dérive de nuance individuelle, portée par les seuls canaux rouge et
     *  bleu : décaler aussi le vert ferait varier la luminance, dont
     *  [SHADE_MIN] se charge déjà. */
    const val HUE_SPREAD = 0.06f

    // ----------------------------------------------------- couleurs cibles
    //
    // Ces quatre couleurs sont des choix ESTHÉTIQUES : l'instruction peut
    // vérifier qu'elles restent dans les bornes et qu'elles contrastent,
    // pas qu'elles sont belles. Elles se jugent sur photo.

    /** Doré-roux du plein automne. */
    const val AUTUMN_R = 0.72f
    const val AUTUMN_G = 0.42f
    const val AUTUMN_B = 0.10f

    /** Brun-gris du feuillage mort de l'hiver. */
    const val WINTER_R = 0.36f
    const val WINTER_G = 0.30f
    const val WINTER_B = 0.22f

    /** Olive des feuillages de climat sec. */
    const val OLIVE_R = 0.60f
    const val OLIVE_G = 0.56f
    const val OLIVE_B = 0.20f

    /** Vert sombre et un peu bleu des climats froids. */
    const val COLD_R = 0.12f
    const val COLD_G = 0.28f
    const val COLD_B = 0.20f

    /**
     * Couleurs de feuillage de référence, indexées par l'ordinal d'espèce.
     *
     * Dérivées de `TreeSpecies.params()` une seule fois plutôt que
     * recopiées : `params()` construit un objet à chaque appel, et 264
     * arbres par image en feraient 15 840 par seconde à jeter. Une table
     * écrite à la main aurait été la seconde vérité qui finit par mentir.
     */
    private val BASE: Array<FloatArray> = TreeSpecies.values().map { species ->
        val p = species.params()
        floatArrayOf(p.foliageRed, p.foliageGreen, p.foliageBlue)
    }.toTypedArray()

    /**
     * Espèces à feuillage persistant : elles ne roussissent jamais.
     *
     * Le cactus y figure sans que cela s'observe : il n'a pas de feuillage
     * du tout (`foliageDepthSpan = 0`), sa tige est du bois pour le
     * maillage, et seule sa luminosité individuelle le distingue de ses
     * voisins.
     */
    fun isEvergreen(species: TreeSpecies): Boolean = when (species) {
        TreeSpecies.CONIFERE, TreeSpecies.PALMIER,
        TreeSpecies.CACTUS, TreeSpecies.MOUSSE -> true
        TreeSpecies.FEUILLU, TreeSpecies.ARBUSTE, TreeSpecies.HERBACEE -> false
    }

    /**
     * Température locale du moment : la moyenne annuelle du lieu plus
     * l'écart saisonnier du lot 1.12. Tout le calendrier — hémisphère,
     * retard thermique de 27 à 55 jours, inclinaison du monde — vient de
     * là, déjà testé.
     */
    fun localTemperatureC(
        annualTempC: Float,
        sinLat: Float,
        continentality01: Float,
        time: WorldTime,
        tick: Long
    ): Float = annualTempC +
        SeasonalClimate.deltaC(sinLat, continentality01, false, time, tick)

    /**
     * Avancement de la sénescence, de 0 (feuillage d'été) à 1 (feuillage
     * mort). Nulle pour un persistant, quelle que soit la température.
     *
     * @param saltPhase aléa individuel dans [0 ; 1[ ; décale le seuil de
     *   ±[PHASE_SPREAD_C]/2, ce qui échelonne le roussissement d'un arbre
     *   à l'autre.
     */
    fun senescence(localTempC: Float, evergreen: Boolean, saltPhase: Float): Float {
        if (evergreen) return 0f
        val onset = T_GREEN_C + (saltPhase - 0.5f) * PHASE_SPREAD_C
        return smoothFalling(onset, onset - SENESCENCE_SPAN_C, localTempC)
    }

    /** Luminosité propre à un individu, dans [0,86 ; 1,12]. */
    fun shade(saltShade: Float): Float =
        SHADE_MIN + SHADE_SPAN * saltShade.coerceIn(0f, 1f)

    /**
     * Écrit dans `out[0..2]` la couleur du feuillage.
     *
     * @param annualTempC moyenne ANNUELLE du lieu — c'est elle qui dit le
     *   climat ; la température du moment dit la saison.
     * @param out tableau d'au moins trois flottants, réutilisé d'un arbre
     *   à l'autre pour n'allouer rien dans la boucle de rendu.
     */
    fun color(
        species: TreeSpecies,
        annualTempC: Float,
        precipMm: Float,
        localTempC: Float,
        saltPhase: Float,
        saltHue: Float,
        out: FloatArray
    ) {
        require(out.size >= 3) { "out trop court : ${out.size}" }
        val base = BASE[species.ordinal]

        // --- Couleur d'été, dictée par le climat ---
        //
        // On mélange vers deux cibles choisies, et NON vers la couleur du
        // biome : celle du désert est beige, et un feuillage beige ne se
        // lit pas comme une plante sèche mais comme du sable. Les deux
        // grandeurs employées — pluie et température annuelle — sont
        // précisément celles qui définissent le biome de Whittaker, donc
        // la couleur suit le biome sans hériter de sa palette ni de ses
        // frontières nettes.
        val martonne = precipMm / max(annualTempC + 10f, 1f)
        val aridity = ((I_HUMID - martonne) / (I_HUMID - I_ARID)).coerceIn(0f, 1f)
        val cold = ((T_MILD - annualTempC) / (T_MILD - T_COLD)).coerceIn(0f, 1f)

        val wArid = ARID_MIX * aridity
        var r = base[0] + (OLIVE_R - base[0]) * wArid
        var g = base[1] + (OLIVE_G - base[1]) * wArid
        var b = base[2] + (OLIVE_B - base[2]) * wArid

        val wCold = COLD_MIX * cold
        r += (COLD_R - r) * wCold
        g += (COLD_G - g) * wCold
        b += (COLD_B - b) * wCold

        val hue = (saltHue.coerceIn(0f, 1f) - 0.5f) * HUE_SPREAD
        r = (r + hue).coerceIn(0f, 1f)
        b = (b - hue).coerceIn(0f, 1f)

        // --- Sénescence : été → automne → hiver ---
        //
        // Deux segments plutôt qu'un seul mélange été→hiver : le doré de
        // l'automne n'est PAS sur le segment qui joint le vert au brun, et
        // sans lui la forêt passerait du vert au brun sans jamais flamber.
        val s = senescence(localTempC, isEvergreen(species), saltPhase)
        if (s <= 0.5f) {
            val t = s * 2f
            r += (AUTUMN_R - r) * t
            g += (AUTUMN_G - g) * t
            b += (AUTUMN_B - b) * t
        } else {
            val t = (s - 0.5f) * 2f
            r = AUTUMN_R + (WINTER_R - AUTUMN_R) * t
            g = AUTUMN_G + (WINTER_G - AUTUMN_G) * t
            b = AUTUMN_B + (WINTER_B - AUTUMN_B) * t
        }

        out[0] = r.coerceIn(0f, 1f)
        out[1] = g.coerceIn(0f, 1f)
        out[2] = b.coerceIn(0f, 1f)
    }

    /**
     * Couleur et luminosité d'un arbre du champ instancié, écrites dans
     * `out[0..3]` : trois composantes puis la luminosité.
     *
     * Point d'entrée du moteur de rendu : il ne connaît ni les seuils ni
     * les mélanges, seulement quatre nombres à poser en uniformes.
     */
    fun of(instance: TreeField.Instance, time: WorldTime, tick: Long, out: FloatArray) {
        require(out.size >= 4) { "out trop court : ${out.size}" }
        val local = localTemperatureC(
            instance.annualTempC, instance.sinLat, instance.continentality01, time, tick
        )
        color(
            instance.variant.species, instance.annualTempC, instance.precipMm,
            local, instance.saltPhase, instance.saltHue, out
        )
        out[3] = shade(instance.saltShade)
    }

    /**
     * Rampe lisse DÉCROISSANTE : 0 au-dessus de [warm], 1 au-dessous de
     * [cold], dérivée nulle aux deux bouts.
     *
     * Le sens inverse est l'intérêt : la sénescence croît quand la
     * température baisse. Écrire `1 − smoothstep(cold, warm, x)` donnerait
     * le même résultat, au prix d'une soustraction que l'on relit deux fois
     * avant d'en être sûr.
     */
    private fun smoothFalling(warm: Float, cold: Float, x: Float): Float {
        if (warm == cold) return if (x >= warm) 0f else 1f
        val t = ((x - warm) / (cold - warm)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
