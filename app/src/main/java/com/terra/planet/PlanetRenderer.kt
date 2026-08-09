package com.terra.planet

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.terra.core.Vec3
import com.terra.sim.CelestialSky
import com.terra.sim.CoarseSampler
import com.terra.sim.Icosphere
import com.terra.sim.PlanetCamera
import com.terra.sim.ScaleRegistry
import com.terra.sim.TerrainProfile
import com.terra.sim.TileId
import com.terra.sim.TileMesh
import com.terra.sim.TileSelector
import com.terra.sim.TileWorkerPool
import com.terra.sim.ViewCone
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Instantané de la caméra de descente, construit sur le fil d'interface et lu
 * sur le fil OpenGL.
 *
 * Immuable par construction : [PlanetCamera][com.terra.sim.PlanetCamera] porte
 * un état mutable en double précision et n'est pas sûre entre fils ; plutôt
 * que de la verrouiller à chaque image, on en fige une copie plate à chaque
 * geste. L'œil reste en double — c'est la moitié de la chaîne de précision
 * relative — le reste peut être en 32 bits sans conséquence.
 */
class CameraSnapshot(
    val eyeXM: Double, val eyeYM: Double, val eyeZM: Double,
    val fwdX: Float, val fwdY: Float, val fwdZ: Float,
    val upX: Float, val upY: Float, val upZ: Float,
    val altitudeM: Double,
    /**
     * Hauteur au-dessus du TERRAIN, distincte de [altitudeM] qui se compte
     * depuis le niveau de la mer. C'est elle qui commande le plan de coupe
     * proche : les confondre supprimait tout le premier plan au sol (v0.10.1).
     */
    val heightAboveGroundM: Double,
    /** Distance de l'œil au point visé au sol : seconde borne du plan de
     *  coupe, toujours disponible même si le lancer de rayon manque. */
    val rangeM: Double,
    val fovRad: Float
)

/**
 * Tout ce qu'il faut pour mailler des tuiles d'un monde donné.
 *
 * [epoch] change à chaque nouveau monde : le fil OpenGL s'en sert pour vider
 * cache et file de travail sans qu'aucun verrou ne traverse les fils.
 */
class TileContext(
    val profile: TerrainProfile,
    val sampler: CoarseSampler,
    val radiusM: Double,
    val epoch: Int
)

/**
 * Moteur de rendu de la planète.
 *
 * ## Deux chemins de rendu
 *
 * Le **globe** (mode par défaut) : le maillage icosphérique entier dans un
 * tampon unique, en unités de sphère, caméra orbitale simple. Chemin hérité,
 * volontairement intact — si le rendu à tuiles déçoit, l'application reste
 * entièrement utilisable.
 *
 * La **descente** (lot B) : sélection de tuiles à chaque image, maillage en
 * tâche de fond, et surtout **coordonnées relatives à la caméra**. La matrice
 * de vue place l'œil à l'origine ; chaque tuile reçoit en uniform le décalage
 * `centre de tuile − œil`, calculé en double sur le CPU et converti en float
 * au dernier moment. Validé numériquement : 0,64 mm d'erreur au ras du sol,
 * là où des coordonnées monde en float32 trembleraient d'un demi-mètre.
 *
 * ## Éclairage calculé au sommet, pas au fragment
 *
 * Les positions métriques atteignent des millions de mètres. Or la précision
 * `mediump` des fragments est un flottant 16 bits sur les GPU Mali, qui sature
 * à 65 504 : normaliser une position planétaire y produirait des infinis et un
 * écran noir difficile à diagnostiquer. Tout ce qui manipule des mètres se
 * calcule donc dans le vertex shader (toujours `highp`), et le fragment ne
 * reçoit que des grandeurs bornées à [0, 1].
 */
