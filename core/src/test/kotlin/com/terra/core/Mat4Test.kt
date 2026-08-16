package com.terra.core

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Lot 10.1 — matrices du rendu PC.
 *
 * Ces tests valent surtout par ce qu'ils attrapent : une matrice
 * transposée, une convention de colonnes inversée ou un signe de
 * profondeur faux ne provoquent AUCUNE erreur — seulement une image
 * vide ou retournée, qu'il faudrait diagnostiquer sur machine.
 */
class Mat4Test {

    private fun assertClose(a: Float, b: Float, tol: Float = 1e-5f, what: String = "") {
        assertTrue(abs(a - b) < tol, "$what : $a ≠ $b")
    }

    @Test
    fun identiteNeChangeRien() {
        val id = Mat4.identity()
        val p = Mat4.transformPoint(id, 3f, -7f, 11f)
        assertClose(3f, p[0]); assertClose(-7f, p[1])
        assertClose(11f, p[2]); assertClose(1f, p[3])
    }

    @Test
    fun perspectiveProjetteDansLeCubeCanonique() {
        val near = 0.1f
        val far = 1000f
        val m = Mat4.perspective(1.0f, 16f / 9f, near, far)

        // Un point sur le plan proche, au centre, doit tomber sur z = −1
        // après division perspective ; sur le plan lointain, sur z = +1.
        // Si le signe était faux, tout serait élagué sans message.
        val atNear = Mat4.transformPoint(m, 0f, 0f, -near)
        assertClose(-1f, atNear[2] / atNear[3], 1e-4f, "plan proche")
        val atFar = Mat4.transformPoint(m, 0f, 0f, -far)
        assertClose(1f, atFar[2] / atFar[3], 1e-4f, "plan lointain")

        // w vaut la profondeur : c'est lui qui rétrécit les objets.
        assertClose(5f, Mat4.transformPoint(m, 0f, 0f, -5f)[3], 1e-4f, "w")
    }

    @Test
    fun perspectiveRespecteLeRapportDImage() {
        val m = Mat4.perspective(1.0f, 2f, 0.1f, 100f)
        // À rapport 2, un objet large paraît deux fois moins étendu en x
        // qu'en y — sinon l'image serait étirée.
        val px = Mat4.transformPoint(m, 1f, 0f, -5f)
        val py = Mat4.transformPoint(m, 0f, 1f, -5f)
        assertClose(0.5f, abs(px[0] / py[1]), 1e-5f, "anisotropie")
    }

    @Test
    fun plansInvalidesRefuses() {
        assertFailsWith<IllegalArgumentException> { Mat4.perspective(1f, 1f, 0f, 10f) }
        assertFailsWith<IllegalArgumentException> { Mat4.perspective(1f, 1f, 10f, 5f) }
        assertFailsWith<IllegalArgumentException> { Mat4.perspective(1f, 0f, 1f, 5f) }
    }

    @Test
    fun laVueRegardeDansLaBonneDirection() {
        // Regardant vers −Z avec +Y en haut : c'est déjà le repère d'OpenGL,
        // la matrice doit donc être l'identité.
        val m = Mat4.lookDirection(0f, 0f, -1f, 0f, 1f, 0f)
        for (i in 0 until 16) assertClose(Mat4.identity()[i], m[i], 1e-6f, "élément $i")

        // Regardant vers +X : un point devant l'œil doit se retrouver
        // DEVANT la caméra, donc en z négatif dans l'espace de vue.
        val east = Mat4.lookDirection(1f, 0f, 0f, 0f, 1f, 0f)
        val ahead = Mat4.transformPoint(east, 10f, 0f, 0f)
        assertTrue(ahead[2] < 0f, "un point devant devrait être en z<0 : ${ahead[2]}")
        assertClose(0f, ahead[0], 1e-5f, "pas de dérive latérale")
    }

    @Test
    fun laVueResteOrthonormee() {
        // Un « haut » non orthogonal à la visée doit être redressé, pas
        // déformer l'image.
        val m = Mat4.lookDirection(1f, 1f, 0f, 0f, 1f, 0f)
        for (col in 0 until 3) {
            var len = 0f
            for (row in 0 until 3) len += m[col * 4 + row] * m[col * 4 + row]
            assertClose(1f, sqrt(len), 1e-5f, "colonne $col non unitaire")
        }
    }

    @Test
    fun visesColineaireAuHautNeProduitPasDeNaN() {
        // Regard au zénith : le produit vectoriel s'annule. Sans garde, la
        // normalisation donnerait des NaN et l'écran deviendrait noir.
        val m = Mat4.lookDirection(0f, 1f, 0f, 0f, 1f, 0f)
        for (v in m) assertTrue(v.isFinite(), "NaN dans la matrice de vue")
    }

    @Test
    fun produitDeMatrices() {
        val a = Mat4.rotation(0f, 1f, 0f, 0.7f)
        val b = Mat4.rotation(0f, 1f, 0f, -0.7f)
        // Deux rotations opposées s'annulent.
        val id = Mat4.multiply(a, b)
        for (i in 0 until 16) assertClose(Mat4.identity()[i], id[i], 1e-5f, "élément $i")

        // L'ordre compte : a×b appliqué à un point vaut a(b(point)).
        val m = Mat4.multiply(a, a)
        val direct = Mat4.transformPoint(m, 1f, 0f, 0f)
        val chained = Mat4.transformPoint(a, 0f, 0f, 0f).let {
            val once = Mat4.transformPoint(a, 1f, 0f, 0f)
            Mat4.transformPoint(a, once[0], once[1], once[2])
        }
        for (i in 0 until 3) assertClose(chained[i], direct[i], 1e-5f, "composante $i")
    }

    @Test
    fun rotationConserveLesLongueurs() {
        val m = Mat4.rotation(0.3f, 0.5f, -0.8f, 1.1f)
        val p = Mat4.transformPoint(m, 2f, -3f, 6f)
        val len = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
        assertClose(7f, len, 1e-4f, "norme")   // |(2,−3,6)| = 7
    }

    @Test
    fun conventionColonnesConformeAOpenGL() {
        // La translation vit dans les éléments 12, 13, 14 en ordre colonnes
        // — c'est ce que glUniformMatrix4fv attend sans transposition. Une
        // matrice transposée ne lèverait aucune erreur, l'image serait
        // seulement fausse.
        val m = Mat4.identity()
        m[12] = 5f; m[13] = -2f; m[14] = 3f
        val p = Mat4.transformPoint(m, 0f, 0f, 0f)
        assertClose(5f, p[0]); assertClose(-2f, p[1]); assertClose(3f, p[2])
    }
}
