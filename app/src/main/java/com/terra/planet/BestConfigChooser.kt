package com.terra.planet

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Choix de la configuration d'affichage, avec anti-aliasing si le GPU le permet.
 *
 * Le rendu low-poly de Terra est fait d'arêtes vives : sans lissage, chaque bord
 * de facette scintille en escalier dès que la planète tourne. Le multi-échantillonnage
 * corrige cela pour un coût modéré.
 *
 * Tous les GPU ne l'offrent pas. On tente donc 4 échantillons, puis 2, puis on
 * se rabat sur une configuration ordinaire — un appareil ancien affichera une
 * image crénelée plutôt qu'un écran noir.
 */
class BestConfigChooser : GLSurfaceView.EGLConfigChooser {

    /** Nombre d'échantillons réellement obtenu, affiché dans le HUD. */
    @Volatile var chosenSamples: Int = 0
        private set

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (samples in intArrayOf(4, 2)) {
            val config = tryConfig(egl, display, samples)
            if (config != null) {
                chosenSamples = samples
                return config
            }
        }
        chosenSamples = 0
        return tryConfig(egl, display, 0)
            ?: throw IllegalStateException("Aucune configuration OpenGL ES 2 disponible")
    }

    private fun tryConfig(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
        val attributes = if (samples > 0) {
            intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 0,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SAMPLE_BUFFERS, 1,
                EGL10.EGL_SAMPLES, samples,
                EGL10.EGL_NONE
            )
        } else {
            intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 0,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_NONE
            )
        }

        return try {
            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, attributes, null, 0, count) || count[0] <= 0) {
                return null
            }
            val configs = arrayOfNulls<EGLConfig>(count[0])
            if (!egl.eglChooseConfig(display, attributes, configs, count[0], count)) {
                return null
            }
            configs[0]
        } catch (t: Throwable) {
            Log.w(TAG, "Configuration à $samples échantillons indisponible", t)
            null
        }
    }

    companion object {
        private const val TAG = "TerraConfig"
        private const val EGL_OPENGL_ES2_BIT = 4
    }
}
