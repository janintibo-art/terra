package com.terra.sim

import com.terra.core.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Teinte de sol filtrée par la maille — lot 2.17.
 *
 * Chaque octave ne doit exister que là où le maillage la résout. Ces tests
 * vérifient les trois propriétés qui font le lot : les échelles fines
 * s'éteignent aux mailles grossières (anti-repliement), elles existent
 * vraiment aux mailles fines (sinon le lot ne sert à rien), et le passage
 * d'un niveau au suivant ne produit pas de ressaut visible.
 *
 * Bornes reprises de `validation/sol_microdetail.py`.
 */
class GroundTintScaleTest {

    private companion object {
        // Une seule génération pour toute la classe : JUnit crée une
        // instance par test, un champ d'instance coûterait quatre mondes.
        val world: PlanetData by lazy {
            WorldGenerator.fromName("Kaleth", PlanetParams(subdivisions = 4)).generate()
        }
    }

    /** Directions terrestres déterministes, tirées en spirale. */
    private fun dirs(n: Int): List<Vec3> {
        val out = ArrayList<Vec3>(n)
        var i = 0
        while (out.size < n && i < n * 40) {
            val t = (i + 0.5) / (n * 4.0)
            val y = 1.0 - 2.0 * t
            val r = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val phi = i * 2.399963
            val d = Vec3(
                (r * kotlin.math.cos(phi)).toFloat(), y.toFloat(),
                (r * kotlin.math.sin(phi)).toFloat()
            )
            if (world.terrain.altitudeAt(d) > 0f) out.add(d)
            i++
        }
        return out
    }

    /** Écart-type du canal rouge de la teinte, pour une maille donnée. */
    private fun spread(cellSizeM: Float, sample: List<Vec3>): Double {
        val tint = FloatArray(3)
        var sum = 0.0
        var sumSq = 0.0
        for (d in sample) {
            world.terrain.groundTintAt(d, tint, cellSizeM)
            sum += tint[0]
            sumSq += tint[0].toDouble() * tint[0]
        }
        val n = sample.size
        val mean = sum / n
        return sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
    }

    @Test
    fun `les octaves fines s eteignent aux mailles grossieres`() {
        // Une maille de 40 km (niveau 4, vue orbitale) ne résout aucune des
        // octaves : la teinte doit y être quasi plate. Sans ce filtre, le
        // grain de 9 m y était échantillonné très en dessous de Nyquist —
        // du bruit replié, pas de la texture.
        val sample = dirs(400)
        val coarse = spread(40_000f, sample)
        val fine = spread(0.07f, sample)
        assertTrue(coarse < 0.01,
            "teinte encore variable à 40 km de maille (écart-type $coarse) : " +
                "le filtre de Nyquist ne s'applique pas")
        // Comparer par un rapport serait fautif ici : à 40 km de maille
        // l'écart-type est exactement NUL (aucune octave n'entre), et
        // « fine > coarse × 5 » se lirait « 0 > 0 ». On exige donc une
        // valeur absolue de détail au plus près, et la platitude au loin.
        assertTrue(fine > 0.02,
            "la maille fine ne porte presque aucun détail (écart-type $fine)")
    }

    @Test
    fun `les octaves decimetriques existent vraiment de pres`() {
        // La raison d'être du lot : entre la maille du sol lointain (2,4 m,
        // niveau 18) et celle du piéton (7 cm, niveau 23), le détail doit
        // AUGMENTER — sinon les deux nouvelles octaves ne servent à rien.
        val sample = dirs(400)
        val far = spread(2.4f, sample)
        val near = spread(0.07f, sample)
        assertTrue(near > far * 1.05,
            "le sol du piéton ($near) n'est pas plus détaillé que celui à " +
                "1 km ($far) : les octaves fines n'entrent pas")
    }

    @Test
    fun `le passage d un niveau au suivant ne fait pas claquer la teinte`() {
        // Le fondu s'étale sur un facteur 5,3 de maille, soit 2,4 niveaux de
        // quadtree. Borne CALCULÉE dans le script de validation : 0,0816 au
        // pire cas (toutes les octaves à leur maximum, signes conspirants),
        // sur la teinte FINALE — produit des deux facteurs, pas leur somme.
        // Un premier jet à 2–4 mailles franchissait un niveau d'un coup et
        // donnait 0,11 : visible.
        val sample = dirs(300)
        val a = FloatArray(3)
        val b = FloatArray(3)
        var worst = 0f
        for (level in 4..22) {
            val c1 = ((Math.PI * 0.5 / (1 shl level)) * 6_371_000.0 / TileMesh.MESH_N).toFloat()
            val c2 = c1 * 0.5f
            for (d in sample) {
                world.terrain.groundTintAt(d, a, c1)
                world.terrain.groundTintAt(d, b, c2)
                for (k in 0 until 3) worst = maxOf(worst, abs(a[k] - b[k]))
            }
        }
        assertTrue(worst < 0.13f,
            "ressaut de teinte de $worst à une bascule de niveau (borne " +
                "calculée 0,0816, seuil 0,13)")
    }

    @Test
    fun `la teinte reste bornee quelle que soit la maille`() {
        // L'écrêtage [0,76 ; 1,24] est le garde-fou de dernier recours : il
        // doit tenir avec cinq octaves comme il tenait avec trois.
        val tint = FloatArray(3)
        for (cell in floatArrayOf(0f, 0.05f, 0.3f, 2.4f, 300f, 40_000f)) {
            for (d in dirs(200)) {
                world.terrain.groundTintAt(d, tint, cell)
                for (k in 0 until 3) {
                    assertTrue(tint[k] in 0.76f..1.24f,
                        "teinte hors bornes à maille $cell : ${tint[k]}")
                }
            }
        }
    }
}
