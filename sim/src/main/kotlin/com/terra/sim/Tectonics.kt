package com.terra.sim

import com.terra.core.Seed
import com.terra.core.Vec3
import kotlin.math.sqrt

/**
 * Une plaque tectonique.
 *
 * @param seedDir centre de la cellule de Voronoï, sur la sphère unité
 * @param oceanic plaque océanique (croûte dense, future subduction) ou
 *   continentale (croûte légère, futurs cratons)
 * @param eulerAxis axe de rotation du mouvement de la plaque, unitaire.
 *   Une plaque rigide sur une sphère ne peut que **tourner** autour d'un axe
 *   passant par le centre (théorème d'Euler) : c'est la seule représentation
 *   du mouvement qui soit exacte, et elle donne gratuitement la vélocité en
 *   tout point par un simple produit vectoriel.
 * @param omegaRadPerMa vitesse angulaire, en radians par million d'années.
 *   Étalonnée sur la Terre : 2 à 10 cm/an en surface, soit 3 à 16 mrad/Ma.
 */
class Plate(
    val id: Int,
    val seedDir: Vec3,
    val oceanic: Boolean,
    val eulerAxis: Vec3,
    val omegaRadPerMa: Float,
    /** Couleur d'affichage stable, précalculée pour le calque. */
    val r: Float,
    val g: Float,
    val b: Float
) {
    /** Vélocité de la plaque au point donné, tangente à la sphère (ω × p). */
    fun velocityAt(p: Vec3): Vec3 = Vec3(
        (eulerAxis.y * p.z - eulerAxis.z * p.y) * omegaRadPerMa,
        (eulerAxis.z * p.x - eulerAxis.x * p.z) * omegaRadPerMa,
        (eulerAxis.x * p.y - eulerAxis.y * p.x) * omegaRadPerMa
    )
}

/**
 * Découpage de la planète en plaques — lot 1.4.
 *
 * ## Méthode
 *
 * Voronoï sphérique discret sur les cellules de l'icosphère : des graines
 * tirées uniformément, chaque cellule rattachée à la graine la plus proche
 * (plus grand produit scalaire), puis **une** itération de relaxation de
 * Lloyd — chaque graine migre au centroïde de sa cellule.
 *
 * Une seule itération, mesuré avant écriture : sans relaxation, une plaque
 * sur deux générations à 40 graines se réduit à une poignée de cellules —
 * artefact de discrétisation, pas petite plaque ; avec deux itérations, les
 * aires s'uniformisent (rapport max/min ~3) alors que les plaques réelles
 * sont très inégales. Une itération garde un rapport de 4 à 5 en éliminant
 * les dégénérées.
 *
 * ## Ce que ce lot ne fait pas
 *
 * Rien n'agit encore sur le relief : les frontières (1.5) puis le relief
 * dérivé (1.6) viendront ensuite. La tectonique tire sa graine de son propre
 * chemin (`derive("tectonique")`) : les mondes existants ne changent donc
 * pas d'un bit — `GENERATION_VERSION` reste inchangé, et le test d'empreinte
 * doit rester vert sur les références figées.
 */
