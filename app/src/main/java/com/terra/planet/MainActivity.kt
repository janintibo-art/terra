package com.terra.planet

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.terra.core.Seed
import com.terra.core.clamp
import com.terra.sim.Biome
import com.terra.sim.PlanetData
import com.terra.sim.PlanetParams
import com.terra.sim.WorldGenerator
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Écran principal.
 *
 * Responsabilités de ce lot :
 *  - générer le monde hors du fil principal, avec progression réelle (lot 0.11)
 *  - conserver [PlanetData] hors du renderer pour survivre à une perte de
 *    contexte OpenGL (lot 0.9)
 *  - afficher un HUD de debug instrumenté (lot 0.6)
 *  - contrôles tactiles : rotation, pincement, appui long pour un nouveau monde
 */
class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: PlanetRenderer
    private lateinit var hud: TextView
    private lateinit var loading: TextView
    private lateinit var scaleDetector: ScaleGestureDetector

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "terra-worldgen").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val generating = AtomicBoolean(false)

    /** Le monde vit ici, pas dans le renderer : il survit à la mise en veille. */
    @Volatile private var world: PlanetData? = null
    @Volatile private var currentSeedValue: Long = 0L
    @Volatile private var meshBuildMs: Long = 0L

    private var hudVisible = true
    private var lastX = 0f
    private var lastY = 0f
    private var touchDownAt = 0L
    private var touchMoved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = PlanetRenderer()
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 0, 16, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        hud = TextView(this).apply {
            setTextColor(Color.argb(190, 150, 255, 190))
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(24, 24, 24, 24)
        }

        loading = TextView(this).apply {
            setTextColor(Color.argb(230, 220, 235, 255))
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }

        setContentView(FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            addView(loading, FrameLayout.LayoutParams(-1, -1))
        })

        scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val factor = if (d.scaleFactor > 0.01f) d.scaleFactor else 1f
                    renderer.distance = clamp(renderer.distance / factor, 1.25f, 9f)
                    return true
                }
            })

        generateWorld(System.currentTimeMillis())
        startHudLoop()
    }

    // ---------------------------------------------------------------- monde

    private fun generateWorld(seedValue: Long) {
        if (!generating.compareAndSet(false, true)) return

        currentSeedValue = seedValue
        loading.visibility = View.VISIBLE
        loading.text = "Terra\n\ninitialisation…"

        worker.execute {
            try {
                val seed = Seed.master(seedValue)
                val generator = WorldGenerator(seed, PlanetParams(subdivisions = 5))

                val data = generator.generate { stage, progress ->
                    val pct = (progress * 100f).roundToInt()
                    mainHandler.post {
                        loading.text = "Terra\n\n${stage.label}\n$pct %"
                    }
                }

                val meshStart = System.nanoTime()
                val mesh = PlanetMesh(data)
                meshBuildMs = (System.nanoTime() - meshStart) / 1_000_000L

                world = data
                renderer.pendingMesh = mesh

                mainHandler.post { loading.visibility = View.GONE }
            } catch (t: Throwable) {
                mainHandler.post {
                    loading.visibility = View.VISIBLE
                    loading.text = "Échec de la génération\n\n${t.javaClass.simpleName}\n${t.message ?: ""}"
                }
            } finally {
                generating.set(false)
            }
        }
    }

    // ------------------------------------------------------------------ HUD

    private fun startHudLoop() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (hudVisible) hud.text = buildHudText()
                mainHandler.postDelayed(this, 250L)
            }
        })
    }

    private fun buildHudText(): String {
        val w = world ?: return "TERRA v$VERSION\ngénération en cours…"
        val s = w.stats
        val sb = StringBuilder()

        sb.append("TERRA v").append(VERSION).append("  ·  lot 0.1-0.6 / 1.1-1.3\n")
        sb.append("graine   ").append(w.seed.shortCode())
            .append("  (").append(currentSeedValue).append(")\n")
        sb.append("rendu    ").append(fmt(renderer.fps)).append(" i/s   ")
            .append(fmt(renderer.frameMs)).append(" ms\n")
        sb.append("maillage ").append(renderer.drawnTriangles).append(" triangles   ")
            .append(w.vertexCount).append(" cellules\n")
        sb.append("calcul   monde ").append(s.generationMs).append(" ms   ")
            .append("maillage ").append(meshBuildMs).append(" ms\n")
        sb.append("océans   ").append(fmt(s.oceanFractionActual * 100f)).append(" %\n")
        sb.append("altitude ").append(s.deepestDepthM.roundToInt()).append(" m … +")
            .append(s.highestAltitudeM.roundToInt()).append(" m\n")
        sb.append("climat   ").append(fmt(s.coldestC)).append(" °C … ")
            .append(fmt(s.hottestC)).append(" °C\n")
        sb.append("biomes   ").append(s.distinctBiomes).append(" présents sur ")
            .append(Biome.values().size).append('\n')

        s.biomeCounts.entries
            .sortedByDescending { it.value }
            .take(6)
            .forEach { (biome, count) ->
                val pct = count * 100f / w.vertexCount
                sb.append("  ").append(biome.label.padEnd(17))
                    .append(fmt(pct)).append(" %\n")
            }

        sb.append("\nglisser : pivoter · pincer : zoomer\n")
        sb.append("appui long : nouveau monde · 2 doigts : HUD")
        return sb.toString()
    }

    private fun fmt(v: Float): String = String.format("%.1f", v)

    // -------------------------------------------------------------- gestes

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                touchDownAt = System.currentTimeMillis()
                touchMoved = false
                renderer.autoRotate = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && e.pointerCount == 1) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    if (dx * dx + dy * dy > 36f) touchMoved = true
                    // La sensibilité suit la distance : de près, on pivote plus lentement.
                    val speed = 0.22f * (renderer.distance / 3.2f)
                    renderer.yaw += dx * speed
                    renderer.pitch = clamp(renderer.pitch + dy * speed, -85f, 85f)
                    lastX = e.x; lastY = e.y
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                touchMoved = true
                if (e.pointerCount == 2) {
                    // Deux doigts posés simultanément : bascule du HUD.
                    if (System.currentTimeMillis() - touchDownAt < 250L) {
                        hudVisible = !hudVisible
                        hud.visibility = if (hudVisible) View.VISIBLE else View.GONE
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                lastX = e.x; lastY = e.y
            }

            MotionEvent.ACTION_UP -> {
                val held = System.currentTimeMillis() - touchDownAt
                if (!touchMoved && held > 600L && !generating.get()) {
                    generateWorld(System.nanoTime())
                }
                renderer.autoRotate = true
            }

            MotionEvent.ACTION_CANCEL -> renderer.autoRotate = true
        }
        return true
    }

    // ------------------------------------------------------------ cycle de vie

    override fun onResume() {
        super.onResume()
        glView.onResume()
        // Si le contexte GL a été détruit pendant la veille, le maillage est
        // reconstruit à partir des données du monde, qui n'ont pas bougé.
        world?.let { data ->
            if (renderer.drawnTriangles == 0) {
                worker.execute { renderer.pendingMesh = PlanetMesh(data) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    companion object {
        const val VERSION = "0.2.0"
    }
}
