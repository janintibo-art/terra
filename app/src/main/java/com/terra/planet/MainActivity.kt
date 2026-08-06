package com.terra.planet

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.terra.core.SimClock
import com.terra.core.clamp
import com.terra.sim.Biome
import com.terra.sim.CoarseSampler
import com.terra.sim.ConsoleCommand
import com.terra.sim.PlanetCamera
import com.terra.sim.TerrainRaycaster
import com.terra.sim.MapLayer
import com.terra.sim.PlanetData
import com.terra.sim.PlanetParams
import com.terra.sim.WorldGenerator
import com.terra.sim.WorldNamer
import com.terra.sim.WorldSave
import com.terra.sim.WorldTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Écran principal.
 *
 * Apports de cette version :
 *  - le monde survit à la fermeture (nom + paramètres + tick sauvegardés)
 *  - on peut nommer un monde et le retrouver exactement
 *  - cinq calques de données pour juger la génération sur les grandeurs réelles
 *  - le temps planétaire s'écoule vraiment, avec jours, saisons et contrôles
 *  - inertie de rotation, et remontée des erreurs GPU dans le HUD
 */
class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: PlanetRenderer
    private lateinit var configChooser: BestConfigChooser
    private lateinit var hud: TextView
    private lateinit var loading: TextView
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var store: WorldStore
    private lateinit var layerBar: LinearLayout
    private lateinit var timeBar: LinearLayout

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "terra-worldgen").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val generating = AtomicBoolean(false)

    /** Le monde vit ici, pas dans le renderer : il survit à la mise en veille. */
    @Volatile private var world: PlanetData? = null
    @Volatile private var meshBuildMs: Long = 0L

    /**
     * Pool de maillage des tuiles. Créé ici et non dans le renderer : il doit
     * survivre aux pertes de contexte OpenGL et mourir avec l'activité.
     */
    private val tilePool = TileWorkerPool()

    /** Caméra de descente, en double précision. Fil d'interface uniquement. */
    private var camera: PlanetCamera? = null
    private var raycaster: TerrainRaycaster? = null
    private var descentActive = false
    private var worldEpoch = 0
    private lateinit var modeButton: TextView
    private var lastPinchFocusY = 0f

    private val clock = SimClock(stepSeconds = 1f / 30f, maxStepsPerFrame = 240)
    private var worldTime = WorldTime()
    private var params = PlanetParams(subdivisions = 5)
    private var currentLayer = MapLayer.BIOME
    private var staleSave = false

    private var hudVisible = true
    private var lastX = 0f
    private var lastY = 0f
    private var yawVelocity = 0f
    private var pitchVelocity = 0f
    private var dragging = false
    private var touchDownAt = 0L
    private var touchMoved = false
    private var lastTickNanos = 0L

    private val speeds = listOf(0f, 1f, 20f, 200f)
    private val speedLabels = listOf("❚❚", "×1", "×20", "×200")
    private var speedIndex = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WorldStore(this)

        renderer = PlanetRenderer(tilePool)
        configChooser = BestConfigChooser()
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(configChooser)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        hud = TextView(this).apply {
            setTextColor(Color.argb(195, 150, 255, 190))
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            setPadding(24, 24, 24, 24)
        }

        loading = TextView(this).apply {
            setTextColor(Color.argb(235, 220, 235, 255))
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }

        layerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 20)
        }
        for (layer in MapLayer.values()) {
            layerBar.addView(makeButton(layer.shortLabel) { switchLayer(layer) })
        }

        timeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 20)
        }
        speedLabels.forEachIndexed { index, label ->
            timeBar.addView(makeButton(label) { setSpeed(index) })
        }
        timeBar.addView(makeButton("Monde") { showSeedDialog() })
        modeButton = makeButton("Sol") { toggleDescent() }
        timeBar.addView(modeButton)

        setContentView(FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            addView(layerBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            addView(timeBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
            addView(loading, FrameLayout.LayoutParams(-1, -1))
        })

        scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                    lastPinchFocusY = d.focusY
                    return true
                }

                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val factor = if (d.scaleFactor > 0.01f) d.scaleFactor else 1f
                    val cam = camera
                    if (descentActive && cam != null) {
                        // Pincer rapproche du point sous les doigts, pas du
                        // centre de l'écran : sans cela, viser un détail est
                        // un exercice de patience.
                        val target = groundDirectionAtScreen(cam, d.focusX, d.focusY)
                        if (target != null) cam.zoomTowards(target, 1.0 / factor)
                        else cam.zoom(1.0 / factor)

                        // Le déplacement vertical du centre de pincement pilote
                        // l'inclinaison — le geste des globes virtuels.
                        val dy = d.focusY - lastPinchFocusY
                        lastPinchFocusY = d.focusY
                        cam.tilt(dy * 0.006)

                        settleCamera(cam)
                    } else {
                        renderer.distance = clamp(renderer.distance / factor, 1.22f, 9f)
                    }
                    return true
                }
            })

        restoreOrCreateWorld()
        startUiLoop()
    }

    // ------------------------------------------------------------ boutons

    private fun makeButton(label: String, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.argb(225, 225, 240, 255))
            setPadding(26, 16, 26, 16)
            background = GradientDrawable().apply {
                cornerRadius = 14f
                setColor(Color.argb(105, 12, 24, 44))
                setStroke(2, Color.argb(90, 150, 200, 255))
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(6, 0, 6, 0)
            layoutParams = lp
            setOnClickListener { onTap() }
        }

    private fun refreshButtonStates() {
        for (i in 0 until layerBar.childCount) {
            highlight(layerBar.getChildAt(i) as TextView, i == currentLayer.ordinal)
        }
        for (i in 0 until speedLabels.size) {
            highlight(timeBar.getChildAt(i) as TextView, i == speedIndex)
        }
    }

    private fun highlight(view: TextView, active: Boolean) {
        (view.background as? GradientDrawable)?.apply {
            setColor(if (active) Color.argb(165, 30, 70, 120) else Color.argb(105, 12, 24, 44))
            setStroke(2, if (active) Color.argb(210, 180, 230, 255) else Color.argb(90, 150, 200, 255))
        }
        view.setTextColor(
            if (active) Color.argb(255, 235, 250, 255) else Color.argb(190, 200, 220, 240)
        )
    }

    // ------------------------------------------------------------- monde

    private fun restoreOrCreateWorld() {
        val snapshot = store.load()
        if (snapshot != null && snapshot.worldName.isNotEmpty()) {
            staleSave = snapshot.isStale
            params = snapshot.params
            clock.restore(snapshot.tick)
            generateWorld(snapshot.worldName)
        } else {
            staleSave = false
            clock.reset()
            generateWorld(WorldNamer.randomName(System.nanoTime()))
        }
    }

    private fun generateWorld(name: String) {
        if (!generating.compareAndSet(false, true)) return

        loading.visibility = View.VISIBLE
        loading.text = "Terra\n\n$name\n\ninitialisation…"

        worker.execute {
            try {
                val data = WorldGenerator.fromName(name, params).generate { stage, progress ->
                    val pct = (progress * 100f).roundToInt()
                    mainHandler.post { loading.text = "Terra\n\n$name\n\n${stage.label}\n$pct %" }
                }

                // L'analyse géographique et l'empreinte sont calculées ici, sur
                // le fil de travail, pour ne jamais bloquer l'affichage.
                data.geography
                data.fingerprint

                val meshStart = System.nanoTime()
                val mesh = PlanetMesh(data, currentLayer)
                meshBuildMs = (System.nanoTime() - meshStart) / 1_000_000L

                world = data
                worldTime = WorldTime(axialTiltDeg = params.axialTiltDeg)
                renderer.pendingMesh = mesh

                // Contexte du rendu à tuiles. L'époque croissante signale au
                // fil OpenGL de jeter tout ce qui a été maillé pour l'ancien
                // monde ; CoarseSampler construit ici son graphe d'adjacence,
                // sur le fil de travail plutôt qu'à la première tuile.
                worldEpoch++
                raycaster = TerrainRaycaster(data.terrain)
                renderer.tileContext = TileContext(
                    data.terrain, CoarseSampler(data),
                    data.params.radiusM.toDouble(), worldEpoch
                )
                mainHandler.post {
                    camera?.let { old ->
                        camera = PlanetCamera(
                            data.params.radiusM.toDouble(),
                            old.focusLatRad, old.focusLonRad,
                            old.rangeM, old.headingRad, old.tiltRad
                        )
                        camera?.let { settleCamera(it) }
                    }
                }

                persist()
                mainHandler.post {
                    loading.visibility = View.GONE
                    refreshButtonStates()
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    loading.visibility = View.VISIBLE
                    loading.text = "Échec de la génération\n\n" +
                            "${t.javaClass.simpleName}\n${t.message ?: ""}"
                }
            } finally {
                generating.set(false)
            }
        }
    }

    private fun switchLayer(layer: MapLayer) {
        if (layer == currentLayer) return
        currentLayer = layer
        refreshButtonStates()
        val data = world ?: return
        worker.execute {
            val started = System.nanoTime()
            val mesh = PlanetMesh(data, layer)
            meshBuildMs = (System.nanoTime() - started) / 1_000_000L
            renderer.pendingMesh = mesh
        }
    }

    private fun setSpeed(index: Int) {
        speedIndex = index
        clock.timeScale = speeds[index]
        refreshButtonStates()
    }

    private fun persist() {
        val data = world ?: return
        if (data.name.isEmpty()) return
        store.save(WorldSave.Snapshot(data.name, params, clock.tick))
    }

    private fun showSeedDialog() {
        val current = world?.name ?: ""
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(current)
            setSelection(text.length)
            hint = "Nom du monde"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Choisir un monde")
            .setMessage(
                "Le nom est la graine : un même nom reconstruit toujours " +
                "exactement la même planète."
            )
            .setView(input)
            .setPositiveButton("Générer") { _, _ ->
                val name = WorldNamer.sanitize(input.text.toString())
                if (name.isNotEmpty() && name != current) {
                    clock.reset()
                    staleSave = false
                    generateWorld(name)
                }
            }
            .setNeutralButton("Aléatoire") { _, _ ->
                clock.reset()
                staleSave = false
                generateWorld(WorldNamer.randomName(System.nanoTime()))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------------------------------------------------------- descente

    private fun toggleDescent() {
        if (descentActive) {
            descentActive = false
            renderer.descentMode = false
            modeButton.text = "Sol"
            refreshButtonStates()
            return
        }
        val data = world ?: return
        if (camera == null) {
            // Première entrée : plein globe à 0°, 0°. Viser d'office le plus
            // grand continent serait mieux ; la géographie ne fournit pas
            // encore son centroïde, et la console (« tp ») comble l'écart en
            // attendant.
            camera = PlanetCamera(
                data.params.radiusM.toDouble(),
                rangeM = 16_000_000.0
            )
        }
        descentActive = true
        modeButton.text = "Globe"
        camera?.let { settleCamera(it) }
        renderer.descentMode = true
        refreshButtonStates()
    }

    /**
     * Ancre la caméra sur le relief puis publie l'instantané au renderer.
     * À appeler après toute manipulation — c'est le seul point de passage
     * entre la caméra mutable du fil d'interface et le fil OpenGL.
     */
    private fun settleCamera(cam: PlanetCamera) {
        raycaster?.let { cam.snapToTerrain(it) }
        val eye = cam.eyePositionM()
        val fwd = cam.forward()
        val up = cam.up()
        renderer.cameraSnapshot = CameraSnapshot(
            eye.x, eye.y, eye.z,
            fwd.x.toFloat(), fwd.y.toFloat(), fwd.z.toFloat(),
            up.x.toFloat(), up.y.toFloat(), up.z.toFloat(),
            cam.eyeAltitudeM(),
            PlanetCamera.DEFAULT_FOV_RAD.toFloat()
        )
    }

    /** Direction du sol sous un point de l'écran, ou null si le rayon manque. */
    private fun groundDirectionAtScreen(cam: PlanetCamera, px: Float, py: Float): com.terra.core.Vec3d? {
        val rc = raycaster ?: return null
        val w = glView.width.toDouble().coerceAtLeast(1.0)
        val h = glView.height.toDouble().coerceAtLeast(1.0)
        val ndcX = px / w * 2.0 - 1.0
        val ndcY = 1.0 - py / h * 2.0
        val dir = cam.rayDirection(ndcX, ndcY, w / h)
        return rc.cast(cam.eyePositionM(), dir)?.direction
    }

    // -------------------------------------------------------------- console

    private fun showConsoleDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "tp 45.5 -73.6 500   ·   aide"
            typeface = Typeface.MONOSPACE
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Console")
            .setView(input)
            .setPositiveButton("Exécuter") { _, _ -> runConsole(input.text.toString()) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun runConsole(line: String) {
        when (val cmd = ConsoleCommand.parse(line)) {
            is ConsoleCommand.Teleport -> {
                if (!descentActive) toggleDescent()
                val cam = camera ?: return
                cam.focusLatRad = Math.toRadians(cmd.latDeg)
                cam.focusLonRad = Math.toRadians(cmd.lonDeg)
                cmd.rangeM?.let { cam.rangeM = it }
                settleCamera(cam)
            }
            is ConsoleCommand.LoadWorld -> {
                val name = WorldNamer.sanitize(cmd.name)
                if (name.isNotEmpty() && !generating.get()) {
                    clock.reset()
                    staleSave = false
                    generateWorld(name)
                }
            }
            is ConsoleCommand.SetMode -> {
                if (cmd.descent != descentActive) toggleDescent()
            }
            is ConsoleCommand.SetLocalHour -> {
                val lon = camera?.focusLonRad ?: 0.0
                val jump = com.terra.sim.SolarTime.ticksUntilLocalHour(
                    worldTime, clock.tick, lon, cmd.hour
                )
                clock.restore(clock.tick + jump)
            }
            is ConsoleCommand.Help -> showConsoleMessage(ConsoleCommand.HELP_TEXT)
            is ConsoleCommand.Invalid -> showConsoleMessage(cmd.message)
        }
    }

    private fun showConsoleMessage(text: String) {
        AlertDialog.Builder(this)
            .setTitle("Console")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    // --------------------------------------------------------------- HUD

    private fun startUiLoop() {
        refreshButtonStates()
        mainHandler.post(object : Runnable {
            override fun run() {
                tickSimulation()
                if (hudVisible) hud.text = buildHudText()
                mainHandler.postDelayed(this, 100L)
            }
        })
    }

    /**
     * Avance le temps planétaire et applique inertie et éclairage.
     *
     * L'horloge à pas fixe pilote enfin quelque chose : rotation propre de la
     * planète, position du soleil, saison. À vitesse ×1, une journée
     * planétaire dure environ quarante-huit secondes de temps réel.
     */
    private fun tickSimulation() {
        val now = System.nanoTime()
        val dt = if (lastTickNanos == 0L) 0.1f
                 else ((now - lastTickNanos) / 1_000_000_000f).coerceIn(0f, 0.5f)
        lastTickNanos = now

        // En descente, le temps s'écoule à l'échelle de l'observateur : la
        // dilatation continue de PlanetCamera évite que le cycle jour/nuit ne
        // stroboscope au sol. Les multiplicateurs restent appliqués par-dessus.
        val dilation = if (descentActive) {
            (camera?.timeDilationFactor() ?: 1.0).toFloat()
        } else 1f
        clock.advance(dt * dilation) { }

        renderer.spinDeg = worldTime.spinDegrees(clock.tick)
        val sun = worldTime.sunDirection(clock.tick)
        renderer.sunX = sun[0]; renderer.sunY = sun[1]; renderer.sunZ = sun[2]

        // Inertie : la planète continue de tourner après un glissement, et
        // ralentit progressivement.
        if (!dragging) {
            if (abs(yawVelocity) > 0.01f || abs(pitchVelocity) > 0.01f) {
                renderer.yawDeg += yawVelocity
                renderer.pitchDeg = clamp(renderer.pitchDeg + pitchVelocity, -85f, 85f)
                yawVelocity *= 0.90f
                pitchVelocity *= 0.90f
            } else {
                yawVelocity = 0f
                pitchVelocity = 0f
            }
        }
    }

    /**
     * HUD compact.
     *
     * La version précédente débordait sous la barre de boutons : les dernières
     * lignes étaient illisibles. On regroupe donc les informations par ligne
     * plutôt que de les empiler, et on limite la liste des biomes.
     */
    private fun buildHudText(): String {
        val w = world ?: return "TERRA v$VERSION\ngénération en cours…"
        val s = w.stats
        val g = w.geography
        val sb = StringBuilder()

        renderer.lastError?.let { sb.append("⚠ ").append(it).append('\n') }

        sb.append("TERRA v").append(VERSION).append(" · ").append(currentLayer.label)
        if (staleSave) sb.append(" · monde d'une version antérieure")
        sb.append('\n')

        sb.append(w.name.ifEmpty { "(sans nom)" }).append("  ")
            .append(w.seed.shortCode()).append("  ").append(w.fingerprintHex().take(8)).append('\n')

        sb.append(worldTime.format(clock.tick))
            .append(" · δ").append(fmt(worldTime.sunDeclinationDeg(clock.tick))).append("°\n")

        sb.append(fmt(renderer.fps)).append(" i/s · ").append(fmt(renderer.frameMs))
            .append(" ms · ").append(configChooser.chosenSamples).append("×AA · ")
            .append(renderer.glRenderer.take(16)).append('\n')

        sb.append(renderer.drawnTriangles).append(" tri · gen ").append(s.generationMs)
            .append(" ms · maille ").append(meshBuildMs).append(" ms\n")

        if (descentActive) {
            val cam = camera
            if (cam != null) {
                sb.append("alt ").append(formatAltitude(cam.eyeAltitudeM()))
                    .append(" · tuiles ").append(renderer.tilesDrawn)
                    .append('/').append(renderer.tilesSelected)
                    .append(" · manque ").append(renderer.tilesMissing)
                    .append(" · cache ").append(renderer.tilesCached)
                    .append(" · file ").append(tilePool.pendingCount).append('\n')
                if (renderer.gpuPoolSummary.isNotEmpty()) {
                    sb.append("gpu ").append(renderer.gpuPoolSummary).append('\n')
                }
            }
        }
        sb.append('\n')

        sb.append("océans ").append(fmt(s.oceanFractionActual * 100f)).append(" % · littoral ")
            .append(g.coastlineKm.roundToInt()).append(" km\n")

        sb.append(g.continentCount).append(" continents · ").append(g.islandCount)
            .append(" îles · ").append(g.inlandSeaCount).append(" mers · ")
            .append(g.lakeCount).append(" lacs\n")

        sb.append("plus grand ").append(fmt(g.largestLandmassFraction * 100f))
            .append(" % · fragmentation ").append(fmt(g.fragmentation * 100f)).append(" %\n")

        sb.append("alt moy ").append(g.meanLandAltitudeM.roundToInt()).append(" m · max ")
            .append(s.highestAltitudeM.roundToInt()).append(" m · mont ")
            .append(fmt(g.mountainFraction * 100f)).append(" %\n")

        sb.append("climat ").append(fmt(s.coldestC)).append(" … ").append(fmt(s.hottestC))
            .append(" °C · ").append(s.distinctBiomes).append(" biomes\n")

        // Part de la surface sous la glace : l'indicateur qui a révélé la
        // planète boule de neige de la v0.3. On le garde en vue permanente.
        val iceShare = listOf(Biome.SEA_ICE, Biome.GLACIER, Biome.SNOW)
            .sumOf { s.biomeCounts[it] ?: 0 }
            .toFloat() * 100f / w.vertexCount
        sb.append("glaces ").append(fmt(iceShare)).append(" % de la surface\n")

        s.biomeCounts.entries
            .sortedByDescending { it.value }
            .take(4)
            .forEach { (biome, count) ->
                sb.append("  ").append(biome.label).append(' ')
                    .append(fmt(count * 100f / w.vertexCount)).append(" %\n")
            }

        return sb.toString()
    }

    private fun fmt(v: Float): String = String.format("%.1f", v)

    // ------------------------------------------------------------ gestes

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                touchDownAt = System.currentTimeMillis()
                touchMoved = false
                dragging = true
                yawVelocity = 0f
                pitchVelocity = 0f
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && e.pointerCount == 1) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    if (dx * dx + dy * dy > 36f) touchMoved = true
                    val cam = camera
                    if (descentActive && cam != null) {
                        // Le glissement déplace le point visé, d'une amplitude
                        // proportionnelle à la distance — la formule unique qui
                        // fait rouler le globe de loin et défiler le sol de près.
                        cam.pan(
                            dx.toDouble(), dy.toDouble(),
                            glView.height.toDouble().coerceAtLeast(1.0)
                        )
                        settleCamera(cam)
                    } else {
                        // La sensibilité suit la distance : de près, on pivote plus lentement.
                        val speed = 0.22f * (renderer.distance / 3.2f)
                        val dYaw = -dx * speed
                        val dPitch = dy * speed
                        renderer.yawDeg += dYaw
                        renderer.pitchDeg = clamp(renderer.pitchDeg + dPitch, -85f, 85f)
                        yawVelocity = dYaw * 0.55f
                        pitchVelocity = dPitch * 0.55f
                    }
                    lastX = e.x; lastY = e.y
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                touchMoved = true
                if (e.pointerCount == 2 && System.currentTimeMillis() - touchDownAt < 250L) {
                    hudVisible = !hudVisible
                    hud.visibility = if (hudVisible) View.VISIBLE else View.GONE
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                lastX = e.x; lastY = e.y
            }

            MotionEvent.ACTION_UP -> {
                dragging = false
                val held = System.currentTimeMillis() - touchDownAt
                if (!touchMoved && held > 600L && !generating.get()) {
                    if (descentActive) showConsoleDialog() else showSeedDialog()
                }
            }

            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    // ------------------------------------------------------- cycle de vie

    override fun onResume() {
        super.onResume()
        glView.onResume()
        lastTickNanos = 0L
        world?.let { data ->
            if (renderer.drawnTriangles == 0) {
                worker.execute { renderer.pendingMesh = PlanetMesh(data, currentLayer) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        persist()
        glView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        persist()
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        tilePool.shutdown()
    }

    private fun formatAltitude(m: Double): String = when {
        m >= 100_000.0 -> "${(m / 1000.0).roundToInt()} km"
        m >= 1_000.0 -> String.format("%.1f km", m / 1000.0)
        else -> "${m.roundToInt()} m"
    }

    companion object {
        const val VERSION = "0.8.6"
    }
}
