package com.terra.planet

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.terra.core.Vec3
import com.terra.sim.CoarseSampler
import com.terra.sim.PlanetCamera
import com.terra.sim.TerrainProfile
import com.terra.sim.TileId
import com.terra.sim.TileMesh
import com.terra.sim.TileSelector
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

    @Volatile var pendingMesh: PlanetMesh? = null

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
    private var tURimStrength = -1
    private var tULevelTint = -1

    private var skyProgram = 0
    private var skyVbo = 0
    private var sAPos = -1
    private var sUTop = -1
    private var sUBottom = -1
    private var sUGround = -1
    private var sUFwd = -1
    private var sURight = -1
    private var sUUpCam = -1
    private var sUPlanetUp = -1
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
        skyProgram = 0
        skyVbo = 0
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
            tURimStrength = GLES20.glGetUniformLocation(tileProgram, "uRimStrength")
            tULevelTint = GLES20.glGetUniformLocation(tileProgram, "uLevelTint")
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

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        pendingMesh?.let {
            upload(it)
            pendingMesh = null
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
        selector.select(camUnit, selection, cone)
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
        val altitude = max(2.0, snapshot.altitudeM)
        val near = PlanetCamera.nearPlaneFor(snapshot.heightAboveGroundM, snapshot.rangeM).toFloat()
        nearPlaneM = near
        // Le plan lointain doit englober l'horizon et les montagnes qui le
        // dépassent ; le facteur absorbe l'inclinaison et les jupes.
        val horizonM = sqrt(max(0.0, (radius + altitude) * (radius + altitude) - radius * radius))
        val far = (horizonM * 1.8 + 80_000.0).toFloat()
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
        drawSky(snapshot, radius, dayFactorAtEye(snapshot, sunLx, sunLz))

        GLES20.glUseProgram(tileProgram)
        GLES20.glUniformMatrix4fv(tUViewProj, 1, false, mvp, 0)
        GLES20.glUniform3f(tUSun, sunLx, sunY, sunLz)

        // Brume : densité choisie pour ~40 % d'atténuation à l'horizon bas —
        // repère de profondeur à peu de frais, et elle voile la transition de
        // niveau de détail au loin en attendant le morphing du lot 2.4.
        val hazeDensity = (0.5 / max(20_000.0, horizonM)).toFloat()
        GLES20.glUniform1f(tUHazeDensity, hazeDensity)
        val dayF = dayFactorAtEye(snapshot, sunLx, sunLz)
        GLES20.glUniform3f(tUHaze, 0.62f * dayF + 0.01f, 0.72f * dayF + 0.012f, 0.85f * dayF + 0.02f)

        // Halo atmosphérique du limbe : plein en orbite, nul au sol — en vue
        // rasante, la direction de visée est presque tangente partout et le
        // halo voilerait toute la scène de bleu.
        val rimStrength = (((snapshot.altitudeM - 60_000.0) / 240_000.0).coerceIn(0.0, 1.0)).toFloat()
        GLES20.glUniform1f(tURimStrength, rimStrength)

        var triangles = 0
        for (tile in drawList) {
            // Diagnostic : teinte par niveau de subdivision, pour voir d'un
            // coup d'œil où s'arrête la couverture proche. Cycle de six
            // teintes vives, le niveau se lit à la couleur.
            if (DIAGNOSTIC_LEVEL_TINT) {
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
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tile.vbo)
            bindTileAttribute(tAPosition, 3, TileMesh.OFFSET_POSITION)
            bindTileAttribute(tAColor, 3, TileMesh.OFFSET_COLOR)
            bindTileAttribute(tANormal, 3, TileMesh.OFFSET_NORMAL)
            bindTileAttribute(tAMaterial, 1, TileMesh.OFFSET_MATERIAL)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, tile.vertexCount)
            triangles += tile.vertexCount / 3
        }
        disableAttribute(tAPosition)
        disableAttribute(tAColor)
        disableAttribute(tANormal)
        disableAttribute(tAMaterial)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        drawnTriangles = triangles
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
        val topR = 0.10f * dayF * presence + 0.004f
        val topG = 0.28f * dayF * presence + 0.008f
        val topB = 0.62f * dayF * presence + 0.022f
        val botR = (0.55f * dayF + 0.016f) * presence + 0.004f
        val botG = (0.66f * dayF + 0.022f) * presence + 0.006f
        val botB = (0.82f * dayF + 0.045f) * presence + 0.016f
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
         * Teinte chaque tuile selon son niveau de subdivision — diagnostic
         * v0.10.5. Montre où s'arrête la couverture proche, question à
         * laquelle aucun compteur ne répond.
         */
        const val DIAGNOSTIC_LEVEL_TINT = true
        private const val DEG = 0.017453292f

        /** Téléversements de tuiles par image : au-delà, à-coups visibles. */
        private const val UPLOADS_PER_FRAME = 4

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

            attribute vec3 aPosition;   // relative au centre de tuile, metres
            attribute vec3 aColor;
            attribute vec3 aNormal;
            attribute float aMaterial;

            varying vec3 vColor;
            varying float vDiffuse;
            varying float vDay;
            varying float vSpec;
            varying float vFog;
            varying float vRim;

            void main() {
                vec3 rel = aPosition + uOffset;
                gl_Position = uViewProj * vec4(rel, 1.0);

                vec3 world = uCenterWorld + aPosition;
                vec3 sph = normalize(world);
                vec3 sun = normalize(uSun);

                vDay = clamp(dot(sph, sun) * 2.2 + 0.22, 0.0, 1.0);
                vDiffuse = max(dot(normalize(aNormal), sun), 0.0);

                vec3 view = normalize(-rel);

                // Le materiau est desormais CONTINU (0 terre, 1 eau, fondu au
                // rivage) : le reflet s'eteint en degrade sur la frange
                // littorale au lieu de s'arreter au bord d'une facette.
                float waterness = clamp((aMaterial - 0.25) * 2.0, 0.0, 1.0);
                vec3 halfway = normalize(sun + view);
                vSpec = pow(max(dot(sph, halfway), 0.0), 160.0) * 0.32 * waterness;

                // Halo du limbe, comme le globe : l'atmosphere s'epaissit la
                // ou le regard frole la sphere. Calcule au sommet, borne [0,1].
                float rim = pow(1.0 - max(dot(sph, view), 0.0), 3.5);
                vRim = rim * uRimStrength * vDay;

                float dist = length(rel);
                vFog = 1.0 - exp(-dist * uHazeDensity);
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

            void main() {
                vec3 color = vColor * (0.12 + 0.88 * vDiffuse) * vDay;
                color += vec3(0.90, 0.94, 1.0) * vSpec * vDay;
                color += vec3(0.28, 0.50, 0.95) * vRim * 0.55;
                // Lueur nocturne plus franche qu'en orbite : au sol, un noir
                // total rend la face nocturne intestable. Clair de lune
                // implicite, en attendant les vraies lunes du lot 2.12.
                color += vColor * (0.018 + 0.038 * (1.0 - vDay));

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

            varying vec3 vDir;

            void main() {
                vec3 d = normalize(vDir);
                // Sinus de l'elevation au-dessus de l'horizontale locale.
                float elev = dot(d, uPlanetUp);
                // L'horizon est abaisse sous l'horizontale par l'altitude.
                float horizon = -uHorizonSin;

                if (elev >= horizon) {
                    float t = clamp((elev - horizon) / (1.0 - horizon), 0.0, 1.0);
                    gl_FragColor = vec4(mix(uBottom, uTop, pow(t, 0.6)), 1.0);
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
