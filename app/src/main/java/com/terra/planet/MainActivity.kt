package com.terra.planet

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.terra.core.SimClock
import com.terra.core.TerraLogger
import com.terra.core.clamp
import com.terra.sim.Biome
import com.terra.sim.CoarseSampler
import com.terra.sim.ConsoleCommand
import com.terra.sim.PlanetCamera
import com.terra.sim.ScaleRegister
import com.terra.sim.ScaleRegistry
import com.terra.sim.TerrainRaycaster
import com.terra.sim.TileWorkerPool
import com.terra.sim.MapLayer
import com.terra.sim.ParamEditor
import com.terra.sim.PlanetData
import com.terra.sim.PlanetMesh
import com.terra.sim.PlanetParams
import com.terra.sim.SeasonalClimate
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
    private val tilePool = TileWorkerPool(
        // Adaptateur logcat : la simulation ne connaît qu'une interface,
        // seul :app sait qu'Android existe. C'est ce qui a permis de rendre
        // le pool testable en CI (v0.33.0).
        logger = TerraLogger { tag, message, cause -> Log.e(tag, message, cause) }
    )

    /** Caméra de descente, en double précision. Fil d'interface uniquement. */
    private var camera: PlanetCamera? = null
    /**
     * Écrit sur le fil de génération, lu sur le fil d'interface : sans
     * `@Volatile`, rien ne garantit que l'affectation devienne visible, et
     * le repli de secours prenait alors le relais en silence (v0.10.3).
     */
    @Volatile private var raycaster: TerrainRaycaster? = null
    private var descentActive = false

    /**
     * Registre d'échelle courant (lot 2.7-a) : étiquette de navigation
     * stabilisée par hystérésis, affichée au HUD. Au 2.7-b, son changement
     * déclenchera la bascule quadtree ↔ globe — c'est pour cela que l'état
     * vit ici, mis à jour à 10 Hz par la boucle d'interface, et non recalculé
     * à la volée dans le texte du HUD.
     */
    private val scaleRegistry = ScaleRegistry()
    private var worldEpoch = 0
    private lateinit var modeButton: TextView

    /** Mode piéton (v0.29.0) : l'œil à hauteur d'homme, la marche à pied. */
    private var pedestrianMode = false
    private lateinit var pedestrianButton: TextView
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
    private var uiLoopRunning = false

    private val speeds = listOf(0f, 1f, 20f, 200f, 400f)
    private val speedLabels = listOf("❚❚", "×1", "×20", "×200", "×400")

    // Tiroirs latéraux (v0.16.2) : les barres vivent dans deux panneaux
    // rétractables, calques à gauche, temps et monde à droite. Fermés, seule
    // la poignée dépasse ; l'écran reste nu pour la contemplation.
    private lateinit var leftDrawer: LinearLayout
    private lateinit var rightDrawer: LinearLayout
    private lateinit var leftHandle: TextView
    private lateinit var rightHandle: TextView
    private var leftOpen = false
    private var rightOpen = false

    // Joystick du mode sol (v0.17.1). Sa boucle de déplacement tourne à
    // 16 ms — la boucle UI, à 100 ms, donnerait un défilement par saccades —
    // et ne vit QUE pendant que le manche est engagé : zéro coût au repos.
    private lateinit var joystick: JoystickView
    private var joystickLoopRunning = false

    // Globe haute définition (v0.19.0) : géométrie du globe évaluée sur le
    // terrain continu, un niveau plus fin que la grille. Construit une fois
    // par monde sur le fil de travail ; les changements de calque le
    // réutilisent et ne refont que la passe de couleurs.
    @Volatile private var globeDetail: com.terra.sim.GlobeRefinement? = null

    /**
     * Échantillonneur du monde courant, partagé par la météo et le HUD.
     *
     * Sa construction bâtit le graphe d'adjacence des 10 242 cellules : la
     * v0.27.0 en créait un NEUF à chaque rafraîchissement du HUD en mode
     * sol, dix fois par seconde. Construit une fois par monde, sur le fil de
     * travail comme le reste de la génération.
     */
    @Volatile private var worldSampler: CoarseSampler? = null

    // --- Suivi du champ d'arbres (lot 3.5-c) ---
    // La forêt se reconstruit quand l'œil s'écarte de plus de 35 % du
    // rayon du dernier champ (validation/suivi_foret.py §3), sur le fil de
    // travail, avec garde anti-empilement.
    @Volatile private var forestFollow = false
    private var forestRadiusM = 400.0
    @Volatile private var forestCenter: com.terra.core.Vec3d? = null
    @Volatile private var forestBuilding = false

    /**
     * Vitesse plein manche, en pixels de glissement équivalents par seconde.
     * Le joystick passe par cam.pan(), la même mécanique que le doigt :
     * la vitesse au sol reste donc proportionnelle à l'altitude, sans code
     * dédié. 900 px/s ≈ un glissement de doigt soutenu.
     */
    private val joystickSpeedPxPerS = 900f

    // Extrêmes thermiques du JOUR courant (lot 1.12). Recalculés seulement
    // quand le jour planétaire change : un parcours des 10 000 cellules à
    // chaque rafraîchissement du HUD serait du gaspillage pur.
    private var weatherHint = 0
    private var seasonCacheDay = -1
    private var seasonCacheYear = -1L
    private var seasonLoC = 0f
    private var seasonHiC = 0f
    private var speedIndex = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WorldStore(this)

        renderer = PlanetRenderer(tilePool)
        renderer.onCapture = { pixels, w, h -> saveCapture(pixels, w, h) }
        applyScreenDensity()
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

        // Les barres deviennent des COLONNES : l'ordre des enfants est
        // inchangé, refreshButtonStates() continue d'indexer les mêmes
        // boutons. Largeur uniforme pour un bord de tiroir net.
        val column = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2) }

        layerBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 4, 8)
        }
        for (layer in MapLayer.values()) {
            layerBar.addView(makeButton(layer.shortLabel) { switchLayer(layer) }, column())
        }

        timeBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 12, 8)
        }
        speedLabels.forEachIndexed { index, label ->
            timeBar.addView(makeButton(label) { setSpeed(index) }, column())
        }
        timeBar.addView(makeButton("Régl.") { showParamEditor() }, column())
        timeBar.addView(makeButton("Monde") { showSeedDialog() }, column())
        timeBar.addView(makeButton("Console") { showConsoleDialog() }, column())
        timeBar.addView(makeButton("Photo") { renderer.captureRequested = true }, column())
        modeButton = makeButton("Sol") { toggleDescent() }
        timeBar.addView(modeButton, column())
        pedestrianButton = makeButton("Piéton") { togglePedestrian() }
        pedestrianButton.visibility = View.GONE
        timeBar.addView(pedestrianButton, column())

        // Poignées : toujours visibles au coin, elles font coulisser le
        // panneau. Le chevron pointe vers l'endroit où le tiroir ira.
        leftHandle = makeButton("❯") { toggleDrawer(left = true) }
        rightHandle = makeButton("❮") { toggleDrawer(left = false) }

        leftDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(layerBar)
            addView(leftHandle, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.BOTTOM
            })
        }
        rightDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(rightHandle, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.BOTTOM
            })
            addView(timeBar)
        }

        joystick = JoystickView(this).apply {
            visibility = View.GONE
            onEngaged = { startJoystickLoop() }
        }

        // Position fermée recalée à CHAQUE layout : c'est ce qui garde le
        // tiroir correctement replié après une rotation d'écran, quand la
        // largeur du panneau change. Un post{} unique ne suffirait pas.
        layerBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!leftOpen) leftDrawer.translationX = -layerBar.width.toFloat()
        }
        timeBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!rightOpen) rightDrawer.translationX = timeBar.width.toFloat()
        }

        setContentView(FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            addView(leftDrawer, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            addView(rightDrawer, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
            // Au-dessus de la poignée gauche, sous le pouce. Taille en
            // pixels physiques via la densité : 140 dp sur tout écran.
            val joySize = (140f * resources.displayMetrics.density).toInt()
            addView(joystick, FrameLayout.LayoutParams(joySize, joySize,
                Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = (18f * resources.displayMetrics.density).toInt()
                bottomMargin = (64f * resources.displayMetrics.density).toInt()
            })
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
                // Le raffinement précède le maillage : ~40 000 évaluations
                // du terrain continu, une seule fois par monde.
                val detail = com.terra.sim.GlobeRefinement(data)
                globeDetail = detail
                // Le ciel du monde : les étoiles naissent avec lui (lot 2.12).
                renderer.pendingStars = com.terra.sim.CelestialSky.generateStars(data.seed)
                // Carte d'humidité : les nuages du monde suivront ses pluies
                // (lot 2.14 b). Projetée ici, sur le fil de travail, une fois
                // par monde — 32 Ko que le GPU échantillonnera en temps
                // constant, là où la recherche du plus proche voisin serait
                // impensable des centaines de milliers de fois par image.
                renderer.pendingHumidity = com.terra.sim.HumidityMap.build(data)
                val mesh = PlanetMesh(data, currentLayer, detail)
                meshBuildMs = (System.nanoTime() - meshStart) / 1_000_000L

                world = data
                worldTime = WorldTime(axialTiltDeg = params.axialTiltDeg)
                // Globe métrique (lot 2.7-b1) : l'inverse de l'exagération du
                // relief, calculé dans :sim où sa garde (exagération nulle)
                // est testée.
                renderer.deExagFactorM = com.terra.sim.GlobeMetric.deExaggerationFactor(
                    params.maxAltitudeM, params.reliefExaggeration
                )
                renderer.pendingMesh = mesh

                // Contexte du rendu à tuiles. L'époque croissante signale au
                // fil OpenGL de jeter tout ce qui a été maillé pour l'ancien
                // monde ; CoarseSampler construit ici son graphe d'adjacence,
                // sur le fil de travail plutôt qu'à la première tuile.
                worldEpoch++
                raycaster = TerrainRaycaster(data.terrain)
                val sampler = CoarseSampler(data)
                worldSampler = sampler
                renderer.tileContext = TileContext(
                    data.terrain, sampler,
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
            val mesh = PlanetMesh(data, layer, globeDetail)
            meshBuildMs = (System.nanoTime() - started) / 1_000_000L
            renderer.pendingMesh = mesh
        }
    }

    /** Densité d'écran transmise au renderer (taille des points d'étoile). */
    private fun applyScreenDensity() {
        renderer.pointScale = resources.displayMetrics.density.coerceIn(1f, 4f)
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

    /**
     * Coulisse un tiroir. La cible est calculée depuis la largeur MESURÉE du
     * panneau, jamais une constante : les libellés changent (« Sol » devient
     * « Globe »), la police d'accessibilité peut grossir, et une largeur
     * codée en dur laisserait un jour un bord de panneau dépasser.
     */
    private fun toggleDrawer(left: Boolean) {
        if (left) {
            leftOpen = !leftOpen
            leftHandle.text = if (leftOpen) "❮" else "❯"
            leftDrawer.animate()
                .translationX(if (leftOpen) 0f else -layerBar.width.toFloat())
                .setDuration(160).start()
        } else {
            rightOpen = !rightOpen
            rightHandle.text = if (rightOpen) "❯" else "❮"
            rightDrawer.animate()
                .translationX(if (rightOpen) 0f else timeBar.width.toFloat())
                .setDuration(160).start()
        }
    }

    /**
     * Bascule du mode piéton. À l'entrée, la caméra se pose debout : œil à
     * 1,70 m, regard à l'horizontale. À la sortie, rien à défaire — le
     * mode ne fait que contraindre la portée à chaque image.
     */
    private fun togglePedestrian() {
        pedestrianMode = !pedestrianMode
        pedestrianButton.text = if (pedestrianMode) "Voler" else "Piéton"
        val cam = camera ?: return
        if (pedestrianMode) {
            cam.tiltRad = PlanetCamera.MAX_TILT_RAD
            cam.rangeM = PlanetCamera.pedestrianRangeM(cam.tiltRad)
            settleCamera(cam)
        }
    }

    // ------------------------------------------------- éditeur (lot 1.18)

    /**
     * Panneau de curseurs sur les paramètres de génération. Toute la
     * connaissance (bornes, pas, libellés) vient de [ParamEditor] dans
     * :sim ; ici on ne fait que dessiner.
     *
     * SEULS les curseurs réellement déplacés sont réécrits dans les
     * paramètres : la grille d'un curseur ne retombe pas toujours au bit
     * près sur la valeur d'usine, et réécrire un paramètre non touché
     * suffirait à changer le monde à la régénération (voir le commentaire
     * « piège du pas flottant » dans ParamEditor).
     */
    private fun showParamEditor() {
        val current = params
        val edits = HashMap<String, Int>()   // id → index de grille choisi

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
        }
        for (spec in ParamEditor.specs) {
            val title = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                text = "${spec.label} : ${spec.format(spec.read(current))}" +
                    if (spec.affectsGeneration) "" else "   (rendu seul)"
            }
            val slider = SeekBar(this).apply {
                max = spec.steps
                progress = spec.indexOf(spec.read(current))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                        // fromUser : une mise à jour programmatique ne doit
                        // jamais compter comme une édition.
                        if (!fromUser) return
                        edits[spec.id] = value
                        title.text = "${spec.label} : ${spec.format(spec.valueAt(value))}" +
                            if (spec.affectsGeneration) "" else "   (rendu seul)"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            list.addView(title)
            list.addView(slider)
        }

        AlertDialog.Builder(this)
            .setTitle("Paramètres de la planète")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("Régénérer") { _, _ ->
                if (edits.isEmpty()) return@setPositiveButton
                var p = params
                for (spec in ParamEditor.specs) {
                    val index = edits[spec.id] ?: continue
                    p = spec.write(p, spec.valueAt(index))
                }
                applyParams(p)
            }
            .setNeutralButton("Défauts") { _, _ ->
                // Retour à l'usine, subdivisions comprises : c'est la
                // planète « catalogue », celle des empreintes de référence.
                applyParams(PlanetParams(subdivisions = 5))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /**
     * Applique un nouveau jeu de paramètres au monde COURANT : même nom,
     * autre recette. Le temps repart de zéro — c'est une autre planète,
     * pas la même vieillie — et la sauvegarde suivra par le circuit
     * habituel, désormais complet grâce au format 2.
     */
    private fun applyParams(p: PlanetParams) {
        if (p == params) return
        val name = world?.name ?: return
        params = p
        clock.reset()
        staleSave = false
        generateWorld(name)
    }

    // ---------------------------------------------------------- descente

    private fun toggleDescent() {
        if (descentActive) {
            descentActive = false
            renderer.descentMode = false
            joystick.visibility = View.GONE
            pedestrianButton.visibility = View.GONE
            pedestrianMode = false
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
        joystick.visibility = View.VISIBLE
        pedestrianButton.visibility = View.VISIBLE
        modeButton.text = "Globe"
        camera?.let { settleCamera(it) }
        renderer.descentMode = true
        refreshButtonStates()
    }

    /**
     * Boucle de déplacement du joystick, démarrée à l'engagement du manche
     * et qui s'éteint d'elle-même au relâchement ou à la sortie du mode sol.
     *
     * POURQUOI PAS pan() : la v0.17.1 passait par la sémantique écran de
     * pan(), et le manche s'inversait près du sol. Diagnostic : la
     * direction-monde du « haut de l'écran » n'est pas la même dans les
     * deux régimes de caméra — à forte inclinaison la géométrie impose le
     * cap (vers l'horizon), en vue plongeante la convention validée en
     * v0.8.4 lui est opposée. Aucun signe unique ne peut satisfaire les
     * deux, et un signe conditionnel aurait consacré l'incohérence.
     *
     * La base du manche est donc PROJETÉE depuis les axes réellement
     * rendus : « pousser en haut » suit up() de la caméra projeté sur le
     * plan tangent au sol, « pousser à droite » suit right(), qui est
     * tangent par construction (f × haut). C'est juste à toute
     * inclinaison, par construction, sans raisonnement de salon sur les
     * signes. up() et forward() couvrent mutuellement leurs
     * dégénérescences : up() devient radial à l'horizon rasant (on prend
     * alors forward(), horizontal), forward() devient radial en vue
     * plongeante (up() y est horizontal).
     */
    private fun startJoystickLoop() {
        if (joystickLoopRunning) return
        joystickLoopRunning = true
        mainHandler.post(object : Runnable {
            override fun run() {
                val cam = camera
                if (!descentActive || cam == null ||
                    (joystick.vx == 0f && joystick.vy == 0f)) {
                    joystickLoopRunning = false
                    return
                }

                val radial = cam.focusDirection()
                val upV = cam.up()
                var f = upV - radial * (upV dot radial)
                if (f.lengthSq < 1e-10) {
                    val fw = cam.forward()
                    f = fw - radial * (fw dot radial)
                }
                if (f.lengthSq >= 1e-12) {
                    f = f.normalized()
                    val r = cam.right()
                    // Même échelle de vitesse que le doigt : mètres par
                    // pixel à la distance courante, sans la compensation
                    // d'inclinaison de pan() — la projection la remplace.
                    // En piéton, la vitesse est ABSOLUE : la règle du
                    // glissement (proportionnelle à l'altitude) donnerait
                    // 35 km/h à 12 m de portée — un promeneur supersonique.
                    // Manche à fond = course, manche à mi-course = marche.
                    val dM = if (pedestrianMode) {
                        val push = kotlin.math.hypot(
                            joystick.vx.toDouble(), joystick.vy.toDouble()
                        ).coerceIn(0.0, 1.0)
                        val speed = PlanetCamera.WALK_SPEED_MS +
                            (PlanetCamera.RUN_SPEED_MS - PlanetCamera.WALK_SPEED_MS) *
                            ((push - 0.5) / 0.5).coerceIn(0.0, 1.0)
                        speed * 0.016
                    } else {
                        val metresPerPixel = 2.0 * cam.rangeM *
                            kotlin.math.tan(PlanetCamera.DEFAULT_FOV_RAD * 0.5) /
                            glView.height.toDouble().coerceAtLeast(1.0)
                        joystickSpeedPxPerS * 0.016 * metresPerPixel
                    }
                    val move = f * (joystick.vy * dM) + r * (joystick.vx * dM)
                    cam.moveFocusMetres(
                        move dot com.terra.core.Geodesy.northAt(radial),
                        move dot com.terra.core.Geodesy.eastAt(radial)
                    )
                    settleCamera(cam)
                }
                mainHandler.postDelayed(this, 16L)
            }
        })
    }

    /**
     * Ancre la caméra sur le relief puis publie l'instantané au renderer.
     * À appeler après toute manipulation — c'est le seul point de passage
     * entre la caméra mutable du fil d'interface et le fil OpenGL.
     */
    private fun settleCamera(cam: PlanetCamera) {
        // En piéton, la portée découle de l'inclinaison — et l'inclinaison
        // reste au-dessus du seuil où l'on ne verrait plus que ses pieds.
        // Appliqué AVANT l'ancrage : snapToTerrain peut encore repousser
        // l'œil si une paroi se dresse devant, et c'est souhaitable.
        if (pedestrianMode) {
            if (cam.tiltRad < PlanetCamera.PEDESTRIAN_MIN_TILT_RAD) {
                cam.tiltRad = PlanetCamera.PEDESTRIAN_MIN_TILT_RAD
            }
            cam.rangeM = PlanetCamera.pedestrianRangeM(cam.tiltRad)
        }
        raycaster?.let { cam.snapToTerrain(it) }
        val eye = cam.eyePositionM()
        // Hauteur réelle au-dessus du relief, mesurée par le lancer de rayon
        // sur la surface RENDUE — la même que voit le mailleur. Sans elle, le
        // plan de coupe proche se calait sur l'altitude marine et rognait le
        // premier plan (v0.10.1).
        val heightAboveGround = raycaster?.heightAboveTerrain(eye) ?: cam.eyeAltitudeM()
        val fwd = cam.forward()
        val up = cam.up()
        renderer.cameraSnapshot = CameraSnapshot(
            eye.x, eye.y, eye.z,
            fwd.x.toFloat(), fwd.y.toFloat(), fwd.z.toFloat(),
            up.x.toFloat(), up.y.toFloat(), up.z.toFloat(),
            cam.eyeAltitudeM(),
            heightAboveGround,
            cam.rangeM,
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
        // Commandes rapides : chaque bouton EST la ligne de commande qu'il
        // affiche — même analyse, même chemin runConsole que le champ libre.
        // Aucune logique nouvelle ici, donc rien de nouveau à tester : le
        // dialogue n'est qu'un clavier prérempli.
        var dialog: AlertDialog? = null
        // column() d'onCreate est un LOCAL, hors de portée ici — et son
        // MATCH_PARENT vertical serait faux pour une rangée : paramètres
        // propres, largeur au contenu.
        fun cell() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        fun quickRow(vararg commands: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (c in commands) addView(makeButton(c) {
                dialog?.dismiss()
                runConsole(c)
            }, cell())
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 0)
            addView(quickRow("limbe auto", "limbe tuiles", "limbe globe"))
            addView(quickRow("arbre conifère", "arbre palmier", "arbre off"))
            addView(quickRow("arbre moyen", "arbre bas", "arbre panneau"))
            addView(quickRow("arbre", "banc limbe", "photo"))
            addView(quickRow("foret", "foret 800", "foret off"))
            addView(quickRow("flore", "teinte", "soleil 12", "aide"))
            addView(input)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Console")
            .setView(panel)
            .setPositiveButton("Exécuter") { _, _ -> runConsole(input.text.toString()) }
            .setNegativeButton("Fermer", null)
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
            ConsoleCommand.ShowFlora -> {
                val cam = camera
                val sampler = worldSampler
                if (cam != null && sampler != null) {
                    val dir = cam.focusDirection().toVec3()
                    val cell = sampler.nearestVertex(dir, weatherHint)
                    weatherHint = cell
                    val biome = sampler.biomeAt(dir, cell)
                    val density = com.terra.sim.VegetationRules.densityFor(biome)
                    val mix = com.terra.sim.VegetationRules.mixFor(biome)
                    val mixText = if (mix.isEmpty()) "aucune végétation"
                    else mix.joinToString("\n") {
                        "  ${it.species.label} : ${(it.weight * 100).toInt()} %"
                    }
                    showConsoleMessage(
                        "Au point visé : ${biome.label}\n" +
                            "Densité de peuplement : ${(density * 100).toInt()} %\n" +
                            mixText
                    )
                }
            }
            is ConsoleCommand.BuildForest -> {
                forestRadiusM = cmd.radiusM
                forestFollow = true
                showConsoleMessage("Construction de la forêt (suivi actif)…")
                rebuildForest(announce = true)
            }
            ConsoleCommand.HideForest -> {
                forestFollow = false
                renderer.clearTreeField()
                com.terra.sim.PlantExclusion.clear()
                // Restaurer les losanges autour du dernier champ : éviction
                // ciblée, pas le vidage complet du cache.
                forestCenter?.let { c ->
                    val data = world
                    renderer.pendingPlantEvict = doubleArrayOf(
                        c.x, c.y, c.z, forestRadiusM + 100.0,
                        (data?.params?.radiusM ?: 6_371_000f).toDouble()
                    )
                }
                forestCenter = null
                showConsoleMessage("Forêt retirée, suivi coupé, losanges restaurés.")
            }
            ConsoleCommand.HideTree -> {
                renderer.clearTestTree()
                showConsoleMessage("Arbre retiré.")
            }
            is ConsoleCommand.ShowTree -> {
                if (!descentActive) toggleDescent()
                val cam = camera
                val ray = raycaster
                val data = world
                if (cam != null && ray != null && data != null) {
                    val tree = com.terra.sim.TreeGenerator.generate(
                        cmd.species.params(), cmd.seed
                    )
                    // Ancre au point visé, posée sur le relief.
                    val up = cam.focusDirection()
                    val altM = kotlin.math.max(0.0, ray.altitudeAlong(up))
                    // Le pied s'enfonce sous le terrain EXACT : le sol
                    // dessiné est une surface de tuile qui passe plus bas
                    // entre ses nœuds, et un arbre posé sur l'exact flotte
                    // au-dessus du visible (leçon v0.26.1). La profondeur
                    // est calculée et testée dans :sim.
                    val r = data.params.radiusM.toDouble() + altM - tree.footSinkM()
                    // Repère local : haut × nord = est (convention).
                    val pole = com.terra.core.Vec3d(0.0, 1.0, 0.0)
                    val north = (pole - up * (up dot pole)).normalized()
                    val east = up cross north
                    // Colonnes (est, haut, nord) : le Y local de l'arbre
                    // est sa verticale (voir PlanetRenderer).
                    val frame = floatArrayOf(
                        east.x.toFloat(), east.y.toFloat(), east.z.toFloat(),
                        up.x.toFloat(), up.y.toFloat(), up.z.toFloat(),
                        north.x.toFloat(), north.y.toFloat(), north.z.toFloat()
                    )
                    val mesh = com.terra.sim.TreeMesh.build(
                        tree, cmd.species.params(), detail = cmd.detail
                    )
                    renderer.plantTestTree(
                        mesh,
                        up.x * r, up.y * r, up.z * r,
                        frame
                    )
                    val triangles = mesh.size / com.terra.sim.TreeMesh.FLOATS_PER_VERTEX / 3
                    showConsoleMessage(
                        "${cmd.species.label} n°${cmd.seed} (${cmd.detail.label}) " +
                            "planté au point visé.\n" +
                            "${tree.segments.size} segments · $triangles triangles · " +
                            "pied enfoui de ${"%.0f".format(tree.footSinkM() * 100)} cm · " +
                            "${"%.1f".format(tree.heightM())} m de haut, " +
                            "${"%.1f".format(tree.spreadM())} m d'envergure.\n" +
                            "« arbre off » pour le retirer."
                    )
                }
            }
            is ConsoleCommand.BenchLimb -> {
                if (!descentActive) toggleDescent()
                val cam = camera
                if (cam != null) {
                    cam.rangeM = cmd.altitudeKm * 1000.0
                    cam.tiltRad = 0.0
                    settleCamera(cam)
                    runConsole("soleil 12")
                    showConsoleMessage(
                        "Banc du limbe : nadir à ${cmd.altitudeKm.toInt()} km, " +
                            "soleil au zénith.\nPhoto, puis « limbe tuiles » ou " +
                            "« limbe globe », et Photo à nouveau."
                    )
                }
            }
            ConsoleCommand.TakePhoto -> {
                renderer.captureRequested = true
                // Le message viendra de saveCapture, avec le chemin réel.
            }
            is ConsoleCommand.SetLimbMode -> {
                renderer.limbMode = cmd.mode
                val label = when (cmd.mode) {
                    1 -> "globe métrique (forcé)"
                    2 -> "collerette (diagnostic)"
                    3 -> "auto — globe en orbite"
                    else -> "tuiles (forcé)"
                }
                showConsoleMessage(
                    "Limbe : $label." +
                        if (!descentActive) " (visible en mode sol)" else ""
                )
            }
            is ConsoleCommand.SetLevelTint -> {
                renderer.debugLevelTint = cmd.enabled ?: !renderer.debugLevelTint
                showConsoleMessage(
                    if (renderer.debugLevelTint) "Teinte par niveau activée."
                    else "Teinte par niveau désactivée."
                )
            }
            is ConsoleCommand.Help -> showConsoleMessage(ConsoleCommand.HELP_TEXT)
            is ConsoleCommand.Invalid -> showConsoleMessage(cmd.message)
        }
    }

    /**
     * Enregistre une capture — lot 2.20-a. Appelé sur le FIL GL avec les
     * pixels bruts : on décampe aussitôt vers un fil de travail, la
     * compression PNG (~centaines de ms) n'a rien à faire dans la boucle
     * de rendu.
     */
    private fun saveCapture(pixels: java.nio.ByteBuffer, w: Int, h: Int) {
        Thread {
            try {
                val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                src.copyPixelsFromBuffer(pixels)
                // glReadPixels livre les lignes de BAS en haut : miroir
                // vertical, sinon la planète est tête en bas.
                val flip = android.graphics.Matrix().apply { postScale(1f, -1f) }
                val shot = Bitmap.createBitmap(src, 0, 0, w, h, flip, false)
                src.recycle()

                val name = com.terra.sim.CaptureName.build(
                    world?.name ?: "monde",
                    BuildConfig.VERSION_NAME,
                    if (descentActive) camera?.eyeAltitudeM() else null,
                    System.currentTimeMillis()
                )

                val where: String
                if (Build.VERSION.SDK_INT >= 29) {
                    // Galerie via MediaStore : AUCUNE permission requise à
                    // partir de l'API 29 — conforme à la promesse du projet.
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Terra")
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: throw IllegalStateException("insertion MediaStore refusée")
                    contentResolver.openOutputStream(uri).use { out ->
                        checkNotNull(out) { "flux de sortie nul" }
                        shot.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    where = "Galerie · Pictures/Terra/$name"
                } else {
                    // API < 29 : la galerie exigerait une permission
                    // d'écriture, contraire à la promesse. Le dossier privé
                    // de l'appli n'en demande aucune ; il est lisible au
                    // gestionnaire de fichiers (Android/data/…).
                    val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                    val f = java.io.File(dir, name)
                    java.io.FileOutputStream(f).use { out ->
                        shot.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    where = f.absolutePath
                }
                shot.recycle()
                runOnUiThread { showConsoleMessage("Capture enregistrée :\n$where") }
            } catch (t: Throwable) {
                runOnUiThread {
                    showConsoleMessage("Capture échouée : ${t.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun showConsoleMessage(text: String) {
        AlertDialog.Builder(this)
            .setTitle("Console")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    // --------------------------------------------------------------- HUD

    /**
     * Boucle d'interface et de simulation, 10 Hz.
     *
     * Elle s'ARRÊTE en arrière-plan : jusqu'à la v0.30.0, seul le rendu était
     * suspendu par glView.onPause(), et cette boucle continuait de faire
     * avancer le temps planétaire, de bâtir le texte du HUD et d'échantillonner
     * la météo, écran éteint — batterie consommée pour rien. Le drapeau est
     * lu à chaque itération plutôt que de retirer les messages : c'est plus
     * simple à raisonner, et le pas suivant s'éteint de lui-même.
     */
    private fun startUiLoop() {
        if (uiLoopRunning) return
        uiLoopRunning = true
        refreshButtonStates()
        mainHandler.post(object : Runnable {
            override fun run() {
                if (!uiLoopRunning) return
                tickSimulation()
                forestFollowStep()
                if (hudVisible) hud.text = buildHudText()
                mainHandler.postDelayed(this, 100L)
            }
        })
    }

    /**
     * Reconstruit le champ d'arbres à la position courante — lot 3.5-c.
     * Appelée par la console et par le suivi ; la garde [forestBuilding]
     * empêche l'empilement si la marche est plus rapide que le fil de
     * travail.
     */
    private fun rebuildForest(announce: Boolean) {
        if (forestBuilding) return
        val cam = camera ?: return
        val sampler = worldSampler ?: return
        val data = world ?: return
        forestBuilding = true
        val eye = cam.eyePositionM()
        val radius = forestRadiusM
        val pxPerRadian = (glView.height / 2f) /
            kotlin.math.tan(com.terra.sim.PlanetCamera.DEFAULT_FOV_RAD / 2.0).toFloat()
        worker.execute {
            try {
                val builder = com.terra.sim.TreeField(
                    data.terrain, sampler, data.params.radiusM.toDouble()
                )
                val field = builder.build(eye, pxPerRadian, radius)
                val meshes = HashMap<com.terra.sim.TreeField.VariantKey, FloatArray>()
                for (inst in field.instances) {
                    meshes.getOrPut(inst.variant) { builder.buildVariantMesh(inst.variant) }
                }
                renderer.pendingTreeField =
                    PlanetRenderer.TreeFieldData(field.instances, meshes)
                com.terra.sim.PlantExclusion.replace(field.occupiedCells)
                // Éviction CIBLÉE : l'union des deux disques (ancien champ,
                // nouveau champ) couvre toutes les tuiles dont les losanges
                // changent. Deux évictions valent plus simple qu'une union.
                val previous = forestCenter
                val planetR = data.params.radiusM.toDouble()
                renderer.pendingPlantEvict = doubleArrayOf(
                    eye.x, eye.y, eye.z, radius + 100.0, planetR
                )
                if (previous != null) {
                    renderer.pendingPlantEvictOld = doubleArrayOf(
                        previous.x, previous.y, previous.z, radius + 100.0, planetR
                    )
                }
                forestCenter = eye
                if (announce) {
                    runOnUiThread {
                        showConsoleMessage(
                            "Forêt : ${field.instances.size} arbres " +
                                "(${field.cellsPlanted} candidats, " +
                                "${field.trianglesSpent} triangles, " +
                                "${meshes.size} variantes). Suivi actif — " +
                                "« foret off » pour couper."
                        )
                    }
                }
            } catch (t: Throwable) {
                if (announce) {
                    runOnUiThread { showConsoleMessage("Échec : ${t.javaClass.simpleName}") }
                }
            } finally {
                forestBuilding = false
            }
        }
    }

    /** Le pas du suivi, appelé à 10 Hz par la boucle d'interface. */
    private fun forestFollowStep() {
        if (!forestFollow || forestBuilding) return
        val cam = camera ?: return
        val center = forestCenter ?: return
        val eye = cam.eyePositionM()
        val dx = eye.x - center.x
        val dy = eye.y - center.y
        val dz = eye.z - center.z
        val moved = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (moved > forestRadiusM * 0.35) rebuildForest(announce = false)
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

        // Registre d'échelle : suivi seulement en descente — en mode globe,
        // la caméra n'a pas d'altitude métrique (repère propre, 0,02..60).
        if (descentActive) {
            camera?.let { scaleRegistry.update(it.eyeAltitudeM()) }
            // Bascule du limbe (2.7-b2) : le renderer suit le registre.
            renderer.orbitRegime = scaleRegistry.current == ScaleRegister.ORBIT
        }

        renderer.spinDeg = worldTime.spinDegrees(clock.tick)
        val sun = worldTime.sunDirection(clock.tick)
        renderer.sunX = sun[0]; renderer.sunY = sun[1]; renderer.sunZ = sun[2]
        // Lune : direction monde du moment, ramenée en local par le renderer
        // avec la même rotation propre que le soleil (lot 2.12).
        world?.let { w ->
            val md = com.terra.sim.CelestialSky.moonDirection(w.seed, worldTime, clock.tick)
            renderer.moonDirX = md.x; renderer.moonDirY = md.y; renderer.moonDirZ = md.z
        }
        // Dérive des nuages : liée aux minutes du monde — à ×200, le ciel
        // court. Le modulo évite la perte de précision du flottant sur les
        // vieux mondes sans créer de saut visible (période >> longueur de
        // corrélation du bruit).
        renderer.cloudDrift = ((clock.tick % 2_000_000L).toFloat()) / 1_440f

        // Météo du point visé (lot 2.15) : décidée dans :sim à partir des
        // précipitations de la cellule et de la température DU MOMENT,
        // saison comprise — la même plaine reçoit pluie en été, neige en
        // hiver. Évaluée seulement en mode sol, une fois par rafraîchissement.
        if (descentActive) {
            val w = world
            val cam = camera
            val sampler = worldSampler
            if (w != null && cam != null && sampler != null) {
                val d = cam.focusDirection().toVec3()
                val cell = sampler.nearestVertex(d, weatherHint)
                weatherHint = cell
                val t = w.temperatureC[cell] + com.terra.sim.SeasonalClimate.deltaC(
                    w.position(cell).y, w.continentality[cell],
                    w.altitudeM[cell] < 0f, worldTime, clock.tick
                )
                val st = com.terra.sim.LocalWeather.stateAt(
                    w.precipMm[cell], t, w.altitudeM[cell] < 0f
                )
                renderer.weatherForm = when (st.form) {
                    com.terra.sim.LocalWeather.Form.RAIN -> 1
                    com.terra.sim.LocalWeather.Form.SNOW -> 2
                    else -> 0
                }
                renderer.weatherIntensity = st.intensity
            }
        } else {
            renderer.weatherForm = 0
        }

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

        // Vitesse RÉELLE du temps. Le bouton annonce un multiplicateur, mais
        // la descente le divise par la dilatation d'altitude — jusqu'à 2 880
        // au ras du sol. Sans cette ligne, le même « ×200 » fait passer un
        // jour en 0,2 s depuis l'orbite et en 12 min à pied, et rien ne
        // l'explique. On affiche donc ce que le monde fait, pas ce qu'on lui
        // a demandé.
        val dilation = if (descentActive) (camera?.timeDilationFactor() ?: 1.0) else 1.0
        val effective = com.terra.sim.TimeReadout.effectiveScale(clock.timeScale, dilation)
        sb.append("temps ").append(com.terra.sim.TimeReadout.formatScale(effective))
        if (dilation < 0.999) {
            sb.append(" (bouton ×").append(clock.timeScale.toInt())
                .append(", descente ÷").append(Math.round(1.0 / dilation)).append(")")
        }
        sb.append(" · jour ").append(
            com.terra.sim.TimeReadout.formatDuration(
                com.terra.sim.TimeReadout.dayRealSeconds(
                    worldTime, clock.timeScale, dilation, clock.stepSeconds
                )
            )
        ).append('\n')

        sb.append(fmt(renderer.fps)).append(" i/s · ").append(fmt(renderer.frameMs))
            .append(" ms · ").append(configChooser.chosenSamples).append("×AA · ")
            .append(renderer.glRenderer.take(16)).append('\n')

        sb.append(renderer.drawnTriangles).append(" tri · gen ").append(s.generationMs)
            .append(" ms · maille ").append(meshBuildMs).append(" ms\n")

        if (descentActive) {
            val cam = camera
            if (cam != null) {
                sb.append("alt ").append(formatAltitude(cam.eyeAltitudeM()))
                    .append(" (").append(scaleRegistry.current.label).append(')')
                // Instruction séparée, PAS un maillon de la chaîne : un when
                // dont la valeur serait consommée par le .append suivant
                // devrait être exhaustif — c'est l'échec de compilation
                // v0.40.0, une chaîne fluide coupée en deux sans le voir.
                when (renderer.limbMode) {
                    0 -> sb.append(" · limbe:tuiles (forcé)")
                    1 -> sb.append(" · limbe:globe (forcé)")
                    2 -> sb.append(" · limbe:collerette")
                    else -> if (renderer.limbModeEffective == 1) {
                        sb.append(" · limbe:globe")
                    }
                }
                sb.append(" · tuiles ").append(renderer.tilesDrawn)
                    .append('/').append(renderer.tilesSelected)
                    .append(" · manque ").append(renderer.tilesMissing)
                    .append(" · cache ").append(renderer.tilesCached)
                    .append(" · file ").append(tilePool.pendingCount).append('\n')
                if (renderer.gpuPoolSummary.isNotEmpty()) {
                    sb.append("gpu ").append(renderer.gpuPoolSummary).append('\n')
                }
                // Diagnostic v0.10.2 : les trois valeurs qui manquent pour
                // trancher sur l'aplat clair du bas d'écran.
                sb.append("sol ").append(formatAltitude(renderer.heightAboveGroundM))
                    .append(" · near ").append(String.format("%.2f m", renderer.nearPlaneM))
                    .append(" · niv max ").append(renderer.maxSelectedLevel)
                    .append('\n')
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

        // Extrêmes du jour : moyenne annuelle + modulation saisonnière
        // (lot 1.12). L'hiver du monde est plus rude que sa moyenne, et
        // c'est enfin visible.
        run {
            val wt = worldTime
            val day = wt.dayOfYear(clock.tick)
            val year = wt.year(clock.tick)
            if (day != seasonCacheDay || year != seasonCacheYear) {
                seasonCacheDay = day
                seasonCacheYear = year
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for (i in 0 until w.vertexCount) {
                    val t = w.temperatureC[i] + SeasonalClimate.deltaC(
                        w.position(i).y, w.continentality[i],
                        w.altitudeM[i] < 0f, wt, clock.tick
                    )
                    if (t < lo) lo = t
                    if (t > hi) hi = t
                }
                seasonLoC = lo
                seasonHiC = hi
            }
            sb.append("aujourd'hui ").append(fmt(seasonLoC)).append(" … ")
                .append(fmt(seasonHiC)).append(" °C\n")
        }

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
        // Remise à zéro AVANT de relancer la boucle : le premier pas repart
        // d'un delta nul, pas de la durée de la veille.
        lastTickNanos = 0L
        startUiLoop()
        // Le filet historique — reconstruire le maillage si drawnTriangles
        // valait zéro — est retiré : il ne se déclenchait JAMAIS (drawGlobe
        // sort avant la ligne qui met drawnTriangles à jour, la condition
        // restait fausse pour toujours) et, s'il l'avait fait, il aurait
        // reconstruit le globe SANS son raffinement haute définition. Le
        // renderer conserve désormais son maillage résident et le reverse
        // lui-même quand le contexte est recréé : aucune dépendance à
        // l'ordre entre onResume et onSurfaceCreated.
    }

    override fun onPause() {
        super.onPause()
        persist()
        // La simulation s'arrête avec le rendu : le temps planétaire ne doit
        // pas courir écran éteint. La reprise du temps est traitée par
        // lastTickNanos = 0 dans onResume — sans quoi le premier pas après
        // la veille vaudrait toute la durée d'absence.
        uiLoopRunning = false
        glView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        persist()
        uiLoopRunning = false
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
        // Lue depuis le gradle : trois livraisons (v0.33.0 → v0.34.1) ont
        // tourné avec un HUD qui affichait « v0.32.2 » parce que cette
        // chaîne était codée en dur ici et jamais mise à jour. Une version
        // qui vit à DEUX endroits finit toujours par mentir à l'un des deux.
        val VERSION: String = BuildConfig.VERSION_NAME
    }
}
