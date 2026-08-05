package com.terra.planet

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Moteur de rendu de la planète.
 *
 * Points traités dans ce lot :
 *  - stockage des sommets en VBO côté GPU plutôt qu'en tampon client
 *  - reconstruction propre après perte du contexte OpenGL (lot 0.9) : le
 *    maillage vit dans [MainActivity], le renderer ne fait que le téléverser
 *  - mesure du temps par image pour le HUD de debug (lot 0.6)
 */
class PlanetRenderer : GLSurfaceView.Renderer {

    // --- État caméra, écrit depuis le fil UI, lu depuis le fil GL ---
    @Volatile var yaw = 0f
    @Volatile var pitch = 15f
    @Volatile var distance = 3.2f
    @Volatile var autoRotate = true

    // --- Télémétrie lue par le HUD ---
    @Volatile var frameMs = 0f
        private set
    @Volatile var fps = 0f
        private set
    @Volatile var drawnTriangles = 0
        private set

    /** Maillage à téléverser. Remplacé par [MainActivity] à chaque nouveau monde. */
    @Volatile var pendingMesh: PlanetMesh? = null

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

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val temp = FloatArray(16)
    private val mvp = FloatArray(16)

    private var spin = 0f
    private var sunAngle = 0.6f
    private var lastFrameNanos = 0L
    private var fpsAccumulator = 0f
    private var fpsFrames = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Le contexte a pu être détruit (mise en veille) : tout identifiant GPU
        // conservé est désormais invalide. On repart de zéro sans toucher aux
        // données du monde, qui vivent hors du renderer.
        program = 0
        vbo = 0
        uploadedVertexCount = 0

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) return

        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aMaterial = GLES20.glGetAttribLocation(program, "aMaterial")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uCamera = GLES20.glGetUniformLocation(program, "uCamera")
        uSun = GLES20.glGetUniformLocation(program, "uSun")

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glClearColor(0.004f, 0.006f, 0.016f, 1f)

        lastFrameNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = if (height > 0) width.toFloat() / height else 1f
        Matrix.perspectiveM(projection, 0, 42f, ratio, 0.02f, 60f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f
                 else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.25f)
        lastFrameNanos = now

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        pendingMesh?.let {
            upload(it)
            pendingMesh = null
        }

        if (program != 0 && uploadedVertexCount > 0) {
            if (autoRotate) spin += dt * 2.2f
            sunAngle += dt * 0.05f

            Matrix.setLookAtM(view, 0, 0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, yaw + spin, 0f, 1f, 0f)
            Matrix.multiplyMM(temp, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
            GLES20.glUniform3f(uCamera, 0f, 0f, distance)
            GLES20.glUniform3f(uSun, cos(sunAngle) * 0.82f, 0.30f, sin(sunAngle) * 0.82f)

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

        frameMs = (System.nanoTime() - now) / 1_000_000f
        fpsAccumulator += dt
        fpsFrames++
        if (fpsAccumulator >= 0.5f) {
            fps = fpsFrames / fpsAccumulator
            fpsAccumulator = 0f
            fpsFrames = 0
        }
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
            Log.e(TAG, "Édition de liens échouée : " + GLES20.glGetProgramInfoLog(p))
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
            Log.e(TAG, "Compilation de shader échouée : " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "TerraRenderer"

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
                // trancher net, comme sur une vraie planète avec atmosphère.
                float day = clamp(dot(sph, sun) * 2.6 + 0.30, 0.0, 1.0);

                float diffuse = max(dot(n, sun), 0.0);
                vec3 color = vColor * (0.09 + 0.98 * diffuse) * day;

                // Reflet spéculaire réservé aux surfaces d'eau.
                if (vMaterial > 0.5) {
                    vec3 halfway = normalize(sun + view);
                    float spec = pow(max(dot(sph, halfway), 0.0), 70.0);
                    color += vec3(0.95, 0.97, 1.0) * spec * 0.60 * day;
                }

                // Halo atmosphérique sur le limbe.
                float rim = pow(1.0 - max(dot(sph, view), 0.0), 3.5);
                color += vec3(0.28, 0.50, 0.95) * rim * 0.90 * day;

                // Faible lueur nocturne pour que la face sombre reste lisible.
                color += vColor * 0.020;

                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