class PlanetRenderer(
    private val tilePool: TileWorkerPool
) : GLSurfaceView.Renderer {

    // --- Commandes du globe, écrites depuis le fil UI ---
    @Volatile var yawDeg = 0f
    @Volatile var pitchDeg = 18f
    @Volatile var distance = 3.2f
    @Volatile var spinDeg = 0f
    @Volatile var sunX = 1f
    @Volatile var sunY = 0f
    @Volatile var sunZ = 0f

    // --- Commandes de la descente ---
    @Volatile var descentMode = false

    /** Teinte de diagnostic des tuiles par niveau — console « teinte ». */
    @Volatile var debugLevelTint = false
    @Volatile var cameraSnapshot: CameraSnapshot? = null
    @Volatile var tileContext: TileContext? = null

    // --- Télémétrie lue par le HUD ---
    @Volatile var frameMs = 0f
        private set
    @Volatile var fps = 0f
        private set
    @Volatile var drawnTriangles = 0
        private set
    @Volatile var glRenderer: String = "?"
        private set
    @Volatile var tilesDrawn = 0
        private set
    @Volatile var tilesSelected = 0
        private set
    @Volatile var tilesMissing = 0
        private set
    @Volatile var tilesCached = 0
        private set
    @Volatile var gpuPoolSummary: String = ""
        private set

    /** Niveau de subdivision maximal parmi les tuiles SÉLECTIONNÉES. */
    @Volatile var maxSelectedLevel = 0
        private set

    /** Plan de coupe proche de la dernière image, en mètres. */
    @Volatile var nearPlaneM = 0f
        private set

    /** Hauteur de l'œil au-dessus du terrain, en mètres. */
    @Volatile var heightAboveGroundM = 0.0
        private set

    /** Erreur GPU, affichée plutôt que laissée en écran noir silencieux. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Maillage du globe publié par le fil de travail, en attente de
     * téléversement. Échangé par [java.util.concurrent.atomic.AtomicReference]
     * et non lu-puis-annulé : la v0.29.4 faisait `lire, téléverser, mettre à
     * null`, si bien qu'un maillage écrit PENDANT le téléversement était
     * effacé sans avoir jamais été vu.
     */
    private val pendingMeshRef = java.util.concurrent.atomic.AtomicReference<PlanetMesh?>(null)
    var pendingMesh: PlanetMesh?
        get() = pendingMeshRef.get()
        set(value) { pendingMeshRef.set(value) }

    /**
     * Dernier maillage RÉSIDENT, conservé après téléversement.
     *
     * Sans lui, une perte de contexte OpenGL (mise en veille, appel entrant)
     * laissait le globe noir DÉFINITIVEMENT : onSurfaceCreated remet
     * uploadedVertexCount à zéro, et plus personne ne détenait le maillage.
     * Le filet de onResume ne pouvait pas jouer, car drawGlobe() sort avant
     * la ligne qui met drawnTriangles à jour — la condition restait fausse
     * pour toujours. Conserver la référence est la seule parade robuste :
     * elle ne dépend d'aucun ordre d'appel du cycle de vie Android.
     */
    @Volatile private var residentMesh: PlanetMesh? = null

    // --- Ressources du chemin globe ---
    private var program = 0
    private var vbo = 0
    private var uploadedVertexCount = 0
    private var aPosition = -1
    private var aColor = -1
    private var aNormal = -1
    private var aMaterial = -1
    private var uMvp = -1
    private var uModel = -1
    private var uCamera = -1
    private var uSun = -1

    // --- Ressources du chemin descente ---
    private var tileProgram = 0

    // --- Couche d'eau (lot 2.9-a) ---
    private var waterProgram = 0
    private var wAPosition = -1
    private var wADepth = -1
    private var wAMorph = -1
    private var wUViewProj = -1
    private var wUOffset = -1
    private var wUCenterWorld = -1
    private var wUSun = -1
    private var wUHaze = -1
    private var wUHazeDensity = -1
    private var wUCenterRel = -1
    private var wUCosHorizon = -1
    private var wULimbBand = -1
    private var wULimbStrength = -1
    private var wURimStrength = -1
    private var wUWaveTime = -1
    private var wUWaveScale = -1
    private var wUCloudDrift = -1
    private var wUCloudShadow = -1
    private var wUTileCover = -1
    private var wUMorph = -1
    private var tAPosition = -1
    private var tAColor = -1
    private var tANormal = -1
    private var tAMaterial = -1
    private var tUViewProj = -1
    private var tUOffset = -1
    private var tUCenterWorld = -1
    private var tUSun = -1
    private var tUHaze = -1
    private var tUHazeDensity = -1
    private var tUCenterRel = -1
    private var tUCosHorizon = -1
    private var tULimbBand = -1
    private var tULimbStrength = -1
    private var tUCloudDrift = -1
    private var tUCloudShadow = -1
    private var tUTileCover = -1
    private var tURimStrength = -1
    private var tULevelTint = -1
    private var tUWaveTime = -1
    private var tUWaveScale = -1
    private var tAMorph = -1
    private var tUMorph = -1

    private var skyProgram = 0
    /**
     * Densité d'écran (pixels par unité d'écran), posée par l'activité.
     * Sert à dimensionner les points d'étoile : gl_PointSize est en pixels
     * physiques, qui ne veulent rien dire d'un appareil à l'autre.
     */
    @Volatile var pointScale = 1f

    private var starProgram = 0
    private var starVbo = 0
    private var moonProgram = 0
    private var moonVbo = 0
    private var moonVertexCount = 0
    /**
     * Champ d'étoiles du monde. CONSERVÉ après téléversement, comme le
     * maillage : la v0.29.4 le mettait à null, et le ciel restait vide après
     * toute reprise de contexte. Le drapeau dit s'il doit être (re)versé.
     */
    @Volatile var pendingStars: FloatArray? = null
        set(value) { field = value; starsUploaded = false }
    @Volatile private var starsUploaded = false
    private var cloudProgram = 0
    private var cloudVbo = 0
    private var cloudVertexCount = 0
    /** Origine de temps du rendu, pour les animations sans état (météo). */
    private val startNanos = System.nanoTime()
    private var weatherProgram = 0
    private var weatherVbo = 0
    /** État météo décidé par :sim, publié par la boucle UI. */
    @Volatile var weatherForm = 0        // 0 rien, 1 pluie, 2 neige
    @Volatile var weatherIntensity = 0f
    /**
     * Carte d'humidité du monde (256×128, un octet par cellule), publiée par
     * le fil de travail. CONSERVÉE après téléversement, comme le maillage et
     * les étoiles : une perte de contexte doit pouvoir la reverser.
     */
    @Volatile var pendingHumidity: ByteArray? = null
        set(value) { field = value; humidityUploaded = false }
    @Volatile private var humidityUploaded = false
    private var humidityTex = 0
    @Volatile var cloudDrift = 0f
    @Volatile var moonDirX = 1f
    @Volatile var moonDirY = 0f
    @Volatile var moonDirZ = 0f
    private var skyVbo = 0
    private var sAPos = -1
    private var sUTop = -1
    private var sUBottom = -1
    private var sUGround = -1
    private var sUFwd = -1
    private var sURight = -1
    private var sUUpCam = -1
    private var sUPlanetUp = -1
    private var sUSunDir = -1
    private var sUSunColor = -1
    private var sUSunGlow = -1
    private var sUHorizonSin = -1
    private var sUTanHalf = -1
    private var sUAspect = -1

    private val gpuPool = GpuBufferPool()
    private val stream = TileStream(gpuPool)
    private val selector = TileSelector()
    private val selection = ArrayList<TileId>(1024)
    private val drawList = ArrayList<TileStream.GpuTile>(1024)
    private val neededKeys = HashSet<Long>(2048)
    private var frameIndex = 0L
    private var waveTime = 0f
    private var currentEpoch = -1

    // --- Matrices et tampons de travail ---
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val temp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val eye = FloatArray(3)
    private var aspect = 1f

    private var lastFrameNanos = 0L
    private var fpsAccumulator = 0f
    private var fpsFrames = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Contexte possiblement recréé après une veille : tout identifiant GPU
        // détenu est caduc. On oublie sans appeler OpenGL — détruire des
        // identifiants de l'ancien contexte viserait ceux du nouveau.
        program = 0
        vbo = 0
        uploadedVertexCount = 0
        tileProgram = 0
        waterProgram = 0
        skyProgram = 0
        skyVbo = 0
        starProgram = 0; starVbo = 0; starsUploaded = false
        moonProgram = 0; moonVbo = 0
        cloudProgram = 0; cloudVbo = 0
        weatherProgram = 0; weatherVbo = 0
        humidityTex = 0; humidityUploaded = false
        gpuPool.forgetAll()
        stream.forgetGpu()
        tilePool.cancelAll()
        lastError = null

        glRenderer = try {
            (GLES20.glGetString(GLES20.GL_RENDERER) ?: "?")
        } catch (t: Throwable) {
            "?"
        }

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) {
            if (lastError == null) lastError = "Shaders indisponibles sur ce GPU"
            return
        }
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aMaterial = GLES20.glGetAttribLocation(program, "aMaterial")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uCamera = GLES20.glGetUniformLocation(program, "uCamera")
        uSun = GLES20.glGetUniformLocation(program, "uSun")

        tileProgram = buildProgram(TILE_VERTEX_SHADER, TILE_FRAGMENT_SHADER)
        if (tileProgram != 0) {
            tAPosition = GLES20.glGetAttribLocation(tileProgram, "aPosition")
            tAColor = GLES20.glGetAttribLocation(tileProgram, "aColor")
            tANormal = GLES20.glGetAttribLocation(tileProgram, "aNormal")
            tAMaterial = GLES20.glGetAttribLocation(tileProgram, "aMaterial")
            tUViewProj = GLES20.glGetUniformLocation(tileProgram, "uViewProj")
            tUOffset = GLES20.glGetUniformLocation(tileProgram, "uOffset")
            tUCenterWorld = GLES20.glGetUniformLocation(tileProgram, "uCenterWorld")
            tUSun = GLES20.glGetUniformLocation(tileProgram, "uSun")
            tUHaze = GLES20.glGetUniformLocation(tileProgram, "uHaze")
            tUHazeDensity = GLES20.glGetUniformLocation(tileProgram, "uHazeDensity")
            tUCenterRel = GLES20.glGetUniformLocation(tileProgram, "uCenterRel")
            tUCosHorizon = GLES20.glGetUniformLocation(tileProgram, "uCosHorizon")
            tULimbBand = GLES20.glGetUniformLocation(tileProgram, "uLimbBand")
            tULimbStrength = GLES20.glGetUniformLocation(tileProgram, "uLimbStrength")
            tUCloudDrift = GLES20.glGetUniformLocation(tileProgram, "uCloudDrift")
            tUCloudShadow = GLES20.glGetUniformLocation(tileProgram, "uCloudShadow")
            tUTileCover = GLES20.glGetUniformLocation(tileProgram, "uTileCover")
            tURimStrength = GLES20.glGetUniformLocation(tileProgram, "uRimStrength")
            tULevelTint = GLES20.glGetUniformLocation(tileProgram, "uLevelTint")
            tUWaveTime = GLES20.glGetUniformLocation(tileProgram, "uWaveTime")
            tUWaveScale = GLES20.glGetUniformLocation(tileProgram, "uWaveScale")
            tAMorph = GLES20.glGetAttribLocation(tileProgram, "aMorph")
            tUMorph = GLES20.glGetUniformLocation(tileProgram, "uMorph")
        }

        waterProgram = buildProgram(WATER_VERTEX_SHADER, WATER_FRAGMENT_SHADER)
        if (waterProgram != 0) {
            wAPosition = GLES20.glGetAttribLocation(waterProgram, "aPosition")
            wADepth = GLES20.glGetAttribLocation(waterProgram, "aDepth")
            wAMorph = GLES20.glGetAttribLocation(waterProgram, "aMorph")
            wUViewProj = GLES20.glGetUniformLocation(waterProgram, "uViewProj")
            wUOffset = GLES20.glGetUniformLocation(waterProgram, "uOffset")
            wUCenterWorld = GLES20.glGetUniformLocation(waterProgram, "uCenterWorld")
            wUSun = GLES20.glGetUniformLocation(waterProgram, "uSun")
            wUHaze = GLES20.glGetUniformLocation(waterProgram, "uHaze")
            wUHazeDensity = GLES20.glGetUniformLocation(waterProgram, "uHazeDensity")
            wUCenterRel = GLES20.glGetUniformLocation(waterProgram, "uCenterRel")
            wUCosHorizon = GLES20.glGetUniformLocation(waterProgram, "uCosHorizon")
            wULimbBand = GLES20.glGetUniformLocation(waterProgram, "uLimbBand")
            wULimbStrength = GLES20.glGetUniformLocation(waterProgram, "uLimbStrength")
            wURimStrength = GLES20.glGetUniformLocation(waterProgram, "uRimStrength")
            wUWaveTime = GLES20.glGetUniformLocation(waterProgram, "uWaveTime")
            wUWaveScale = GLES20.glGetUniformLocation(waterProgram, "uWaveScale")
            wUCloudDrift = GLES20.glGetUniformLocation(waterProgram, "uCloudDrift")
            wUCloudShadow = GLES20.glGetUniformLocation(waterProgram, "uCloudShadow")
            wUTileCover = GLES20.glGetUniformLocation(waterProgram, "uTileCover")
            wUMorph = GLES20.glGetUniformLocation(waterProgram, "uMorph")
        }

        skyProgram = buildProgram(SKY_VERTEX_SHADER, SKY_FRAGMENT_SHADER)
        if (skyProgram != 0) {
            sAPos = GLES20.glGetAttribLocation(skyProgram, "aPos")
            sUTop = GLES20.glGetUniformLocation(skyProgram, "uTop")
            sUBottom = GLES20.glGetUniformLocation(skyProgram, "uBottom")
            sUGround = GLES20.glGetUniformLocation(skyProgram, "uGround")
            sUFwd = GLES20.glGetUniformLocation(skyProgram, "uFwd")
            sURight = GLES20.glGetUniformLocation(skyProgram, "uRight")
            sUUpCam = GLES20.glGetUniformLocation(skyProgram, "uUpCam")
            sUPlanetUp = GLES20.glGetUniformLocation(skyProgram, "uPlanetUp")
            sUSunDir = GLES20.glGetUniformLocation(skyProgram, "uSunDir")
            sUSunColor = GLES20.glGetUniformLocation(skyProgram, "uSunColor")
            sUSunGlow = GLES20.glGetUniformLocation(skyProgram, "uSunGlow")
            sUHorizonSin = GLES20.glGetUniformLocation(skyProgram, "uHorizonSin")
            sUTanHalf = GLES20.glGetUniformLocation(skyProgram, "uTanHalf")
            sUAspect = GLES20.glGetUniformLocation(skyProgram, "uAspect")
            skyVbo = createSkyQuad()
        }

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glClearColor(0.004f, 0.006f, 0.016f, 1f)

        lastFrameNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height > 0) width.toFloat() / height else 1f
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f
                 else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.25f)
        lastFrameNanos = now
        frameIndex++

        // Phase de la houle, avancée ici parce que c'est ici que vit le temps
        // de trame. Elle suit le temps RÉEL et non le temps simulé : une mer
        // figée quand on met la simulation en pause donnerait une impression
        // de photographie, et à ×200 elle deviendrait un clignotement.
        // Période d'environ six secondes, celle d'une houle longue vue du
        // rivage.
        waveTime = (waveTime + dt * 1.05f) % 6.2831853f

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Échange atomique : ce qui arrive après ce point sera vu à l'image
        // suivante, jamais perdu.
        pendingMeshRef.getAndSet(null)?.let {
            upload(it)
            residentMesh = it
        }
        // Contexte recréé : le maillage résident n'est plus sur le GPU. On le
        // reverse sans rien recalculer — et sans dépendre de l'ordre entre
        // onResume et onSurfaceCreated.
        if (uploadedVertexCount == 0) {
            residentMesh?.let { upload(it) }
        }

        val snapshot = cameraSnapshot
        val ctx = tileContext
        if (descentMode && snapshot != null && ctx != null && tileProgram != 0) {
            drawDescent(snapshot, ctx)
        } else {
            drawGlobe()
        }

        frameMs = (System.nanoTime() - now) / 1_000_000f
        fpsAccumulator += dt
        fpsFrames++
        if (fpsAccumulator >= 0.5f) {
            fps = fpsFrames / fpsAccumulator
            fpsAccumulator = 0f
            fpsFrames = 0
        }
    }

    // ------------------------------------------------------------- descente

    private fun drawDescent(snapshot: CameraSnapshot, ctx: TileContext) {
        // Changement de monde : tout ce qui a été maillé décrit l'ancien.
        if (ctx.epoch != currentEpoch) {
            currentEpoch = ctx.epoch
            // L'ordre compte : fermer la porte aux retardataires avant de
            // vider, sinon un maillage de l'ancien monde déposé entre les deux
            // passerait.
            stream.acceptEpoch = ctx.epoch
            tilePool.cancelAll()
            stream.clear()
        }

        val radius = ctx.radiusM

        // --- Sélection des tuiles, en unités de sphère unité ---
        val camUnit = Vec3(
            (snapshot.eyeXM / radius).toFloat(),
            (snapshot.eyeYM / radius).toFloat(),
            (snapshot.eyeZM / radius).toFloat()
        )
        val forward = Vec3(snapshot.fwdX, snapshot.fwdY, snapshot.fwdZ)
        val cone = ViewCone.fromCamera(camUnit, forward, snapshot.fovRad, aspect)
        // Rayon du terrain sous la caméra, en unités de sphère unité : c'est
        // par rapport à LUI que se jugent les distances de subdivision.
        val eyeLenUnit = sqrt(
            snapshot.eyeXM * snapshot.eyeXM + snapshot.eyeYM * snapshot.eyeYM +
                    snapshot.eyeZM * snapshot.eyeZM
        ) / radius
        selector.groundRadiusUnit =
            (eyeLenUnit - snapshot.heightAboveGroundM / radius).coerceIn(0.9, 1.1).toFloat()
        selector.select(
            snapshot.eyeXM / radius, snapshot.eyeYM / radius, snapshot.eyeZM / radius,
            selection, cone
        )
        tilesSelected = selection.size
        var deepest = 0
        for (t in selection) if (t.level > deepest) deepest = t.level
        maxSelectedLevel = deepest
        heightAboveGroundM = snapshot.heightAboveGroundM

        // --- Filet de sécurité : les six racines, toujours ---
        //
        // Le repli sur l'ancêtre ne peut fonctionner que si la chaîne remonte
        // jusqu'à quelque chose. Six tuiles de niveau 0 couvrent la planète
        // entière pour 460 Ko : elles sont demandées d'office et jamais
        // évincées. Ainsi, aucune direction de l'écran ne peut plus tomber
        // sur du vide — au pire elle montre un terrain très grossier, ce qui
        // se voit infiniment moins qu'un trou de ciel sous l'horizon.
        for (face in 0 until 6) {
            val rootKey = TileId(face, 0, 0, 0).packed()
            if (stream.isCached(rootKey)) continue
            val root = TileId(face, 0, 0, 0)
            val profileRoot = ctx.profile
            val samplerRoot = ctx.sampler
            val epochRoot = ctx.epoch
            tilePool.submit(rootKey, 1000f) {
                stream.offer(TileMesh(root, profileRoot, samplerRoot, radius), epochRoot)
            }
        }

        // --- Demandes de maillage, priorisées par l'axe du regard ---
        for (tile in selection) {
            val key = tile.packed()
            if (stream.isCached(key)) continue
            val priority = priorityOf(tile, camUnit, forward)
            val profile = ctx.profile
            val sampler = ctx.sampler
            val epoch = ctx.epoch
            tilePool.submit(key, priority) {
                stream.offer(TileMesh(tile, profile, sampler, radius), epoch)
            }

            // Feuille manquante : son parent est demandé en priorité — il
            // couvre quatre feuilles pour le même coût de maillage, donc le
            // trou se comble quatre fois plus vite, et le repli en cascade
            // retrouve un échelon proche au lieu de remonter loin.
            val pKey = TileId.parentKey(key)
            if (pKey != -1L && !stream.isCached(pKey)) {
                val parent = TileId.unpack(pKey)
                tilePool.submit(pKey, priority + 0.5f) {
                    stream.offer(TileMesh(parent, profile, sampler, radius), epoch)
                }
            }
        }

        // De loin en loin, on annule ce qui n'est plus utile. Pas à chaque
        // image : la construction de l'ensemble et le verrou du pool ont un
        // coût, et une tuile devenue inutile ne le reste qu'un instant.
        if (frameIndex % 15L == 0L) {
            neededKeys.clear()
            for (tile in selection) neededKeys.add(tile.packed())
            tilePool.retainOnly(neededKeys)
        }

        stream.uploadPending(UPLOADS_PER_FRAME, frameIndex)
        tilesMissing = stream.resolveDrawSet(selection, drawList, frameIndex)
        tilesDrawn = drawList.size
        tilesCached = stream.cachedCount
        if (frameIndex % 30L == 0L) {
            // L'ordre compte : raviver les ancêtres de la sélection AVANT
            // l'éviction, sinon le filet du repli part avec l'eau du bain.
            stream.touchAncestors(selection, frameIndex)
            stream.touchRoots(frameIndex)
            stream.evictStale(frameIndex, KEEP_FRAMES)
            gpuPoolSummary = gpuPool.summary()
        }


        // --- Matrices : l'œil est à l'origine, le monde vient à lui ---
        val near = PlanetCamera.nearPlaneFor(snapshot.heightAboveGroundM, snapshot.rangeM).toFloat()
        nearPlaneM = near
        // Horizon et plan lointain : les formules vivent désormais dans :sim
        // (ScaleRegistry, lot 2.7-a), où elles sont TESTÉES — y compris leur
        // identité avec l'ancienne écriture en ligne d'ici. Ce fichier n'a
        // pas de filet : ne rien recalculer localement. L'horizon reste
        // nécessaire ici pour la densité de brume, plus bas.
        val horizonM = ScaleRegistry.slantHorizonM(snapshot.altitudeM, radius)
        val far = ScaleRegistry.farPlaneM(snapshot.altitudeM, radius).toFloat()
        Matrix.perspectiveM(projection, 0, Math.toDegrees(snapshot.fovRad.toDouble()).toFloat(), aspect, near, far)
        Matrix.setLookAtM(
            view, 0,
            0f, 0f, 0f,
            snapshot.fwdX, snapshot.fwdY, snapshot.fwdZ,
            snapshot.upX, snapshot.upY, snapshot.upZ
        )
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

        // Soleil dans le repère de la planète : le monde des tuiles ne tourne
        // pas (pas de matrice modèle), c'est donc le soleil qui est ramené du
        // repère monde par la rotation propre inverse.
        val spinRad = spinDeg * DEG
        val c = cos(spinRad)
        val s = sin(spinRad)
        val sunLx = sunX * c - sunZ * s
        val sunLz = sunX * s + sunZ * c

        // --- Ciel, avant le terrain, une fois l'éclairement connu ---
        val dayFEye = dayFactorAtEye(snapshot, sunLx, sunLz)
        drawSky(snapshot, radius, dayFEye)
        drawCelestial(mvp, c, s, snapshot, dayFEye, far)

        GLES20.glUseProgram(tileProgram)
        GLES20.glUniformMatrix4fv(tUViewProj, 1, false, mvp, 0)
        GLES20.glUniform3f(tUSun, sunLx, sunY, sunLz)

        // Brume : densité choisie pour ~40 % d'atténuation à l'horizon bas —
        // repère de profondeur à peu de frais, et elle voile la transition de
        // niveau de détail au loin en attendant le morphing du lot 2.4.
        // La brume est de l'AIR entre l'œil et le sol : au-dessus de
        // l'atmosphère, il n'y en a plus, et la loi en 0,5/horizon voilait
        // encore un tiers des tuiles lointaines à 400 km d'altitude — le
        // délavage laiteux constaté en v0.19.1. Extinction linéaire de
        // 20 km (pleine brume, l'échelle de hauteur de l'air est de 8 km)
        // à 120 km (plus rien, on rejoint la montée du halo de limbe qui
        // prend le relais entre 60 et 300 km).
        val atmosphere = ((120_000.0 - snapshot.altitudeM) / 100_000.0)
            .coerceIn(0.0, 1.0)
        val hazeDensity = (0.5 / max(20_000.0, horizonM) * atmosphere).toFloat()
        GLES20.glUniform1f(tUHazeDensity, hazeDensity)

        // --- Fondu de limbe (v0.24.0, correctif du limbe polygonal) ---
        //
        // Au-delà de ~1 500 km, la silhouette de la planète était dessinée
        // par les bords droits de tuiles de niveau 3-4 : le point ouvert des
        // captures v0.19.1. Les tuiles se dissolvent désormais dans le
        // disque analytique du ciel à l'approche de la silhouette — le même
        // canal vFog que la brume, les deux régimes s'excluant par
        // l'altitude (la brume meurt à 120 km, le fondu naît à 600).
        val eyeLen = kotlin.math.sqrt(
            snapshot.eyeXM * snapshot.eyeXM + snapshot.eyeYM * snapshot.eyeYM +
                snapshot.eyeZM * snapshot.eyeZM
        ).coerceAtLeast(1.0)
        // cos de l'angle nadir→horizon : sqrt(1 − (R/d)²).
        val cosHorizon = kotlin.math.sqrt(
            (1.0 - (radius / eyeLen) * (radius / eyeLen)).coerceIn(0.0, 1.0)
        ).toFloat()
        // Largeur du fondu : 15 % du rayon angulaire du disque — assez pour
        // avaler l'arête d'une tuile de niveau 3, trop peu pour manger le
        // terrain intérieur.
        val limbBand = ((1f - cosHorizon) * 0.15f).coerceAtLeast(1e-4f)
        val limbStrength = (((snapshot.altitudeM - 600_000.0) / 900_000.0)
            .coerceIn(0.0, 1.0)).toFloat()
        GLES20.glUniform3f(
            tUCenterRel,
            (-snapshot.eyeXM).toFloat(), (-snapshot.eyeYM).toFloat(),
            (-snapshot.eyeZM).toFloat()
        )
        GLES20.glUniform1f(tUCosHorizon, cosHorizon)
        GLES20.glUniform1f(tULimbBand, limbBand)
        GLES20.glUniform1f(tULimbStrength, limbStrength)

        // Ombres de nuages : altitude de la coquille en RAYONS planétaires,
        // l'unité dans laquelle le shader travaille (sph est unitaire). Au
        // sol comme en orbite, la même valeur : c'est une propriété de la
        // planète, pas du point de vue.
        GLES20.glUniform1f(tUCloudDrift, cloudDrift)
        GLES20.glUniform1f(
            tUCloudShadow,
            (CLOUD_ALTITUDE_M / max(1.0, radius)).toFloat()
        )

        // Cible du fondu : la couleur EXACTE du disque du ciel (formule de
        // drawSky), pour que les tuiles s'y dissolvent sans couture. La
        // brume de distance étant morte à ces altitudes, uHaze est libre.
        val dayF = dayFactorAtEye(snapshot, sunLx, sunLz)
        val presence = ((90_000.0 - snapshot.altitudeM) / 78_000.0).coerceIn(0.0, 1.0).toFloat()
        val groundFade = (0.25f + 0.75f * presence)
        val night = 1f - dayF
        val gR = (0.50f * dayF + 0.020f * night + 0.008f) * groundFade
        val gG = (0.58f * dayF + 0.024f * night + 0.010f) * groundFade
        val gB = (0.72f * dayF + 0.040f * night + 0.020f) * groundFade
        val limbOn = limbStrength
        // Valeurs posées dans des locaux : la couche d'eau (lot 2.9-a) doit
        // baigner dans EXACTEMENT le même air que le terrain, donc recevoir
        // les mêmes uniformes — pas une seconde évaluation des formules.
        val hazeR = (0.62f * dayF + 0.01f) * (1f - limbOn) + gR * limbOn
        val hazeG = (0.72f * dayF + 0.012f) * (1f - limbOn) + gG * limbOn
        val hazeB = (0.85f * dayF + 0.02f) * (1f - limbOn) + gB * limbOn
        GLES20.glUniform3f(tUHaze, hazeR, hazeG, hazeB)

        // Halo atmosphérique du limbe : plein en orbite, nul au sol — en vue
        // rasante, la direction de visée est presque tangente partout et le
        // halo voilerait toute la scène de bleu.
        val rimStrength = (((snapshot.altitudeM - 60_000.0) / 240_000.0).coerceIn(0.0, 1.0)).toFloat()
        GLES20.glUniform1f(tURimStrength, rimStrength)

        GLES20.glUniform1f(tUWaveTime, waveTime)

        var triangles = 0
        for (tile in drawList) {
            // Diagnostic : teinte par niveau de subdivision, pour voir d'un
            // coup d'œil où s'arrête la couverture proche. Cycle de six
            // teintes vives, le niveau se lit à la couleur.
            if (debugLevelTint) {
                val lvl = TileId.unpack(tile.key).level
                val h = (lvl % 6) / 6f
                GLES20.glUniform3f(
                    tULevelTint,
                    kotlin.math.abs(h * 6f - 3f) - 1f,
                    2f - kotlin.math.abs(h * 6f - 2f),
                    2f - kotlin.math.abs(h * 6f - 4f)
                )
            } else {
                GLES20.glUniform3f(tULevelTint, -1f, -1f, -1f)
            }
            GLES20.glUniform3f(
                tUOffset,
                (tile.centerXM - snapshot.eyeXM).toFloat(),
                (tile.centerYM - snapshot.eyeYM).toFloat(),
                (tile.centerZM - snapshot.eyeZM).toFloat()
            )
            GLES20.glUniform3f(
                tUCenterWorld,
                tile.centerXM.toFloat(), tile.centerYM.toFloat(), tile.centerZM.toFloat()
            )
            // Amplitude des vagues, atténuée sur les tuiles grossières : une
            // houle de 80 m échantillonnée tous les 150 m devient du bruit.
            // Nulle au-delà du niveau 16, pleine à partir du niveau 19.
            val lvl = TileId.unpack(tile.key).level
            GLES20.glUniform1f(
                tUWaveScale, ((lvl - 16) / 3f).coerceIn(0f, 1f)
            )

            // Humidité de la tuile, pour l'ombre des nuages qui la survolent.
            // Lue sur le PROCESSEUR au centre de la tuile : la cellule
            // d'humidité fait 155 km, une tuile au-delà du niveau 8 tient
            // dedans — l'approximation par tuile est excellente, et elle
            // évite d'échantillonner une texture dans le shader de sommet,
            // ce que GLES2 ne garantit pas.
            val cover = pendingHumidity?.let { map ->
                val len = kotlin.math.sqrt(
                    tile.centerXM * tile.centerXM + tile.centerYM * tile.centerYM +
                        tile.centerZM * tile.centerZM
                ).coerceAtLeast(1.0)
                com.terra.sim.HumidityMap.coverAt(
                    map,
                    com.terra.core.Vec3(
                        (tile.centerXM / len).toFloat(),
                        (tile.centerYM / len).toFloat(),
                        (tile.centerZM / len).toFloat()
                    )
                )
            } ?: 0.5f
            GLES20.glUniform1f(tUTileCover, cover)

            // --- Morphing entre niveaux (lot 2.4) ---
            //
            // Le sélecteur bascule au niveau supérieur quand la distance
            // descend sous 2·rayon/seuil. On interpole vers la géométrie
            // parente sur les trente derniers pour cent avant cette bascule :
            // à l'instant du changement, les deux maillages coïncident
            // exactement, et le ressaut disparaît. Plus tôt, la géométrie
            // fine est vraie ; c'est ce qui compte de près.
            val tileRadius = (Math.PI * 0.5 / (1 shl lvl)) * radius * 0.75
            val switchDist = 2.0 * tileRadius / 1.4
            val dxm = tile.centerXM - snapshot.eyeXM
            val dym = tile.centerYM - snapshot.eyeYM
            val dzm = tile.centerZM - snapshot.eyeZM
            val dist = sqrt(dxm * dxm + dym * dym + dzm * dzm)
            val morph = (((dist / switchDist) - MORPH_START) / (1.0 - MORPH_START))
                .coerceIn(0.0, 1.0).toFloat()
            GLES20.glUniform1f(tUMorph, morph)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tile.vbo)
            bindTileAttribute(tAPosition, 3, TileMesh.OFFSET_POSITION)
            bindTileAttribute(tAColor, 3, TileMesh.OFFSET_COLOR)
            bindTileAttribute(tANormal, 3, TileMesh.OFFSET_NORMAL)
            bindTileAttribute(tAMaterial, 1, TileMesh.OFFSET_MATERIAL)
            bindTileAttribute(tAMorph, 1, TileMesh.OFFSET_MORPH)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, tile.vertexCount)
            triangles += tile.vertexCount / 3
        }
        disableAttribute(tAPosition)
        disableAttribute(tAColor)
        disableAttribute(tANormal)
        disableAttribute(tAMaterial)
        disableAttribute(tAMorph)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // --- Couche d'eau (lot 2.9-a), après TOUT le terrain ---------------
        //
        // Opaque dans ce sous-lot : profondeur écrite, test actif — l'ordre
        // terrain → eau n'est ici qu'une préparation du mélange alpha du
        // 2.9-b, où il deviendra obligatoire. Les uniformes d'atmosphère
        // reprennent LES MÊMES valeurs locales que le terrain : la mer
        // baigne dans le même air, sinon elle se découperait au loin.
        if (waterProgram != 0) {
            // Lot 2.9-b : la couche devient translucide. Profondeur TESTEE
            // mais non ECRITE — une tuile d'eau ne doit pas masquer sa
            // voisine, et l'eau glissee sous les terres (emission par tuile,
            // v0.34.2) reste eliminee par le terrain, deja ecrit. Source
            // premultipliee : ONE / ONE_MINUS_SRC_ALPHA.
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            GLES20.glUseProgram(waterProgram)
            GLES20.glUniformMatrix4fv(wUViewProj, 1, false, mvp, 0)
            GLES20.glUniform3f(wUSun, sunLx, sunY, sunLz)
            GLES20.glUniform3f(wUHaze, hazeR, hazeG, hazeB)
            GLES20.glUniform1f(wUHazeDensity, hazeDensity)
            GLES20.glUniform3f(
                wUCenterRel,
                (-snapshot.eyeXM).toFloat(), (-snapshot.eyeYM).toFloat(),
                (-snapshot.eyeZM).toFloat()
            )
            GLES20.glUniform1f(wUCosHorizon, cosHorizon)
            GLES20.glUniform1f(wULimbBand, limbBand)
            GLES20.glUniform1f(wULimbStrength, limbStrength)
            GLES20.glUniform1f(wURimStrength, rimStrength)
            GLES20.glUniform1f(wUWaveTime, waveTime)
            GLES20.glUniform1f(wUCloudDrift, cloudDrift)
            GLES20.glUniform1f(
                wUCloudShadow,
                (CLOUD_ALTITUDE_M / max(1.0, radius)).toFloat()
            )
            for (tile in drawList) {
                if (tile.waterVertexCount == 0) continue
                GLES20.glUniform3f(
                    wUOffset,
                    (tile.centerXM - snapshot.eyeXM).toFloat(),
                    (tile.centerYM - snapshot.eyeYM).toFloat(),
                    (tile.centerZM - snapshot.eyeZM).toFloat()
                )
                GLES20.glUniform3f(
                    wUCenterWorld,
                    tile.centerXM.toFloat(), tile.centerYM.toFloat(), tile.centerZM.toFloat()
                )
                // Amplitude de houle, humidité et morphing : formules
                // reprises TELLES QUELLES de la boucle du terrain — la houle
                // et la bascule de niveau de l'eau doivent être synchrones
                // avec la tuile qu'elle recouvre.
                val lvl = TileId.unpack(tile.key).level
                GLES20.glUniform1f(
                    wUWaveScale, ((lvl - 16) / 3f).coerceIn(0f, 1f)
                )
                GLES20.glUniform1f(wUTileCover, tileCover(tile))
                val tileRadius = (Math.PI * 0.5 / (1 shl lvl)) * radius * 0.75
                val switchDist = 2.0 * tileRadius / 1.4
                val dxm = tile.centerXM - snapshot.eyeXM
                val dym = tile.centerYM - snapshot.eyeYM
                val dzm = tile.centerZM - snapshot.eyeZM
                val dist = sqrt(dxm * dxm + dym * dym + dzm * dzm)
                val morph = (((dist / switchDist) - MORPH_START) / (1.0 - MORPH_START))
                    .coerceIn(0.0, 1.0).toFloat()
                GLES20.glUniform1f(wUMorph, morph)

                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tile.vbo)
                bindWaterAttribute(wAPosition, 3, tile.waterOffsetBytes, TileMesh.WATER_OFFSET_POSITION)
                bindWaterAttribute(wADepth, 1, tile.waterOffsetBytes, TileMesh.WATER_OFFSET_DEPTH)
                bindWaterAttribute(wAMorph, 1, tile.waterOffsetBytes, TileMesh.WATER_OFFSET_MORPH)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, tile.waterVertexCount)
                triangles += tile.waterVertexCount / 3
            }
            disableAttribute(wAPosition)
            disableAttribute(wADepth)
            disableAttribute(wAMorph)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            // Etat rendu tel qu'il etait : la passe des nuages qui suit pose
            // son propre melange, mais l'ecriture de profondeur, elle, est
            // attendue active par tout ce qui vient ensuite.
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        drawnTriangles = triangles

        // --- Nuages, APRÈS le terrain : ils se mélangent par-dessus. ---
        drawClouds(
            mvp,
            (-snapshot.eyeXM).toFloat(), (-snapshot.eyeYM).toFloat(),
            (-snapshot.eyeZM).toFloat(),
            (radius + CLOUD_ALTITUDE_M).toFloat(),
            sunLx, sunY, sunLz, dayFEye,
            inside = snapshot.altitudeM < CLOUD_ALTITUDE_M
        )

        // --- Météo locale, en dernier : des particules devant tout. ---
        drawWeather(snapshot, dayFEye)
    }

    /**
     * Météo locale — lot 2.15. Une colonne de particules autour de la
     * caméra, en coordonnées CAMÉRA pures : la pluie accompagne l'œil,
     * comme dans la réalité où l'averse est partout autour de soi.
     *
     * Aucun état persistant : la position de chaque particule se déduit du
     * temps et de son indice, par un repli modulo sur la hauteur de la
     * colonne. Le déterminisme est structurel et il n'y a rien à mettre à
     * jour entre deux images.
     *
     * Ne s'affiche qu'au SOL (sous 3 km) : au-delà, on est au-dessus de
     * l'averse, et les nuages font le travail.
     */
    private fun drawWeather(snapshot: CameraSnapshot, dayF: Float) {
        if (weatherForm == 0 || weatherIntensity <= 0.01f) return
        if (snapshot.heightAboveGroundM > 3_000.0) return
        if (weatherProgram == 0) {
            weatherProgram = buildProgram(WEATHER_VERTEX, WEATHER_FRAGMENT)
            // Indices des particules : le shader en déduit tout.
            val data = FloatArray(WEATHER_BUDGET) { it.toFloat() }
            val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0)
            weatherVbo = ids[0]
            val buf = java.nio.ByteBuffer.allocateDirect(data.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buf.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, weatherVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf,
                GLES20.GL_STATIC_DRAW)
        }
        if (weatherProgram == 0) return

        val snow = weatherForm == 2
        val count = (WEATHER_BUDGET * weatherIntensity *
            (if (snow) 0.55f else 1f)).toInt().coerceIn(0, WEATHER_BUDGET)
        if (count == 0) return
        val t = ((System.nanoTime() - startNanos) / 1_000_000_000.0).toFloat()

        // Projection SEULE : les particules vivent en repère caméra, sans
        // la vue — elles suivent l'œil et ne tournent pas avec le monde.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(weatherProgram)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(weatherProgram, "uProj"), 1, false, projection, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(weatherProgram, "uTime"), t)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(weatherProgram, "uFall"),
            if (snow) 1.1f else 9f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(weatherProgram, "uSnow"),
            if (snow) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(weatherProgram, "uDayF"), dayF)
        val aIndex = GLES20.glGetAttribLocation(weatherProgram, "aIndex")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, weatherVbo)
        GLES20.glEnableVertexAttribArray(aIndex)
        GLES20.glVertexAttribPointer(aIndex, 1, GLES20.GL_FLOAT, false, 4, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(aIndex)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * Priorité d'une tuile : proche de l'axe du regard d'abord, proche de la
     * caméra ensuite. La tuile fixée par l'utilisateur doit se mailler avant
     * celle qui affleure à l'horizon.
     */
    private fun priorityOf(tile: TileId, camUnit: Vec3, forward: Vec3): Float {
        // tile.center alloue — toléré ici : cette fonction ne tourne que pour
        // les tuiles manquantes, quelques dizaines par image pendant un
        // déplacement, zéro à l'arrêt. Rien à voir avec le sélecteur, qui
        // visite des milliers de noeuds à chaque image.
        val center = tile.center
        val dx = center.x - camUnit.x
        val dy = center.y - camUnit.y
        val dz = center.z - camUnit.z
        val dist = max(1e-6f, sqrt(dx * dx + dy * dy + dz * dz))
        val along = (dx * forward.x + dy * forward.y + dz * forward.z) / dist
        return along - dist * 0.5f
    }

    private fun dayFactorAtEye(snapshot: CameraSnapshot, sunLx: Float, sunLz: Float): Float {
        val len = sqrt(
            snapshot.eyeXM * snapshot.eyeXM + snapshot.eyeYM * snapshot.eyeYM + snapshot.eyeZM * snapshot.eyeZM
        )
        if (len < 1.0) return 0f
        val dot = (snapshot.eyeXM / len) * sunLx + (snapshot.eyeYM / len) * sunY +
                (snapshot.eyeZM / len) * sunLz
        return (dot.toFloat() * 2.2f + 0.22f).coerceIn(0f, 1f)
    }

    /**
     * Ciel calé sur l'horizon géométrique réel.
     *
     * ## Pourquoi ce n'est plus un simple dégradé d'écran
     *
     * La première version peignait un dégradé vertical plaqué sur l'écran :
     * plein cadre, il était indiscernable d'un écran vide — l'utilisateur ne
     * pouvait littéralement pas dire s'il regardait le ciel ou un bug. Le
     * ciel reconstruit désormais la direction de visée par pixel et compare
     * son élévation à celle de l'horizon, abaissé sous l'horizontale de
     * l'angle exact dû à l'altitude (5° à 27 km, presque rien au sol).
     *
     * Sous l'horizon, couleur de brume : c'est aussi le fond de secours des
     * tuiles pas encore maillées — un manque se voit en brume cohérente, plus
     * jamais en trou noir. La vraie diffusion atmosphérique reste au lot 2.10.
     */
    /**
     * Étoiles et lune — lot 2.12. Dessinées ENTRE le ciel et le terrain :
     * le fond bleu-nuit est déjà posé, le relief recouvrira. Le champ est
     * fixe dans le repère monde ; comme le soleil, il est ramené dans le
     * repère local par la rotation propre inverse — le ciel défile parce
     * que la planète tourne, pas l'inverse.
     *
     * Étoiles en points additifs (visibles quand le ciel s'éteint : nuit,
     * ou espace où l'atmosphère disparaît), lune en vraie sphère éclairée
     * par le soleil — les phases sortent de la géométrie, gratuitement.
     */
    private fun drawCelestial(
        mvp: FloatArray, spinC: Float, spinS: Float,
        snapshot: CameraSnapshot, dayF: Float, farPlane: Float
    ) {
        // Reversé si jamais téléversé, ou si le contexte a été recréé.
        if (!starsUploaded || starVbo == 0) pendingStars?.let {
            if (starVbo == 0) {
                val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0)
                starVbo = ids[0]
            }
            val buf = java.nio.ByteBuffer.allocateDirect(it.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(it)
            buf.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, starVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, it.size * 4, buf,
                GLES20.GL_STATIC_DRAW)
            starsUploaded = true
        }
        if (starProgram == 0) {
            starProgram = buildProgram(STAR_VERTEX, STAR_FRAGMENT)
            moonProgram = buildProgram(MOON_VERTEX, MOON_FRAGMENT)
        }
        if (moonVbo == 0) {
            val sphere = Icosphere(2)
            val data = FloatArray(sphere.faceCount * 9)
            var o = 0
            for (f in 0 until sphere.faceCount) {
                for (k in 0..2) {
                    val v = sphere.vertices[sphere.faces[f * 3 + k]]
                    data[o] = v.x; data[o + 1] = v.y; data[o + 2] = v.z
                    o += 3
                }
            }
            moonVertexCount = sphere.faceCount * 3
            val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0)
            moonVbo = ids[0]
            val buf = java.nio.ByteBuffer.allocateDirect(data.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buf.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, moonVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf,
                GLES20.GL_STATIC_DRAW)
        }
        if (starVbo == 0 || starProgram == 0) return

        // Visibilité : le ciel éteint révèle les étoiles — nuit au sol,
        // ou altitude où l'atmosphère n'existe plus (même rampe que le ciel).
        val presence = ((90_000.0 - snapshot.altitudeM) / 78_000.0)
            .coerceIn(0.0, 1.0).toFloat()
        val starAlpha = (1f - dayF * presence).coerceIn(0f, 1f)
        val dist = farPlane * 0.90f

        GLES20.glDepthMask(false)
        if (starAlpha > 0.01f) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            GLES20.glUseProgram(starProgram)
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(starProgram, "uMvp"), 1, false, mvp, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(starProgram, "uDist"), dist)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(starProgram, "uAlpha"), starAlpha)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(starProgram, "uSpin"), spinC, spinS)
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(starProgram, "uPointScale"), pointScale
            )
            val aStar = GLES20.glGetAttribLocation(starProgram, "aStar")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, starVbo)
            GLES20.glEnableVertexAttribArray(aStar)
            GLES20.glVertexAttribPointer(aStar, 4, GLES20.GL_FLOAT, false, 16, 0)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, CelestialSky.STAR_COUNT)
            GLES20.glDisableVertexAttribArray(aStar)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // Lune : locale = rotation propre inverse, comme le soleil.
        val mlx = moonDirX * spinC - moonDirZ * spinS
        val mlz = moonDirX * spinS + moonDirZ * spinC
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(moonProgram)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(moonProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(moonProgram, "uCenter"),
            mlx * dist, moonDirY * dist, mlz * dist)
        // Rayon angulaire ~0,55° : 0,0096 rad × distance.
        GLES20.glUniform1f(GLES20.glGetUniformLocation(moonProgram, "uRadius"),
            dist * 0.0096f)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(moonProgram, "uSunL"),
            sunX * spinC - sunZ * spinS, sunY, sunX * spinS + sunZ * spinC)
        val aPos = GLES20.glGetAttribLocation(moonProgram, "aPos")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, moonVbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, moonVertexCount)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
    }

    /**
     * Téléverse la carte d'humidité — première et seule texture du moteur.
     *
     * Format GL_LUMINANCE en octets : un seul canal, 32 Ko, disponible
     * partout en OpenGL ES 2 sans extension. Dimensions en puissances de
     * deux, condition du filtrage linéaire et de la répétition sur GLES2.
     *
     * Répétition en X (la longitude boucle) et bornage en Y (aux pôles il
     * n'y a rien au-delà) : un GL_REPEAT vertical replierait l'Arctique sur
     * l'Antarctique.
     */
    private fun ensureHumidityTexture() {
        if (humidityUploaded && humidityTex != 0) return
        val data = pendingHumidity ?: return
        if (humidityTex == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            humidityTex = ids[0]
        }
        val buf = java.nio.ByteBuffer.allocateDirect(data.size).put(data)
        buf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, humidityTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            com.terra.sim.HumidityMap.WIDTH, com.terra.sim.HumidityMap.HEIGHT, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        humidityUploaded = true
    }

    private fun drawSky(snapshot: CameraSnapshot, radiusM: Double, dayF: Float) {
        if (skyProgram == 0 || skyVbo == 0) return

        // Le ciel bleu s'éteint avec l'altitude ; le fond de brume, lui,
        // subsiste atténué — en orbite, il dessine le disque planétaire
        // derrière les tuiles manquantes.
        val presence = ((90_000.0 - snapshot.altitudeM) / 78_000.0).coerceIn(0.0, 1.0).toFloat()

        val eyeLen = kotlin.math.sqrt(
            snapshot.eyeXM * snapshot.eyeXM + snapshot.eyeYM * snapshot.eyeYM +
                    snapshot.eyeZM * snapshot.eyeZM
        ).coerceAtLeast(1.0)
        val horizonSin = kotlin.math.sqrt(
            (1.0 - (radiusM / eyeLen) * (radiusM / eyeLen)).coerceIn(0.0, 1.0)
        ).toFloat()

        // Repère caméra : la droite se déduit des deux vecteurs de l'instantané.
        val rx = snapshot.fwdY * snapshot.upZ - snapshot.fwdZ * snapshot.upY
        val ry = snapshot.fwdZ * snapshot.upX - snapshot.fwdX * snapshot.upZ
        val rz = snapshot.fwdX * snapshot.upY - snapshot.fwdY * snapshot.upX

        val night = 1f - dayF

        // --- Crépuscule (lot 2.10) ---
        //
        // Même mesure que le terrain, pour que les deux virent ENSEMBLE : un
        // sol orangé sous un ciel resté bleu jurerait. L'élévation du soleil
        // sur la verticale du lieu ; la fenêtre est étroite et la courbe
        // cubique la resserre encore.
        //
        // Le BAS du ciel rougit fortement — c'est là que le regard traverse
        // le plus d'atmosphère — et le haut à peine : c'est ce dégradé, pas
        // une teinte uniforme, qui fait un couchant.
        val eyeUp = kotlin.math.sqrt(
            snapshot.eyeXM * snapshot.eyeXM + snapshot.eyeYM * snapshot.eyeYM +
                snapshot.eyeZM * snapshot.eyeZM
        ).coerceAtLeast(1.0)
        val sunElev = ((snapshot.eyeXM / eyeUp) * sunX +
            (snapshot.eyeYM / eyeUp) * sunY +
            (snapshot.eyeZM / eyeUp) * sunZ).toFloat()
        val d0 = (1f - kotlin.math.abs(sunElev) * 6f).coerceIn(0f, 1f)
        val dusk = d0 * d0 * d0

        val topR = (0.10f + 0.34f * dusk) * dayF * presence + 0.004f
        val topG = (0.28f - 0.06f * dusk) * dayF * presence + 0.008f
        val topB = (0.62f - 0.20f * dusk) * dayF * presence + 0.022f
        val botR = ((0.55f + 0.62f * dusk) * dayF + 0.016f) * presence + 0.004f
        val botG = ((0.66f - 0.18f * dusk) * dayF + 0.022f) * presence + 0.006f
        val botB = ((0.82f - 0.52f * dusk) * dayF + 0.045f) * presence + 0.016f
        val groundFade = (0.25f + 0.75f * presence)
        var gR = (0.50f * dayF + 0.020f * night + 0.008f) * groundFade
        var gG = (0.58f * dayF + 0.024f * night + 0.010f) * groundFade
        var gB = (0.72f * dayF + 0.040f * night + 0.020f) * groundFade

        // --- DIAGNOSTIC v0.10.2, à retirer une fois le défaut identifié ---
        //
        // Depuis quatre versions, je suppose que l'aplat clair vu sous
        // l'horizon en visée rasante est ce fond de brume, sans l'avoir
        // jamais vérifié. S'il s'agissait d'eau ou de terrain mal coloré,
        // toutes les pistes suivies seraient fausses. Le magenta n'existe
        // nulle part ailleurs dans le rendu : une seule capture tranchera.
        @Suppress("ConstantConditionIf")
        if (DIAGNOSTIC_SKY_GROUND) {
            gR = 1f; gG = 0f; gB = 1f
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(skyProgram)
        GLES20.glUniform3f(sUTop, topR, topG, topB)
        GLES20.glUniform3f(sUBottom, botR, botG, botB)
        GLES20.glUniform3f(sUGround, gR, gG, gB)
        GLES20.glUniform3f(sUFwd, snapshot.fwdX, snapshot.fwdY, snapshot.fwdZ)
        GLES20.glUniform3f(sURight, rx, ry, rz)
        GLES20.glUniform3f(sUUpCam, snapshot.upX, snapshot.upY, snapshot.upZ)
        GLES20.glUniform3f(
            sUPlanetUp,
            (snapshot.eyeXM / eyeLen).toFloat(),
            (snapshot.eyeYM / eyeLen).toFloat(),
            (snapshot.eyeZM / eyeLen).toFloat()
        )
        // Direction du soleil dans le repere du ciel — le MEME que celui de
        // sunElev ci-dessus (repere monde, non tourne), surtout pas le
        // repere local des tuiles : le disque se poserait ailleurs que la
        // lumiere qui eclaire le sol.
        val sunLen = kotlin.math.sqrt(sunX * sunX + sunY * sunY + sunZ * sunZ)
            .coerceAtLeast(1e-6f)
        GLES20.glUniform3f(sUSunDir, sunX / sunLen, sunY / sunLen, sunZ / sunLen)
        // Couleur du disque : blanche haut dans le ciel, orangee au ras de
        // l'horizon — meme mesure de crepuscule que le sol et les tuiles,
        // pour que tout vire ENSEMBLE.
        GLES20.glUniform3f(
            sUSunColor,
            1.0f, 0.97f - 0.30f * dusk, 0.92f - 0.62f * dusk
        )
        // Le halo est atmospherique : il s'eteint avec l'air (presence) et
        // se renforce au couchant, quand les rayons traversent le plus
        // d'atmosphere. Le disque, lui, reste visible depuis l'orbite.
        GLES20.glUniform1f(sUSunGlow, presence * dayF * (0.30f + 0.70f * dusk))
        GLES20.glUniform1f(sUHorizonSin, horizonSin)
        GLES20.glUniform1f(sUTanHalf, kotlin.math.tan(snapshot.fovRad * 0.5f))
        GLES20.glUniform1f(sUAspect, aspect)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyVbo)
        GLES20.glEnableVertexAttribArray(sAPos)
        GLES20.glVertexAttribPointer(sAPos, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(sAPos)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun createSkyQuad(): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        if (ids[0] == 0) return 0
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(quad).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    /**
     * Humidité moyenne au centre d'une tuile, pour l'ombre des nuages —
     * même échantillonnage CPU que la boucle du terrain (une cellule
     * d'humidité fait 155 km, l'approximation par tuile est excellente).
     */
    private fun tileCover(tile: TileStream.GpuTile): Float {
        val map = pendingHumidity ?: return 0.5f
        val len = kotlin.math.sqrt(
            tile.centerXM * tile.centerXM + tile.centerYM * tile.centerYM +
                tile.centerZM * tile.centerZM
        ).coerceAtLeast(1.0)
        return com.terra.sim.HumidityMap.coverAt(
            map,
            com.terra.core.Vec3(
                (tile.centerXM / len).toFloat(),
                (tile.centerYM / len).toFloat(),
                (tile.centerZM / len).toFloat()
            )
        )
    }

    /** Attribut de la couche d'eau : stride 5 flottants, base = offset du VBO. */
    private fun bindWaterAttribute(location: Int, size: Int, baseBytes: Int, offsetFloats: Int) {
        if (location < 0) return
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(
            location, size, GLES20.GL_FLOAT, false,
            TileMesh.WATER_STRIDE_BYTES, baseBytes + offsetFloats * 4
        )
    }

    private fun bindTileAttribute(location: Int, size: Int, offsetFloats: Int) {
        if (location < 0) return
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(
            location, size, GLES20.GL_FLOAT, false,
            TileMesh.STRIDE_BYTES, offsetFloats * 4
        )
    }

    // ---------------------------------------------------------------- globe

    private fun drawGlobe() {
        if (program == 0 || uploadedVertexCount == 0) return

        Matrix.perspectiveM(projection, 0, 42f, aspect, 0.02f, 60f)

        // Caméra en coordonnées sphériques autour de l'origine.
        val yawRad = yawDeg * DEG
        val pitchRad = pitchDeg * DEG
        val horizontal = cos(pitchRad) * distance
        eye[0] = horizontal * sin(yawRad)
        eye[1] = sin(pitchRad) * distance
        eye[2] = horizontal * cos(yawRad)

        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, 0f, 0f, 0f, 1f, 0f)

        // La matrice modèle ne porte que la rotation propre de la planète.
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, spinDeg, 0f, 1f, 0f)

        Matrix.multiplyMM(temp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)

        // Nuages du globe : dessinés APRÈS la planète (voir fin de fonction),
        // la matrice mvp porte déjà la rotation propre — le soleil est donc
        // contre-tourné comme pour les tuiles.
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3f(uCamera, eye[0], eye[1], eye[2])
        GLES20.glUniform3f(uSun, sunX, sunY, sunZ)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        bindAttribute(aPosition, 3, PlanetMesh.OFFSET_POSITION)
        bindAttribute(aColor, 3, PlanetMesh.OFFSET_COLOR)
        bindAttribute(aNormal, 3, PlanetMesh.OFFSET_NORMAL)
        bindAttribute(aMaterial, 1, PlanetMesh.OFFSET_MATERIAL)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, uploadedVertexCount)

        disableAttribute(aPosition)
        disableAttribute(aColor)
        disableAttribute(aNormal)
        disableAttribute(aMaterial)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        drawnTriangles = uploadedVertexCount / 3

        // Nuages contemplatifs : coquille au rayon relatif (1 + 9 km/R),
        // centre à l'origine, soleil contre-tourné de la rotation propre.
        val spinRad = spinDeg * DEG
        val sc = cos(spinRad); val ss = sin(spinRad)
        drawClouds(
            mvp, 0f, 0f, 0f,
            1f + (CLOUD_ALTITUDE_M / 6_371_000.0).toFloat(),
            sunX * sc - sunZ * ss, sunY, sunX * ss + sunZ * sc,
            1f, inside = false
        )
    }

    /**
     * Couche nuageuse — lot 2.14. Une coquille sphérique à ~9 km, dont
     * l'alpha est un bruit de valeur calculé dans le fragment : pas de
     * texture dans ce moteur, le bruit est la texture. Deux octaves au
     * fragment (le budget du Mali : chaque octave coûte huit sinus par
     * pixel) posées sur une octave large au sommet.
     *
     * Vue de DEHORS on regarde la face externe, de DESSOUS la face
     * interne : l'élagage bascule avec l'altitude, sinon l'une des deux
     * vues serait vide — et de l'intérieur, la face lointaine, au-delà de
     * la planète, est éliminée par le test de profondeur contre le terrain
     * déjà dessiné.
     */
    private fun drawClouds(
        mvpM: FloatArray,
        centerRelX: Float, centerRelY: Float, centerRelZ: Float,
        shellRadius: Float,
        sunLx: Float, sunLy: Float, sunLz: Float,
        dayF: Float,
        inside: Boolean
    ) {
        if (cloudProgram == 0) cloudProgram = buildProgram(CLOUD_VERTEX, CLOUD_FRAGMENT)
        if (cloudVbo == 0) {
            val sphere = Icosphere(4)
            val data = FloatArray(sphere.faceCount * 9)
            var o = 0
            for (f in 0 until sphere.faceCount) {
                for (k in 0..2) {
                    val v = sphere.vertices[sphere.faces[f * 3 + k]]
                    data[o] = v.x; data[o + 1] = v.y; data[o + 2] = v.z
                    o += 3
                }
            }
            cloudVertexCount = sphere.faceCount * 3
            val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0)
            cloudVbo = ids[0]
            val buf = java.nio.ByteBuffer.allocateDirect(data.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buf.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cloudVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf,
                GLES20.GL_STATIC_DRAW)
        }
        if (cloudProgram == 0) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glCullFace(if (inside) GLES20.GL_FRONT else GLES20.GL_BACK)

        ensureHumidityTexture()
        GLES20.glUseProgram(cloudProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, humidityTex)
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(cloudProgram, "uHumidity"), 0)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(cloudProgram, "uMvp"), 1, false, mvpM, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(cloudProgram, "uCenterRel"),
            centerRelX, centerRelY, centerRelZ)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(cloudProgram, "uShellR"), shellRadius)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(cloudProgram, "uSunL"),
            sunLx, sunLy, sunLz)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(cloudProgram, "uDayF"), dayF)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(cloudProgram, "uDrift"), cloudDrift)
        val aDir = GLES20.glGetAttribLocation(cloudProgram, "aDir")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cloudVbo)
        GLES20.glEnableVertexAttribArray(aDir)
        GLES20.glVertexAttribPointer(aDir, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, cloudVertexCount)
        GLES20.glDisableVertexAttribArray(aDir)

        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun bindAttribute(location: Int, size: Int, offsetFloats: Int) {
        if (location < 0) return
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(
            location, size, GLES20.GL_FLOAT, false,
            PlanetMesh.STRIDE_BYTES, offsetFloats * 4
        )
    }

    private fun disableAttribute(location: Int) {
        if (location >= 0) GLES20.glDisableVertexAttribArray(location)
    }

    private fun upload(mesh: PlanetMesh) {
        try {
            if (vbo != 0) {
                GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
                vbo = 0
            }
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            vbo = ids[0]

            val buffer: FloatBuffer = ByteBuffer
                .allocateDirect(mesh.sizeBytes)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buffer.put(mesh.vertexData)
            buffer.position(0)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER, mesh.sizeBytes, buffer, GLES20.GL_STATIC_DRAW
            )
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            uploadedVertexCount = mesh.vertexCount
        } catch (t: Throwable) {
            lastError = "Téléversement du maillage échoué : ${t.javaClass.simpleName}"
            Log.e(TAG, "Téléversement échoué", t)
        }
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vs == 0 || fs == 0) return 0

        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)

        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p) ?: ""
            Log.e(TAG, "Édition de liens échouée : $log")
            lastError = "Édition de liens : ${log.take(110)}"
            GLES20.glDeleteProgram(p)
            return 0
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader) ?: ""
            Log.e(TAG, "Compilation de shader échouée : $log")
            lastError = "Shader : ${log.take(110)}"
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        /** Budget de particules de météo : plafond du tampon d'indices. */
        const val WEATHER_BUDGET = 1400

        private const val WEATHER_VERTEX = """
            attribute float aIndex;
            uniform mat4 uProj;
            uniform highp float uTime;
            uniform float uFall;
            // mediump EXPLICITE des deux cotes : un drapeau 0/1 n'a aucun
            // besoin de precision, et le defaut du sommet (highp) contre
            // celui du fragment (mediump) faisait echouer l'edition de
            // liens sur Mali — meme piege que uDrift en v0.26.1, que je
            // n'avais corrige que pour uDrift au lieu d'auditer TOUS les
            // uniformes partages. L'audit exhaustif est fait : c'etait le
            // dernier.
            uniform mediump float uSnow;
            varying float vAlpha;
            // Dispersion pseudo-aleatoire deterministe a partir de l'indice.
            float h(float n) { return fract(sin(n * 12.9898) * 43758.5453); }
            void main() {
                float i = aIndex;
                float bx = (h(i) - 0.5) * 22.0;
                float bz = (h(i + 37.0) - 0.5) * 22.0 - 4.0;
                float span = 14.0;
                // Chute : repli modulo, aucun etat a conserver entre images.
                float phase = h(i + 91.0) * span;
                float y = span - mod(uTime * uFall + phase, span);
                // Derive laterale des flocons : la neige tourbillonne.
                float sway = uSnow * (sin(uTime * 0.9 + i) * 0.7);
                vec4 pos = vec4(bx + sway, y - 6.0, bz, 1.0);
                gl_Position = uProj * pos;
                float dist = length(pos.xyz);
                gl_PointSize = mix(2.5, 5.0, uSnow) * (14.0 / max(3.0, dist));
                // Les particules proches sont franches, les lointaines pales.
                vAlpha = clamp(1.4 - dist / 16.0, 0.0, 1.0);
            }
        """

        private const val WEATHER_FRAGMENT = """
            precision mediump float;
            uniform float uSnow;
            uniform float uDayF;
            varying float vAlpha;
            void main() {
                vec2 pc = gl_PointCoord * 2.0 - 1.0;
                float r = dot(pc, pc);
                if (r > 1.0) discard;
                float soft = 1.0 - r;
                // Pluie : trainee bleutee translucide. Neige : flocon blanc.
                vec3 col = mix(vec3(0.62, 0.70, 0.85), vec3(0.97, 0.98, 1.0), uSnow);
                float a = vAlpha * soft * mix(0.42, 0.85, uSnow) * (0.35 + 0.65 * uDayF);
                gl_FragColor = vec4(col * (0.30 + 0.70 * uDayF), a);
            }
        """


        /**
         * Champ de nuages en GLSL, PARTAGÉ entre le shader de la coquille et
         * celui du terrain (lot 2.14 b).
         *
         * L'ombre doit tomber exactement sous le nuage qui la porte : la
         * seule façon d'en être sûr est que les deux étages évaluent la même
         * fonction, au bit près. Dupliquer la formule dans deux shaders
         * l'aurait garantie le jour de l'écriture et jamais ensuite — un
         * réglage d'un côté aurait décalé les ombres sans prévenir.
         */
        private const val CLOUD_FIELD_GLSL = """
            float cloudHash(vec3 p) {
                return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
            }
            float cloudNoise(vec3 p) {
                vec3 i = floor(p); vec3 f = fract(p);
                vec3 u = f * f * (3.0 - 2.0 * f);
                float a = mix(cloudHash(i), cloudHash(i + vec3(1.0, 0.0, 0.0)), u.x);
                float b = mix(cloudHash(i + vec3(0.0, 1.0, 0.0)), cloudHash(i + vec3(1.0, 1.0, 0.0)), u.x);
                float c = mix(cloudHash(i + vec3(0.0, 0.0, 1.0)), cloudHash(i + vec3(1.0, 0.0, 1.0)), u.x);
                float d = mix(cloudHash(i + vec3(0.0, 1.0, 1.0)), cloudHash(i + vec3(1.0, 1.0, 1.0)), u.x);
                return mix(mix(a, b, u.y), mix(c, d, u.y), u.z);
            }
            // Structure continentale des masses nuageuses : l'octave large,
            // calculee au sommet dans le shader de coquille, refaite ici pour
            // que terrain et coquille partagent EXACTEMENT la meme fonction.
            float cloudBase(vec3 dir, float drift) {
                vec3 q = dir * 2.6 + vec3(drift * 0.11, 0.0, drift * 0.07);
                return sin(q.x * 3.1 + sin(q.z * 2.3)) * sin(q.z * 2.7 + sin(q.y * 3.7))
                     + sin(q.y * 2.9 + sin(q.x * 1.9)) * 0.6;
            }
            // Couverture issue de la SIMULATION : la carte d'humidite est
            // lue en projection equirectangulaire, avec la convention figee
            // du projet (axe polaire Y, longitude atan2(z, x)). Sans cette
            // convention exacte, les nuages seraient decales en longitude
            // par rapport au sol qu'ils sont censes arroser.
            // La couverture est FOURNIE par l'appelant, jamais lue ici :
            // le shader de terrain l'evalue au sommet, ou GLES2 ne garantit
            // AUCUNE unite de texture (GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS
            // peut valoir zero). Une lecture au sommet aurait echoue a lier
            // sur une partie du parc — exactement le piege de precision de
            // la v0.26.1, sous une autre forme.
            vec2 humidityUv(vec3 dir) {
                float lat = asin(clamp(dir.y, -1.0, 1.0));
                float lon = atan(dir.z, dir.x);
                return vec2((lon + 3.14159265) / 6.28318531,
                            0.5 - lat / 3.14159265);
            }
            // Opacite du nuage dans une direction donnee, dans [0, 1].
            //
            // Le bruit donne la FORME, l'humidite simulee donne la
            // PRESENCE : au-dessus d'un desert le seuil monte et presque
            // rien ne perce, au-dessus d'une foret tropicale il descend et
            // le ciel se couvre. C'est ce qui solde la dette du lot 2.14 —
            // jusqu'ici, l'apparence contredisait la simulation.
            float cloudOpacity(vec3 dir, float drift, float cover) {
                vec3 p = dir * 9.0 + vec3(drift * 0.23, 0.0, drift * 0.15);
                float n = cloudNoise(p) * 0.62 + cloudNoise(p * 2.7) * 0.38;
                // Seuil variable : 1,28 sur un ciel sec, 0,72 sur un ciel
                // sature — l'ecart couvre toute la plage utile du bruit.
                float threshold = 1.28 - 0.56 * cover;
                float density = cloudBase(dir, drift) * 0.35 + n * 1.3 - threshold;
                float a = clamp(density, 0.0, 1.0);
                return a * a * (3.0 - 2.0 * a);
            }
        """

        /** Altitude de la couche nuageuse, en mètres. */
        const val CLOUD_ALTITUDE_M = 9_000.0

        private const val CLOUD_VERTEX = """
            attribute vec3 aDir;
            uniform mat4 uMvp;
            uniform vec3 uCenterRel;
            uniform float uShellR;
            uniform float uDrift;
            varying vec3 vDir;
            void main() {
                gl_Position = uMvp * vec4(aDir * uShellR + uCenterRel, 1.0);
                vDir = aDir;
            }
        """

        private const val CLOUD_FRAGMENT = """
            precision mediump float;
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            #define TIME_PRECISION highp
            #else
            #define TIME_PRECISION mediump
            #endif
            uniform vec3 uSunL;
            uniform float uDayF;
            // Precision accordee avec l'etage sommet : Mali refuse de lier
            // deux declarations divergentes (constate en v0.26.0).
            uniform TIME_PRECISION float uDrift;
            uniform sampler2D uHumidity;
            varying vec3 vDir;
""" + CLOUD_FIELD_GLSL + """
            void main() {
                vec3 d = normalize(vDir);
                float cover = texture2D(uHumidity, humidityUv(d)).r;
                float alpha = cloudOpacity(d, uDrift, cover) * 0.62;
                // Eclairage : jour plein cote soleil, gris bleute de nuit.
                float light = max(dot(d, uSunL), 0.0) * 0.85 + 0.10;
                vec3 col = vec3(0.97, 0.97, 0.99) * light * (0.25 + 0.75 * uDayF)
                         + vec3(0.06, 0.07, 0.10) * (1.0 - uDayF);
                gl_FragColor = vec4(col, alpha);
            }
        """


        private const val STAR_VERTEX = """
            attribute vec4 aStar;
            uniform mat4 uMvp;
            uniform float uDist;
            uniform vec2 uSpin;      // (cos, sin) de la rotation propre inverse
            uniform float uPointScale; // densite d'ecran (px par unite d'ecran)
            varying float vMag;
            void main() {
                vec3 d = aStar.xyz;
                vec3 local = vec3(d.x * uSpin.x - d.z * uSpin.y, d.y,
                                  d.x * uSpin.y + d.z * uSpin.x);
                gl_Position = uMvp * vec4(local * uDist, 1.0);
                // Taille mise a l'echelle de la DENSITE d'ecran. En pixels
                // bruts, 1,5 px sur un ecran a 440 dpi fait un dixieme de
                // millimetre : les etoiles existaient, mais sous le seuil de
                // visibilite. Le meme chiffre en unites d'ecran donne 5,5 px
                // sur cet appareil et reste correct sur un ecran mdpi.
                gl_PointSize = (2.0 + 4.0 * aStar.w) * uPointScale;
                vMag = aStar.w;
            }
        """

        private const val STAR_FRAGMENT = """
            precision mediump float;
            uniform float uAlpha;
            varying float vMag;
            void main() {
                vec2 pc = gl_PointCoord * 2.0 - 1.0;
                float r = dot(pc, pc);
                float a = max(0.0, 1.0 - r);
                // a*a concentrait tout au centre : le disque PERCU faisait
                // 60 % du point demande. Un noyau net double d'un halo
                // large rend l'etoile lisible sans la transformer en tache.
                float core = a * a;
                float halo = a * 0.45;
                float glow = min(1.0, core + halo) * uAlpha * (0.30 + 0.70 * vMag);
                gl_FragColor = vec4(vec3(0.90, 0.93, 1.0) * glow, 1.0);
            }
        """

        private const val MOON_VERTEX = """
            attribute vec3 aPos;
            uniform mat4 uMvp;
            uniform vec3 uCenter;
            uniform float uRadius;
            uniform vec3 uSunL;
            varying float vLight;
            void main() {
                gl_Position = uMvp * vec4(uCenter + aPos * uRadius, 1.0);
                // La sphere unitaire est sa propre normale : le terminateur
                // lunaire — la phase — sort du produit scalaire, gratuitement.
                vLight = max(dot(aPos, uSunL), 0.0) * 0.88 + 0.03;
            }
        """

        private const val MOON_FRAGMENT = """
            precision mediump float;
            varying float vLight;
            void main() {
                gl_FragColor = vec4(vec3(0.86, 0.85, 0.81) * vLight, 1.0);
            }
        """

        private const val TAG = "TerraRenderer"

        /**
         * Colore le fond de brume du ciel en magenta. A servi à identifier
         * l'aplat du bas d'écran (v0.10.2) : il s'agissait bien du ciel, donc
         * d'un manque de géométrie, ce qui a orienté vers le plan de coupe.
         * Conservé, éteint : le même doute peut revenir, et le rallumer coûte
         * un booléen.
         */
        const val DIAGNOSTIC_SKY_GROUND = false

        /**
         * Teinte chaque tuile selon son niveau de subdivision.
         *
         * A résolu le défaut du premier plan (v0.10.5) : les bandes colorées
         * ont montré d'un coup d'œil que la couverture proche existait enfin,
         * en anneaux concentriques autour de l'observateur, là où aucun
         * compteur ne pouvait le dire. Conservé, éteint — inspecter la
         * structure du niveau de détail coûte un booléen.
         */
        // Remplacée par le drapeau d'exécution debugLevelTint (v0.19.1) :
        // recompiler pour diagnostiquer contredisait le lot 0.6.
        private const val DEG = 0.017453292f

        /** Téléversements de tuiles par image : au-delà, à-coups visibles. */
        private const val UPLOADS_PER_FRAME = 4

        /**
         * Fraction de la distance de bascule à laquelle le morphing commence.
         *
         * 0,70 : la géométrie fine reste vraie sur les soixante-dix premiers
         * pour cent, et l'interpolation occupe les trente derniers — assez
         * pour être progressive, assez court pour ne pas fausser le relief
         * proche.
         */
        private const val MORPH_START = 0.70

        /** Une tuile non vue pendant ~4 s à 30 i/s est rendue au pool. */
        private const val KEEP_FRAMES = 120L

        // ------------------------------------------------------ shaders globe

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uModel;

            attribute vec3 aPosition;
            attribute vec3 aColor;
            attribute vec3 aNormal;
            attribute float aMaterial;

            varying vec3 vColor;
            varying vec3 vNormal;
            varying vec3 vWorld;
            varying float vMaterial;

            void main() {
                vColor = aColor;
                vNormal = normalize((uModel * vec4(aNormal, 0.0)).xyz);
                vWorld = (uModel * vec4(aPosition, 1.0)).xyz;
                vMaterial = aMaterial;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;

            uniform vec3 uCamera;
            uniform vec3 uSun;

            varying vec3 vColor;
            varying vec3 vNormal;
            varying vec3 vWorld;
            varying float vMaterial;

            void main() {
                vec3 n    = normalize(vNormal);
                vec3 sph  = normalize(vWorld);
                vec3 sun  = normalize(uSun);
                vec3 view = normalize(uCamera - vWorld);

                // Terminateur adouci : le passage jour/nuit s'étale au lieu de
                // trancher net, comme sur une planète pourvue d'atmosphère.
                float day = clamp(dot(sph, sun) * 2.2 + 0.22, 0.0, 1.0);

                float diffuse = max(dot(n, sun), 0.0);
                vec3 color = vColor * (0.10 + 0.85 * diffuse) * day;

                // Reflet spéculaire réservé aux surfaces d'eau. Exposant élevé
                // et intensité modérée : un vrai glint solaire est petit et
                // net, pas une tache blanche couvrant un quart du globe.
                if (vMaterial > 0.5) {
                    vec3 halfway = normalize(sun + view);
                    float spec = pow(max(dot(sph, halfway), 0.0), 160.0);
                    color += vec3(0.90, 0.94, 1.0) * spec * 0.32 * day;
                }

                // Halo atmosphérique sur le limbe, plus intense côté jour.
                float rim = pow(1.0 - max(dot(sph, view), 0.0), 3.5);
                color += vec3(0.28, 0.50, 0.95) * rim * 0.55 * day;

                // Faible lueur nocturne pour que la face sombre reste lisible.
                color += vColor * 0.018;

                // Compression douce des hautes lumières.
                //
                // La neige et la banquise, presque blanches, atteignaient
                // saturation dès qu'elles étaient éclairées : toute la calotte
                // devenait un aplat sans relief. Ce genou ne touche pas les
                // tons moyens et ne comprime que ce qui dépasse, préservant le
                // détail des facettes dans les zones claires.
                float peak = max(color.r, max(color.g, color.b));
                float knee = 0.82;
                if (peak > knee) {
                    float over = peak - knee;
                    color *= (knee + over / (1.0 + over * 1.7)) / peak;
                }

                gl_FragColor = vec4(color, 1.0);
            }
        """

        // --------------------------------------------------- shaders descente

        /**
         * Tout ce qui manipule des mètres vit ici, en highp implicite du
         * vertex shader. Le fragment ne reçoit que des grandeurs dans [0, 1] :
         * sur Mali, mediump est un flottant 16 bits qui sature à 65 504, et
         * une position planétaire y deviendrait infinie.
         */
        private const val TILE_VERTEX_SHADER = """
            uniform mat4 uViewProj;
            uniform vec3 uOffset;       // centre de tuile - oeil, en metres
            uniform vec3 uCenterWorld;  // centre de tuile, repere planete
            uniform vec3 uSun;          // soleil, repere planete
            uniform float uHazeDensity;
            uniform float uRimStrength;
            uniform vec3 uLevelTint;   // composantes < 0 : teinte desactivee
            uniform vec3 uCenterRel;   // centre planetaire en repere camera
            uniform float uCosHorizon; // cos(angle nadir -> horizon)
            uniform float uLimbBand;   // largeur angulaire du fondu de limbe
            uniform float uLimbStrength; // 0 sous 600 km, 1 au-dela de 1500
            uniform float uWaveTime;   // phase de la houle, radians
            uniform float uWaveScale;  // 0 sur tuile grossiere, 1 de pres
            uniform float uCloudDrift;   // meme derive que la coquille
            uniform float uCloudShadow;  // altitude relative des nuages ; 0 = desactive
            uniform float uTileCover;    // humidite moyenne de la tuile, [0,1]

            uniform float uMorph;      // 0 geometrie fine, 1 geometrie parente

            attribute vec3 aPosition;   // relative au centre de tuile, metres
            attribute float aMorph;     // ecart d'altitude vers le niveau parent
            attribute vec3 aColor;
            attribute vec3 aNormal;
            attribute float aMaterial;

            varying vec3 vColor;
            varying float vDiffuse;
            varying float vDay;
            varying float vSpec;
            varying float vFog;
            varying float vRim;
            varying float vCloudShade;
            varying float vDusk;
            varying float vFresnel;
            varying float vFoam;
            varying float vWater;
""" + CLOUD_FIELD_GLSL + """
            void main() {
                vec3 world = uCenterWorld + aPosition;
                vec3 sph = normalize(world);
                vec3 sun = normalize(uSun);
                vec3 nrm = normalize(aNormal);

                // --- Houle -------------------------------------------------
                //
                // Deux trains d'ondes croises, de periodes 80 et 53 m : une
                // seule direction donnerait des rides de tole ondulee. Elles
                // deplacent la surface le long de la verticale locale et
                // inclinent la normale — c'est cette inclinaison qui rend la
                // mer vivante, bien plus que le deplacement lui-meme.
                //
                // L'amplitude s'annule pres du rivage (aMaterial < 1) : une
                // vague qui deborderait sur la plage deplacerait le trait de
                // cote par rapport a la grille, donc par rapport aux biomes.
                // Morphing : le sommet glisse vers l'altitude qu'il aurait au
                // niveau parent. A la bascule (uMorph = 1) les deux maillages
                // coincident, donc plus aucun ressaut.
                vec3 rel = aPosition + uOffset + sph * (aMorph * uMorph);

                float openWater = clamp((aMaterial - 0.85) * 6.67, 0.0, 1.0);
                float waveAmp = uWaveScale * openWater;
                if (waveAmp > 0.0) {
                    vec3 t1 = normalize(cross(sph, vec3(0.0, 1.0, 0.0) + sph * 0.01));
                    vec3 t2 = cross(sph, t1);
                    float p1 = dot(world, t1) * 0.0785 + uWaveTime;
                    float p2 = dot(world, t2) * 0.1185 + uWaveTime * 1.31;
                    float h = sin(p1) * 0.42 + sin(p2) * 0.24;
                    rel += sph * (h * waveAmp);

                    // Normale inclinee par la pente de la houle : derivees
                    // analytiques des deux sinus, pas de difference finie.
                    vec3 slope = t1 * (cos(p1) * 0.42 * 0.0785)
                               + t2 * (cos(p2) * 0.24 * 0.1185);
                    nrm = normalize(nrm - slope * waveAmp * 12.0);
                }

                gl_Position = uViewProj * vec4(rel, 1.0);

                vDay = clamp(dot(sph, sun) * 2.2 + 0.22, 0.0, 1.0);
                vDiffuse = max(dot(nrm, sun), 0.0);

                // --- Ombres de nuages (lot 2.14 b) ---
                //
                // Le point du sol est projete sur la coquille nuageuse LE LONG
                // DU RAYON SOLAIRE : la direction obtenue porte le nuage
                // responsable de l'ombre. Le facteur 1/cos allonge l'ombre
                // quand le soleil rase l'horizon — un nuage projette alors son
                // ombre tres loin, comme un soir d'ete.
                //
                // Calcule au SOMMET et non au fragment : les masses nuageuses
                // sont bien plus larges qu'une maille, l'interpolation ne se
                // voit pas, et le fragment terrain est deja charge.
                vCloudShade = 1.0;
                if (uCloudShadow > 0.0) {
                    float cosSun = max(dot(sph, sun), 0.12);
                    vec3 shadowDir = normalize(sph + sun * (uCloudShadow / cosSun));
                    // 0,55 : un nuage laisse passer une part notable de la
                    // lumiere du ciel ; une ombre totale ferait tache d'encre.
                    vCloudShade = 1.0 - cloudOpacity(shadowDir, uCloudDrift, uTileCover) * 0.55;
                }

                vec3 view = normalize(-rel);

                // Le materiau est desormais CONTINU (0 terre, 1 eau, fondu au
                // rivage) : le reflet s'eteint en degrade sur la frange
                // littorale au lieu de s'arreter au bord d'une facette.
                float waterness = clamp((aMaterial - 0.25) * 2.0, 0.0, 1.0);
                vec3 halfway = normalize(sun + view);
                // Reflet calcule sur la normale de la HOULE, pas sur la
                // verticale : c'est ce qui fait scintiller la mer au lieu de
                // n'y poser qu'une seule tache de soleil.
                // --- Reflexion de Fresnel (lot 2.9, volet reflets) ---
                //
                // Une mer regardee d'aplomb est sombre et laisse voir sa
                // profondeur ; regardee de biais, elle devient un miroir.
                // C'est LE trait visuel de l'eau, et il manquait
                // entierement. Approximation de Schlick, R0 = 0,02 pour
                // n = 1,33 : 2 % a la verticale, 24 % a 75 degres, 65 % a
                // 85 — la bande argentee que l'on voit depuis toute plage,
                // et que le mode pieton regarde precisement de biais.
                //
                // La normale utilisee est celle de la HOULE : les cretes et
                // les creux ne renvoient pas le meme ciel, ce qui fait
                // scintiller la surface au lieu de la vitrifier.
                float cosView = max(dot(nrm, view), 0.0);
                float fres = 0.02 + 0.98 * pow(1.0 - cosView, 5.0);
                vFresnel = fres * waterness;
                vWater = waterness;

                // --- Ecume de rivage ---
                //
                // La frange ou aMaterial passe de terre a eau : c'est la
                // laisse, la ou la houle butte sur le fond. Modulee par le
                // temps de houle pour respirer au lieu de figer un ourlet
                // blanc.
                float shore = waterness * (1.0 - clamp((aMaterial - 0.55) * 4.0, 0.0, 1.0));
                float pulse = 0.55 + 0.45 * sin(uWaveTime * 1.7 + dot(world, sph) * 0.0009);
                vFoam = shore * pulse * uWaveScale;

                float ndh = max(dot(nrm, halfway), 0.0);
                // Eau : reflet serre et vif. Sol : un lustre LARGE et faible,
                // qui existe sur toute surface reelle — sable et roche
                // renvoient la lumiere rasante, la neige davantage. Sans lui,
                // un sol eclaire de biais restait parfaitement mat, ce qui le
                // rendait plat quel que soit le relief.
                //
                // La rugosite se lit dans la COULEUR deja calculee : un sol
                // clair (neige, sel, sable sec) reflechit plus qu'un sol
                // sombre (foret, roche humide). Aucun canal nouveau — le
                // format de sommet reste intact.
                float albedo = (aColor.r + aColor.g + aColor.b) * 0.3333;
                float groundGloss = smoothstep(0.35, 0.85, albedo) * 0.10;
                vSpec = pow(ndh, 90.0) * 0.40 * waterness
                      + pow(ndh, 8.0) * groundGloss * (1.0 - waterness);

                // --- Crepuscule (lot 2.10, promesse non tenue jusqu'ici) ---
                //
                // Le lot 2.10 annoncait un « rougeoiement rasant » ; le ciel
                // se contentait de s'assombrir sans virer. Quand le soleil
                // rase l'horizon LOCAL, sa lumiere traverse une epaisseur
                // d'atmosphere bien plus grande : le bleu est diffuse au
                // loin, il ne reste que l'orange. On mesure exactement cela
                // par le cosinus du soleil sur la verticale du lieu.
                //
                // La fenetre est etroite (le soleil descend vite) et la
                // courbe cubique la resserre encore : l'effet culmine au
                // ras de l'horizon et disparait des que le soleil monte.
                float sunElev = dot(sph, sun);
                float dusk = clamp(1.0 - abs(sunElev) * 6.0, 0.0, 1.0);
                vDusk = dusk * dusk * dusk;

                // Halo du limbe, comme le globe : l'atmosphere s'epaissit la
                // ou le regard frole la sphere. Calcule au sommet, borne [0,1].
                float rim = pow(1.0 - max(dot(sph, view), 0.0), 3.5);
                vRim = rim * uRimStrength * vDay;

                float dist = length(rel);
                float fogDist = 1.0 - exp(-dist * uHazeDensity);
                // Fondu de limbe : angle du rayon de visee au nadir compare
                // a l'angle de l'horizon. Actif en altitude seulement — la
                // brume de distance y est morte, le canal vFog est libre.
                vec3 ray = normalize(rel);
                vec3 nadir = normalize(uCenterRel);
                float cosToNadir = dot(ray, nadir);
                float limb = 1.0 - smoothstep(uCosHorizon, uCosHorizon + uLimbBand, cosToNadir);
                vFog = max(fogDist, limb * uLimbStrength);
                vColor = uLevelTint.r < 0.0 ? aColor
                       : mix(aColor, clamp(uLevelTint, 0.0, 1.0), 0.75);
            }
        """

        private const val TILE_FRAGMENT_SHADER = """
            precision mediump float;

            uniform vec3 uHaze;

            varying vec3 vColor;
            varying float vDiffuse;
            varying float vDay;
            varying float vSpec;
            varying float vFog;
            varying float vRim;
            varying float vCloudShade;
            varying float vDusk;
            varying float vFresnel;
            varying float vFoam;
            varying float vWater;

            void main() {
                // L'ombre du nuage attenue la lumiere DIRECTE seulement :
                // l'ambiante vient du ciel entier, qu'un nuage ne masque pas.
                // La lumiere DIRECTE se teinte au crepuscule ; l'ambiante,
                // qui vient du ciel entier, garde sa couleur. C'est ce qui
                // donne des ombres bleutees sous un soleil orange, comme au
                // vrai couchant.
                vec3 sunColor = mix(vec3(1.0, 1.0, 1.0), vec3(1.32, 0.72, 0.42), vDusk);
                vec3 color = vColor * 0.12 * vDay
                           + vColor * (0.88 * vDiffuse * vCloudShade) * vDay * sunColor;
                color += vec3(0.90, 0.94, 1.0) * vSpec * vDay * sunColor;

                // La mer renvoie le CIEL, dont uHaze porte deja la couleur du
                // moment (bleu de jour, orange au couchant, sombre la nuit) :
                // le reflet suit donc l'heure sans calcul supplementaire.
                color = mix(color, uHaze * (0.55 + 0.45 * vDay), vFresnel * 0.85);

                // Ecume par-dessus le reflet : elle flotte SUR l'eau.
                color = mix(color, vec3(0.92, 0.95, 0.97) * vDay, vFoam * 0.65);
                color += vec3(0.28, 0.50, 0.95) * vRim * 0.55;
                // Lueur nocturne plus franche qu'en orbite : au sol, un noir
                // total rend la face nocturne intestable. Clair de lune
                // implicite.
                //
                // ÉTEINTE SUR L'EAU. Ce terme simule une retrodiffusion de
                // surface : l'herbe, la roche et le sable renvoient un peu
                // de la lumiere du ciel dans toutes les directions. L'eau ne
                // le fait pas — elle REFLECHIT, et un ciel nocturne est
                // noir. Appliquee a l'eau, cette lueur faisait luire lacs et
                // rivieres en filaments blancs au fond des vallees, alors
                // qu'ils devraient etre le point le plus SOMBRE du paysage
                // (constate sur appareil, v0.32.0 : l'eau ressortait 1,5
                // fois plus claire que la foret, de nuit).
                color += vColor * (0.018 + 0.038 * (1.0 - vDay)) * (1.0 - 0.88 * vWater);

                // Meme genou que le globe : les deux chemins doivent rendre
                // les zones claires de la meme facon, sinon la bascule de mode
                // se verrait comme un saut d'exposition.
                float peak = max(color.r, max(color.g, color.b));
                float knee = 0.82;
                if (peak > knee) {
                    float over = peak - knee;
                    color *= (knee + over / (1.0 + over * 1.7)) / peak;
                }

                color = mix(color, uHaze, vFog);
                gl_FragColor = vec4(color, 1.0);
            }
        """

        /**
         * Couche d'eau — lot 2.9-a.
         *
         * Structure du shader de tuile, blocs repris bit à bit (houle,
         * ombres de nuages, Fresnel, crépuscule, limbe, brume) : la mer doit
         * baigner dans le même air et onduler à la même phase que la tuile
         * qu'elle recouvre. Divergences, toutes commentées : openWater est
         * une rampe de PROFONDEUR (plus de aMaterial), la couleur vient de
         * l'absorption par canal (miroir exact de
         * TileMesh.waterAbsorptionColor, testée en CI), et il n'y a PAS de
         * lueur nocturne — l'eau réfléchit, elle ne rétrodiffuse pas
         * (leçon des filaments, v0.32.2).
         */
        private const val WATER_VERTEX_SHADER = """
            uniform mat4 uViewProj;
            uniform vec3 uOffset;       // centre de tuile - oeil, en metres
            uniform vec3 uCenterWorld;  // centre de tuile, repere planete
            uniform vec3 uSun;          // soleil, repere planete
            uniform float uHazeDensity;
            uniform float uRimStrength;
            uniform vec3 uCenterRel;   // centre planetaire en repere camera
            uniform float uCosHorizon; // cos(angle nadir -> horizon)
            uniform float uLimbBand;   // largeur angulaire du fondu de limbe
            uniform float uLimbStrength; // 0 sous 600 km, 1 au-dela de 1500
            uniform float uWaveTime;   // phase de la houle, radians
            uniform float uWaveScale;  // 0 sur tuile grossiere, 1 de pres
            uniform float uCloudDrift;   // meme derive que la coquille
            uniform float uCloudShadow;  // altitude relative des nuages ; 0 = desactive
            uniform float uTileCover;    // humidite moyenne de la tuile, [0,1]

            uniform float uMorph;      // 0 geometrie fine, 1 geometrie parente

            attribute vec3 aPosition;   // relative au centre de tuile, metres
            attribute float aDepth;     // profondeur d'eau sous le sommet, metres
            attribute float aMorph;     // ecart de profondeur vers le parent

            varying float vDepth;
            varying float vDiffuse;
            varying float vDay;
            varying float vSpec;
            varying float vFog;
            varying float vRim;
            varying float vCloudShade;
            varying float vDusk;
            varying float vFresnel;
            varying float vFoam;
""" + CLOUD_FIELD_GLSL + """
            void main() {
                vec3 world = uCenterWorld + aPosition;
                vec3 sph = normalize(world);
                vec3 sun = normalize(uSun);
                // Surface de mer : normale radiale, avant la houle.
                vec3 nrm = sph;

                // Continuite entre niveaux : la POSITION, a rayon constant,
                // n'a rien a morpher (l'ecart inter-niveaux est la sagitta,
                // sub-pixel partout) ; la PROFONDEUR rejoint celle du parent
                // pour que la couleur bascule sans ressaut, comme l'altitude
                // du terrain qu'elle recouvre.
                float depth = aDepth + aMorph * uMorph;
                vDepth = depth;
                vec3 rel = aPosition + uOffset;

                // --- Houle : bloc du shader de tuile, bit a bit. Seule la
                // source d'openWater change : rampe de PROFONDEUR (nulle au
                // trait de cote, pleine a 2 m) au lieu de la rampe de
                // materiau — meme intention, une vague qui deborderait sur
                // la plage deplacerait le trait de cote face aux biomes.
                float openWater = clamp(depth * 0.5, 0.0, 1.0);
                float waveAmp = uWaveScale * openWater;
                if (waveAmp > 0.0) {
                    vec3 t1 = normalize(cross(sph, vec3(0.0, 1.0, 0.0) + sph * 0.01));
                    vec3 t2 = cross(sph, t1);
                    float p1 = dot(world, t1) * 0.0785 + uWaveTime;
                    float p2 = dot(world, t2) * 0.1185 + uWaveTime * 1.31;
                    float h = sin(p1) * 0.42 + sin(p2) * 0.24;
                    rel += sph * (h * waveAmp);
                    vec3 slope = t1 * (cos(p1) * 0.42 * 0.0785)
                               + t2 * (cos(p2) * 0.24 * 0.1185);
                    nrm = normalize(nrm - slope * waveAmp * 12.0);
                }

                gl_Position = uViewProj * vec4(rel, 1.0);

                vDay = clamp(dot(sph, sun) * 2.2 + 0.22, 0.0, 1.0);
                vDiffuse = max(dot(nrm, sun), 0.0);

                // --- Ombres de nuages : bloc du shader de tuile, bit a bit.
                vCloudShade = 1.0;
                if (uCloudShadow > 0.0) {
                    float cosSun = max(dot(sph, sun), 0.12);
                    vec3 shadowDir = normalize(sph + sun * (uCloudShadow / cosSun));
                    vCloudShade = 1.0 - cloudOpacity(shadowDir, uCloudDrift, uTileCover) * 0.55;
                }

                vec3 view = normalize(-rel);
                vec3 halfway = normalize(sun + view);
                // --- Fresnel de Schlick : bloc du shader de tuile, avec
                // waterness = 1 — ici tout est eau.
                float cosView = max(dot(nrm, view), 0.0);
                float fres = 0.02 + 0.98 * pow(1.0 - cosView, 5.0);
                vFresnel = fres;

                // --- Ecume : la ou l'eau devient mince. Rampe reprise du
                // fondu de rive historique (pleine sous 0,4 m, morte a
                // 1,2 m), meme pulsation que l'ancienne ecume de tuile.
                float shore = 1.0 - clamp((depth - 0.4) * 1.25, 0.0, 1.0);
                float pulse = 0.55 + 0.45 * sin(uWaveTime * 1.7 + dot(world, sph) * 0.0009);
                vFoam = shore * pulse * uWaveScale;

                float ndh = max(dot(nrm, halfway), 0.0);
                vSpec = pow(ndh, 90.0) * 0.40;

                // --- Crepuscule, halo de limbe, brume : blocs du shader de
                // tuile, bit a bit.
                float sunElev = dot(sph, sun);
                float dusk = clamp(1.0 - abs(sunElev) * 6.0, 0.0, 1.0);
                vDusk = dusk * dusk * dusk;

                float rim = pow(1.0 - max(dot(sph, view), 0.0), 3.5);
                vRim = rim * uRimStrength * vDay;

                float dist = length(rel);
                float fogDist = 1.0 - exp(-dist * uHazeDensity);
                vec3 ray = normalize(rel);
                vec3 nadir = normalize(uCenterRel);
                float cosToNadir = dot(ray, nadir);
                float limb = 1.0 - smoothstep(uCosHorizon, uCosHorizon + uLimbBand, cosToNadir);
                vFog = max(fogDist, limb * uLimbStrength);
            }
        """

        private const val WATER_FRAGMENT_SHADER = """
            precision mediump float;

            uniform vec3 uHaze;

            varying float vDepth;
            varying float vDiffuse;
            varying float vDay;
            varying float vSpec;
            varying float vFog;
            varying float vRim;
            varying float vCloudShade;
            varying float vDusk;
            varying float vFresnel;
            varying float vFoam;

            void main() {
                // Profondeur bornee AVANT l'exponentielle : en mediump,
                // exp(-6000) sur une fosse oceanique est hors du domaine du
                // flottant 16 bits (les lecons uDrift/uSnow : ne jamais
                // supposer mieux que la norme GLES2). A 200 m le bleu a
                // converge depuis longtemps (120 m).
                float d = min(vDepth, 200.0);

                // --- Lot 2.9-b : la couche ne porte PLUS le fond. ---------
                // L'absorption du trajet fond -> oeil est peinte par le
                // TERRAIN (TileMesh.seafloorColor, pre-divisee par T_bleu) ;
                // ici on ne calcule que ce que l'eau AJOUTE : sa diffusion
                // propre, et l'opacite scalaire qui la fait gagner sur le
                // fond. Alpha = 1 - exp(-2d/32) : MIROIR EXACT de
                // TileMesh.waterLayerAlpha, testee en CI. Toute retouche se
                // fait DES DEUX COTES.
                float alpha = 1.0 - exp(-2.0 * d / 32.0);
                vec3 base = vec3(0.015, 0.11, 0.24);

                // Eclairage : structure du fragment de tuile, bit a bit.
                vec3 sunColor = mix(vec3(1.0, 1.0, 1.0), vec3(1.32, 0.72, 0.42), vDusk);
                vec3 color = base * 0.12 * vDay
                           + base * (0.88 * vDiffuse * vCloudShade) * vDay * sunColor;

                // Reflet du ciel, ecume, halo de limbe : ils ne traversent
                // PAS, ils couvrent — ils poussent donc l'opacite en meme
                // temps qu'ils teintent. Sans cela, un reflet rasant sur un
                // haut-fond laisserait voir le sable au travers du miroir.
                float cover = clamp(vFresnel * 0.85 + vFoam * 0.65, 0.0, 1.0);
                vec3 coverColor = mix(uHaze * (0.55 + 0.45 * vDay),
                                      vec3(0.92, 0.95, 0.97) * vDay,
                                      clamp(vFoam * 0.65 / max(cover, 0.001), 0.0, 1.0));
                color = mix(color, coverColor, cover);
                color += vec3(0.28, 0.50, 0.95) * vRim * 0.55;
                alpha = clamp(alpha + cover * (1.0 - alpha), 0.0, 1.0);

                // Au loin, la brume mange tout : l'eau doit y devenir aussi
                // opaque que l'air qu'elle imite, sinon le fond marin
                // sombre transparaitrait a travers un horizon laiteux.
                color = mix(color, uHaze, vFog);
                alpha = max(alpha, vFog);

                // PAS de lueur nocturne : l'eau REFLECHIT, elle ne
                // retrodiffuse pas (lecon v0.32.2).

                float peak = max(color.r, max(color.g, color.b));
                float knee = 0.82;
                if (peak > knee) {
                    float over = peak - knee;
                    color *= (knee + over / (1.0 + over * 1.7)) / peak;
                }

                // Source PREMULTIPLIEE (blend ONE / ONE_MINUS_SRC_ALPHA) :
                // c'est ce qui permet au speculaire de s'AJOUTER au lieu
                // d'etre attenue par un alpha faible — un eclat de soleil
                // sur trente centimetres d'eau claire reste un eclat.
                vec3 premult = color * alpha
                             + vec3(0.90, 0.94, 1.0) * vSpec * vDay * sunColor;
                gl_FragColor = vec4(premult, alpha);
            }
        """

        private const val SKY_VERTEX_SHADER = """
            attribute vec2 aPos;

            uniform vec3 uFwd;
            uniform vec3 uRight;
            uniform vec3 uUpCam;
            uniform float uTanHalf;
            uniform float uAspect;

            varying vec3 vDir;

            void main() {
                // Direction de visee du pixel, reconstruite depuis le repere
                // camera ; normalisee au fragment, comme toute skybox.
                vDir = uFwd
                     + uRight * (aPos.x * uTanHalf * uAspect)
                     + uUpCam * (aPos.y * uTanHalf);
                gl_Position = vec4(aPos, 0.999, 1.0);
            }
        """

        private const val SKY_FRAGMENT_SHADER = """
            precision mediump float;

            uniform vec3 uTop;
            uniform vec3 uBottom;
            uniform vec3 uGround;
            uniform vec3 uPlanetUp;
            uniform float uHorizonSin;
            uniform vec3 uSunDir;    // direction du soleil, repere monde
            uniform vec3 uSunColor;  // couleur du disque, chaude au couchant
            uniform float uSunGlow;  // intensite du halo atmospherique

            varying vec3 vDir;

            void main() {
                vec3 d = normalize(vDir);
                // Sinus de l'elevation au-dessus de l'horizontale locale.
                float elev = dot(d, uPlanetUp);
                // L'horizon est abaisse sous l'horizontale par l'altitude.
                float horizon = -uHorizonSin;

                if (elev >= horizon) {
                    float t = clamp((elev - horizon) / (1.0 - horizon), 0.0, 1.0);
                    vec3 sky = mix(uBottom, uTop, pow(t, 0.6));

                    // --- Le ciel doit SAVOIR ou est le soleil (lot 2.10 b).
                    // Jusqu'ici il n'etait qu'un degrade vertical : au
                    // couchant, tout l'horizon rougeoyait de la meme facon,
                    // y compris dos au soleil. Deux lobes de diffusion
                    // avant, largeurs CALCULEES : k=64 donne un halo serre
                    // de 8 deg autour de l'astre, k=4 une nappe de 33 deg
                    // qui couche la couleur sur un quart du ciel.
                    float cosSun = max(dot(d, uSunDir), 0.0);
                    float tight = pow(cosSun, 64.0);
                    float broad = pow(cosSun, 4.0);
                    sky += uSunColor * uSunGlow * (tight * 0.55 + broad * 0.22);

                    // --- Le disque. Rayon 0,40 deg, soit un peu plus que le
                    // vrai Soleil (0,265) : a 60 deg de champ sur 1080 px il
                    // couvre 29 px, lisible sans etre une tache. Le bord se
                    // fond sur 0,1 deg — sans cet adoucissement il crenele.
                    float disc = smoothstep(0.99996954, 0.99997563, cosSun);
                    sky = mix(sky, uSunColor * 1.35, disc);

                    gl_FragColor = vec4(sky, 1.0);
                } else {
                    // Sous l'horizon : brume. C'est aussi le fond de secours
                    // des tuiles pas encore pretes — un manque se voit en
                    // brume, plus jamais en trou noir.
                    gl_FragColor = vec4(uGround, 1.0);
                }
            }
        """
    }
}
