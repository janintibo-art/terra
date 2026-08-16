package com.terra.desktop

import com.terra.sim.GlobeRefinement
import com.terra.sim.MapLayer
import com.terra.sim.PlanetMesh
import com.terra.sim.PlanetParams
import com.terra.sim.WorldGenerator
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_G
import org.lwjgl.glfw.GLFW.GLFW_KEY_L
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_RESIZABLE
import org.lwjgl.glfw.GLFW.GLFW_SAMPLES
import org.lwjgl.glfw.GLFW.GLFW_TRUE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwGetFramebufferSize
import org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor
import org.lwjgl.glfw.GLFW.glfwGetTime
import org.lwjgl.glfw.GLFW.glfwGetVideoMode
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback
import org.lwjgl.glfw.GLFW.glfwSetErrorCallback
import org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback
import org.lwjgl.glfw.GLFW.glfwSetKeyCallback
import org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback
import org.lwjgl.glfw.GLFW.glfwSetScrollCallback
import org.lwjgl.glfw.GLFW.glfwSetWindowPos
import org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose
import org.lwjgl.glfw.GLFW.glfwSetWindowTitle
import org.lwjgl.glfw.GLFW.glfwShowWindow
import org.lwjgl.glfw.GLFW.glfwSwapBuffers
import org.lwjgl.glfw.GLFW.glfwSwapInterval
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.glfw.GLFW.glfwWindowShouldClose
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL33C.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL33C.GL_DEPTH_TEST
import org.lwjgl.opengl.GL33C.GL_MULTISAMPLE
import org.lwjgl.opengl.GL33C.glClear
import org.lwjgl.opengl.GL33C.glClearColor
import org.lwjgl.opengl.GL33C.glEnable
import org.lwjgl.opengl.GL33C.glViewport
import org.lwjgl.system.MemoryUtil.NULL
import kotlin.math.PI
import kotlin.math.max

/**
 * Terra sur PC — lot 10.1.
 *
 * ## Ce que ce lot établit
 *
 * La chaîne complète : fenêtre GLFW, contexte OpenGL 3.3 core, génération
 * d'un monde par le MÊME code que sur Android (`:core` et `:sim` sont
 * partagés tels quels), affichage du globe, et packaging en `Terra.exe`
 * par la CI. Le portage du rendu terrain — quadtree, eau, ciel, arbres —
 * est le lot 10.2 ; c'est le gros morceau, et il vaut mieux découvrir ici
 * ce qui casse dans l'outillage que là-bas.
 *
 * ## Le déterminisme est préservé
 *
 * Un même nom de monde donne la même planète sur téléphone et sur PC :
 * toute la génération vit dans `:sim`, sans une ligne spécifique à une
 * plateforme. Ce qui diffère est le RENDU — nombre de côtés, budgets,
 * portée — jamais la simulation. C'est la distinction qui permet de tout
 * donner en qualité graphique sans casser l'invariant n°1.
 *
 * ## Différences GLES2 → OpenGL 3.3 core, pour le lot 10.2
 *
 * Le profil core interdit le pipeline fixe et EXIGE un objet de tableau de
 * sommets (VAO) lié, sans quoi tout dessin échoue en silence. Les shaders
 * passent de GLSL ES 1.00 à GLSL 3.30 : `attribute` devient `in`,
 * `varying` devient `in`/`out`, `gl_FragColor` devient une sortie
 * déclarée, et les qualificateurs de précision sont ignorés (mais tolérés).
 */
fun main() {
    println("Terra — version PC (lot 10.1)")
    Desktop().run()
}

private class Desktop {

    private var window = NULL
    private var width = 1600
    private var height = 900

    /** Conservé pour être libéré à la fermeture : passer `null` à
     *  glfwSetErrorCallback depuis Kotlin demanderait un transtypage de
     *  plateforme, et fuir le rappel ferait planter la JVM à l'arrêt. */
    private var errorCallback: GLFWErrorCallback? = null

    /** Monde courant ; nul tant que la première génération n'a pas fini. */
    private var world: com.terra.sim.PlanetData? = null

    // Caméra orbitale : mêmes conventions que le mode Globe d'Android.
    private var yawRad = 0.0
    private var pitchRad = 0.35
    private var distance = 3.2

    private var dragging = false
    private var lastCursorX = 0.0
    private var lastCursorY = 0.0

    private var worldName = "Terra"
    private var layerIndex = 0
    private lateinit var globe: GlobeView
    private var meshToUpload: PlanetMesh? = null

    fun run() {
        initWindow()
        globe = GlobeView()
        generateWorld(worldName)
        loop()
        shutdown()
    }

    // ------------------------------------------------------------ fenêtre