class PlateSet(
    val plates: List<Plate>,
    /** Plaque de chaque sommet de la grille. */
    val plateId: IntArray
) {

    fun plateOf(vertexIndex: Int): Plate = plates[plateId[vertexIndex]]

    /** Nombre de cellules par plaque, pour le HUD et les tests. */
    fun cellCounts(): IntArray {
        val counts = IntArray(plates.size)
        for (id in plateId) counts[id]++
        return counts
    }

    companion object {

        /** Bornes du tirage du nombre de plaques (feuille de route : 15 à 40). */
        const val MIN_PLATES = 15
        const val MAX_PLATES = 40

        /** Vitesses angulaires étalonnées sur la Terre, en rad/Ma. */
        const val MIN_OMEGA = 0.003f
        const val MAX_OMEGA = 0.016f

        fun generate(seed: Seed, sphere: Icosphere, oceanFraction: Float): PlateSet {
            val rng = seed.derive("tectonique").rng()
            val n = sphere.vertexCount
            val vertices = sphere.vertices
            val plateCount = rng.nextIntRange(MIN_PLATES, MAX_PLATES)

            // --- Graines uniformes sur la sphère, par rejet ---
            var seeds = Array(plateCount) { randomUnit(rng) }

            // --- Assignation puis une relaxation de Lloyd ---
            val assign = IntArray(n)
            assignAll(vertices, seeds, assign)
            seeds = relaxed(vertices, seeds, assign)
            assignAll(vertices, seeds, assign)

            // --- Compaction : une plaque vidée par la relaxation disparaît ---
            //
            // Rarissime avec une itération, mais possible : plutôt que de
            // promettre « aucune plaque vide » par croisement de doigts, on le
            // garantit par construction et les tests s'appuient dessus.
            val counts = IntArray(plateCount)
            for (id in assign) counts[id]++
            val remap = IntArray(plateCount) { -1 }
            var kept = 0
            for (k in 0 until plateCount) if (counts[k] > 0) remap[k] = kept++
            for (i in 0 until n) assign[i] = remap[assign[i]]

            // --- Caractères des plaques ---
            //
            // L'ordre des tirages est figé par plaque (type, axe, vitesse) :
            // en insérer un nouveau au milieu changerait toutes les plaques
            // des mondes existants. À enrichir, ajouter EN FIN de liste.
            val plates = ArrayList<Plate>(kept)
            var built = 0
            for (k in 0 until plateCount) {
                val oceanic = rng.nextFloat() < oceanFraction
                val axis = randomUnit(rng)
                val omega = rng.nextFloatRange(MIN_OMEGA, MAX_OMEGA)
                if (remap[k] == -1) continue   // tirages consommés quand même :
                // une plaque vide ne doit pas décaler les caractères des
                // suivantes d'une graine à l'autre.

                val hue = (built * 0.61803398f) % 1f   // nombre d'or : teintes espacées
                val (r, g, b) = plateColor(hue, oceanic)
                plates.add(Plate(built, seeds[k], oceanic, axis, omega, r, g, b))
                built++
            }

            return PlateSet(plates, assign)
        }

        private fun assignAll(vertices: Array<Vec3>, seeds: Array<Vec3>, out: IntArray) {
            for (i in vertices.indices) {
                val v = vertices[i]
                var best = 0
                var bestDot = -2f
                for (k in seeds.indices) {
                    val d = v.x * seeds[k].x + v.y * seeds[k].y + v.z * seeds[k].z
                    if (d > bestDot) { bestDot = d; best = k }
                }
                out[i] = best
            }
        }

        /** Chaque graine migre au centroïde sphérique de sa cellule. */
        private fun relaxed(
            vertices: Array<Vec3>,
            seeds: Array<Vec3>,
            assign: IntArray
        ): Array<Vec3> {
            val sx = FloatArray(seeds.size)
            val sy = FloatArray(seeds.size)
            val sz = FloatArray(seeds.size)
            for (i in vertices.indices) {
                val k = assign[i]
                sx[k] += vertices[i].x; sy[k] += vertices[i].y; sz[k] += vertices[i].z
            }
            return Array(seeds.size) { k ->
                val len = sqrt(sx[k] * sx[k] + sy[k] * sy[k] + sz[k] * sz[k])
                if (len > 1e-6f) Vec3(sx[k] / len, sy[k] / len, sz[k] / len)
                else seeds[k]   // cellule vide : la graine reste où elle est
            }
        }

        private fun randomUnit(rng: com.terra.core.Rng): Vec3 {
            while (true) {
                val x = rng.nextFloatSigned()
                val y = rng.nextFloatSigned()
                val z = rng.nextFloatSigned()
                val l2 = x * x + y * y + z * z
                if (l2 in 1e-4f..1f) {
                    val inv = 1f / sqrt(l2)
                    return Vec3(x * inv, y * inv, z * inv)
                }
            }
        }

        /**
         * Couleur d'une plaque : teinte espacée par le nombre d'or, famille
         * bleutée et sombre pour l'océanique, chaude et claire pour la
         * continentale — le type se lit avant même la frontière.
         */
        private fun plateColor(hue: Float, oceanic: Boolean): Triple<Float, Float, Float> {
            val h6 = hue * 6f
            val sector = h6.toInt() % 6
            val f = h6 - h6.toInt()
            val (s, v) = if (oceanic) 0.45f to 0.52f else 0.55f to 0.88f
            val p = v * (1f - s)
            val q = v * (1f - s * f)
            val t = v * (1f - s * (1f - f))
            return when (sector) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
        }
    }
}
