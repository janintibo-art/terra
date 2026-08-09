package com.terra.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Carte de ressources — lot 1.17.
 *
 * La couverture de chaque ressource est exacte PAR CONSTRUCTION (percentile
 * sur les cellules terrestres) : la vérifier ne prouve donc presque rien. Ce
 * que ces tests cherchent vraiment, c'est la COHÉRENCE GÉOLOGIQUE — un
 * gisement doit se trouver là où sa géologie le veut, et nulle part ailleurs.
 *
 * Le contraste se mesure en rapport de MÉDIANES et non de moyennes : une
 * distance angulaire a une distribution à longue queue, et quelques cellules
 * lointaines déplaceraient la moyenne sans rien dire du gisement. Seuil 0,60,
 * calculé dans `validation/ressources.py` : un masque qui fonctionne donne
 * 0,26, et le hasard ne descend pas sous 1,0.
 */
class ResourceFieldTest {

    private companion object {
        val world: PlanetData by lazy {
            WorldGenerator.fromName("Kaleth", PlanetParams(subdivisions = 5)).generate()
        }
        const val CONTRAST = 0.60
    }

    private fun landIndices(): List<Int> =
        (0 until world.vertexCount).filter { world.altitudeM[it] > 0f }

    private fun median(values: List<Float>): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2].toDouble()
        else 0.5 * (s[n / 2 - 1] + s[n / 2]).toDouble()
    }

    @Test
    fun `la couverture de chaque ressource est celle demandee`() {
        val land = landIndices().size
        assertTrue(land > 500, "monde sans terres exploitables : $land cellules")
        for (r in Resource.ALL) {
            val cells = world.resources.cellCount(r)
            val expected = Math.round(r.landFraction * land)
            val tolerance = maxOf(1, expected / 50)
            // Un percentile ne peut JAMAIS dépasser sa cible : c'est vrai de
            // toute ressource, sur tout monde. La tolérance ne couvre que les
            // ex aequo de score, qui peuvent faire passer une cellule de plus.
            assertTrue(
                cells <= expected + tolerance,
                "${r.label} : $cells cellules pour $expected demandées"
            )
            // En revanche il peut rester EN DESSOUS quand le masque
            // géologique n'offre pas assez de candidats — un monde sans
            // collision continentale n'a pas d'étain, et c'est correct. On
            // n'exige donc l'égalité que des ressources dont le masque couvre
            // forcément des terres.
            if (r == Resource.FARMLAND || r == Resource.WOOD || r == Resource.STONE) {
                assertTrue(
                    cells >= expected - tolerance,
                    "${r.label} : $cells cellules, moins que les $expected " +
                        "demandées alors que son masque couvre tous les biomes"
                )
            }
        }
    }

    @Test
    fun `aucune ressource sous la mer`() {
        for (i in 0 until world.vertexCount) {
            if (world.altitudeM[i] > 0f) continue
            for (r in Resource.ALL) {
                assertTrue(
                    !world.resources.hasResource(r, i),
                    "${r.label} trouvée en mer au sommet $i"
                )
            }
        }
    }

    @Test
    fun `le cuivre se tient pres des arcs de subduction`() {
        // Sa raison d'être : les fluides hydrothermaux des zones de
        // subduction. Loin d'un arc, il ne doit pas y en avoir.
        val land = landIndices()
        val d = world.boundaryDistance.distConvergent
        val gisement = land.filter { world.resources.hasResource(Resource.COPPER, it) }
            .map { d[it] }
        assertTrue(gisement.size > 20, "gisement de cuivre trop petit pour conclure")
        val ratio = median(gisement) / median(land.map { d[it] })
        assertTrue(
            ratio < CONTRAST,
            "cuivre à ${median(gisement)} rad des convergences contre " +
                "${median(land.map { d[it] })} pour les terres (rapport $ratio)"
        )
    }

    @Test
    fun `le fer se tient loin de toute frontiere active`() {
        // Un craton est vieux PARCE QU'il n'a rien vécu depuis longtemps :
        // le masque est l'inverse d'une proximité, le contraste s'inverse
        // donc aussi — ici la médiane du gisement doit être plus GRANDE.
        val land = landIndices()
        fun dAny(i: Int) = minOf(
            world.boundaryDistance.distConvergent[i],
            world.boundaryDistance.distDivergent[i],
            world.boundaryDistance.distTransform[i]
        )
        val gisement = land.filter { world.resources.hasResource(Resource.IRON, it) }
            .map { dAny(it) }
        assertTrue(gisement.size > 20, "gisement de fer trop petit pour conclure")
        val ratio = median(land.map { dAny(it) }) / median(gisement)
        assertTrue(
            ratio < CONTRAST,
            "le fer n'est pas dans les vieux boucliers (rapport inverse $ratio)"
        )
    }

    @Test
    fun `le fer ne sort pas des plaques continentales`() {
        for (i in 0 until world.vertexCount) {
            if (!world.resources.hasResource(Resource.IRON, i)) continue
            assertTrue(
                !world.plates.plateOf(i).oceanic,
                "fer sur une plaque océanique au sommet $i"
            )
        }
    }

    @Test
    fun `l etain ne nait que d une collision continentale`() {
        // Précaution : un monde PEUT n'avoir aucune collision continentale.
        // Le test constate alors l'absence légitime d'étain plutôt que
        // d'échouer — mais il vérifie d'abord laquelle des deux situations
        // il observe, pour ne pas se taire en silence sur un vrai bug.
        val collisions = (0 until world.vertexCount).count {
            world.altitudeM[it] > 0f &&
                world.boundaryDistance.contextConvergent[it] == BoundaryDistanceField.CRUST_CC
        }
        var checked = 0
        for (i in 0 until world.vertexCount) {
            if (!world.resources.hasResource(Resource.TIN, i)) continue
            assertEquals(
                BoundaryDistanceField.CRUST_CC,
                world.boundaryDistance.contextConvergent[i],
                "étain hors collision continentale au sommet $i"
            )
            checked++
        }
        if (collisions > 100) {
            assertTrue(
                checked > 0,
                "$collisions cellules de collision continentale mais aucun " +
                    "gisement d'étain : le masque est trop strict"
            )
        } else {
            assertEquals(0, checked, "étain sans collision continentale dans le monde")
        }
    }

    @Test
    fun `le sol arable evite les deserts et les glaces`() {
        val interdits = setOf(
            Biome.DESERT, Biome.GLACIER, Biome.SNOW, Biome.BARE_ROCK,
            Biome.ALPINE, Biome.TUNDRA
        )
        for (i in 0 until world.vertexCount) {
            if (!world.resources.hasResource(Resource.FARMLAND, i)) continue
            val biome = Biome.values()[world.biomeId[i].toInt()]
            assertTrue(biome !in interdits, "sol arable en $biome au sommet $i")
        }
    }

    @Test
    fun `la carte est deterministe et reproductible`() {
        // Elle est dérivée, donc recalculée à chaque chargement : deux
        // calculs sur la même graine doivent coïncider exactement, sans quoi
        // une tribu changerait de ressources d'une session à l'autre.
        val a = ResourceField.generate(world)
        val b = ResourceField.generate(world)
        for (r in Resource.ALL) {
            for (i in 0 until world.vertexCount) {
                assertEquals(
                    a.abundanceAt(r, i), b.abundanceAt(r, i), 0f,
                    "${r.label} diffère au sommet $i entre deux calculs"
                )
            }
        }
    }

    @Test
    fun `les abondances restent dans les bornes`() {
        for (r in Resource.ALL) {
            for (i in 0 until world.vertexCount) {
                val v = world.resources.abundanceAt(r, i)
                assertTrue(v in 0f..1f, "${r.label} hors bornes au sommet $i : $v")
            }
        }
    }
}