    private fun initWindow() {
        // Les erreurs GLFW partent sur la sortie d'erreur : sans ce
        // rappel, un échec de création de contexte est parfaitement muet.
        errorCallback = GLFWErrorCallback.createPrint(System.err)
        glfwSetErrorCallback(errorCallback)
        check(glfwInit()) { "Initialisation de GLFW impossible" }

        glfwDefaultWindowHints()
        // Fenêtre cachée le temps de la placer : évite qu'elle apparaisse
        // dans un coin avant de sauter au centre.
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        // Exigé par macOS, sans effet ailleurs : autant le poser une fois.
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        // Anti-crénelage 8× : sur PC il est gratuit, et le limbe de la
        // planète est précisément ce qui en profite le plus.
        glfwWindowHint(GLFW_SAMPLES, 8)

        window = glfwCreateWindow(width, height, "Terra", NULL, NULL)
        check(window != NULL) { "Création de la fenêtre impossible" }

        glfwSetKeyCallback(window) { win, key, _, action, _ ->
            if (action == GLFW_PRESS) onKey(win, key)
        }
        glfwSetMouseButtonCallback(window) { _, _, action, _ ->
            dragging = action == GLFW_PRESS
        }
        glfwSetCursorPosCallback(window) { _, x, y ->
            if (dragging) {
                yawRad -= (x - lastCursorX) * 0.005
                pitchRad = (pitchRad + (y - lastCursorY) * 0.005)
                    .coerceIn(-PI / 2 + 0.02, PI / 2 - 0.02)
            }
            lastCursorX = x
            lastCursorY = y
        }
        glfwSetScrollCallback(window) { _, _, dy ->
            // Zoom multiplicatif : à distance constante d'un facteur, le
            // pas visuel est le même de près comme de loin.
            distance = (distance * Math.pow(0.90, dy)).coerceIn(1.05, 20.0)
        }
        glfwSetFramebufferSizeCallback(window) { _, w, h ->
            if (w > 0 && h > 0) {
                width = w
                height = h
                glViewport(0, 0, w, h)
            }
        }

        // Centrage sur l'écran principal.
        val mode = glfwGetVideoMode(glfwGetPrimaryMonitor())
        if (mode != null) {
            glfwSetWindowPos(window, (mode.width() - width) / 2, (mode.height() - height) / 2)
        }

        glfwMakeContextCurrent(window)
        // Synchronisation verticale : sans elle, la carte dessine à mille
        // images par seconde en chauffant pour rien.
        glfwSwapInterval(1)
        glfwShowWindow(window)

        // Indispensable : c'est cet appel qui lie LWJGL au contexte courant.
        // Sans lui, le premier appel OpenGL lève une exception obscure.
        GL.createCapabilities()

        glEnable(GL_DEPTH_TEST)
        glEnable(GL_MULTISAMPLE)
        glClearColor(0.02f, 0.02f, 0.04f, 1f)

        val fb = IntArray(1)
        val fbh = IntArray(1)
        glfwGetFramebufferSize(window, fb, fbh)
        width = fb[0]
        height = fbh[0]
        glViewport(0, 0, width, height)
        println("Contexte OpenGL prêt : ${width}×$height")
    }

    private fun onKey(win: Long, key: Int) {
        when (key) {
            GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(win, true)
            GLFW_KEY_G -> generateWorld(randomName())
            GLFW_KEY_L -> {
                layerIndex = (layerIndex + 1) % MapLayer.values().size
                regenerateMesh()
            }
        }
    }

    // ------------------------------------------------------------ monde

    private fun randomName(): String {
        val letters = "BCDFGKLMNPRSTVZ"
        val vowels = "aeiou"
        val rng = java.util.Random()
        val sb = StringBuilder()
        repeat(3) {
            sb.append(letters[rng.nextInt(letters.length)])
            sb.append(vowels[rng.nextInt(vowels.length)])
        }
        return sb.toString()
    }

    /**
     * Génère sur le fil courant : la fenêtre reste figée quelques secondes,
     * ce qui est accepté à ce stade. Le lot 10.2 déportera la génération
     * sur un fil de travail, comme le fait déjà Android.
     */
    private fun generateWorld(name: String) {
        worldName = name
        glfwSetWindowTitle(window, "Terra — $name — génération…")
        println("Génération de « $name »…")
        val started = System.nanoTime()

        val params = PlanetParams()
        val data = WorldGenerator.fromName(name, params).generate { stage, progress ->
            if (progress >= 1f) println("  ${stage.label}")
        }
        data.geography
        data.fingerprint

        world = data
        regenerateMesh()

        val ms = (System.nanoTime() - started) / 1_000_000
        println("« $name » prêt en $ms ms — empreinte ${data.fingerprint}")
        glfwSetWindowTitle(window, "Terra — $name")
    }

    private fun regenerateMesh() {
        val data = world ?: return
        val layer = MapLayer.values()[layerIndex]
        // Raffinement haute définition : sur PC il n'y a aucune raison de
        // s'en priver, c'est exactement le « niveau maximum » demandé.
        meshToUpload = PlanetMesh(data, layer, GlobeRefinement(data))
        println("Calque : ${layer.name}")
    }

    // ------------------------------------------------------------ boucle

    private fun loop() {
        var lastTitleUpdate = glfwGetTime()
        var frames = 0

        while (!glfwWindowShouldClose(window)) {
            meshToUpload?.let {
                globe.upload(it)
                meshToUpload = null
            }

            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

            val aspect = max(0.1f, width.toFloat() / max(1, height).toFloat())
            globe.draw(yawRad, pitchRad, distance, aspect, glfwGetTime())

            glfwSwapBuffers(window)
            glfwPollEvents()

            frames++
            val now = glfwGetTime()
            if (now - lastTitleUpdate >= 1.0) {
                val fps = frames / (now - lastTitleUpdate)
                glfwSetWindowTitle(
                    window,
                    "Terra — $worldName — %.0f i/s — %d triangles".format(
                        fps, globe.triangleCount
                    )
                )
                frames = 0
                lastTitleUpdate = now
            }
        }
    }

    private fun shutdown() {
        globe.dispose()
        glfwFreeCallbacks(window)
        glfwDestroyWindow(window)
        glfwTerminate()
        errorCallback?.free()
        println("Terra fermé proprement.")
    }
}
