package com.terra.desktop

import com.terra.core.Mat4
import com.terra.sim.PlanetMesh
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL33C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL33C.GL_FLOAT
import org.lwjgl.opengl.GL33C.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL33C.GL_LINK_STATUS
import org.lwjgl.opengl.GL33C.GL_STATIC_DRAW
import org.lwjgl.opengl.GL33C.GL_TRIANGLES
import org.lwjgl.opengl.GL33C.GL_TRUE
import org.lwjgl.opengl.GL33C.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL33C.glAttachShader
import org.lwjgl.opengl.GL33C.glBindBuffer
import org.lwjgl.opengl.GL33C.glBindVertexArray
import org.lwjgl.opengl.GL33C.glBufferData
import org.lwjgl.opengl.GL33C.glCompileShader
import org.lwjgl.opengl.GL33C.glCreateProgram
import org.lwjgl.opengl.GL33C.glCreateShader
import org.lwjgl.opengl.GL33C.glDeleteBuffers
import org.lwjgl.opengl.GL33C.glDeleteProgram
import org.lwjgl.opengl.GL33C.glDeleteShader
import org.lwjgl.opengl.GL33C.glDeleteVertexArrays
import org.lwjgl.opengl.GL33C.glDrawArrays
import org.lwjgl.opengl.GL33C.glEnableVertexAttribArray
import org.lwjgl.opengl.GL33C.glGenBuffers
import org.lwjgl.opengl.GL33C.glGenVertexArrays
import org.lwjgl.opengl.GL33C.glGetProgramInfoLog
import org.lwjgl.opengl.GL33C.glGetProgrami
import org.lwjgl.opengl.GL33C.glGetShaderInfoLog
import org.lwjgl.opengl.GL33C.glGetShaderi
import org.lwjgl.opengl.GL33C.glGetUniformLocation
import org.lwjgl.opengl.GL33C.glLinkProgram
import org.lwjgl.opengl.GL33C.glShaderSource
import org.lwjgl.opengl.GL33C.glUniform3f
import org.lwjgl.opengl.GL33C.glUniformMatrix4fv
import org.lwjgl.opengl.GL33C.glUseProgram
import org.lwjgl.opengl.GL33C.glVertexAttribPointer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rendu du globe sur PC — lot 10.1.
 *
 * Reprend le maillage `PlanetMesh` partagé avec Android (déplacé dans
 * `:sim` par ce lot) et son éclairage, transcrits en GLSL 3.30.
 *
 * ## Les trois pièges du profil core, pour le lot 10.2
 *
 * 1. Un VAO doit être lié, toujours : sans lui, `glDrawArrays` ne dessine
 *    rien et ne signale rien.
 * 2. `attribute`/`varying` n'existent plus : `in` et `out`.
 * 3. `gl_FragColor` n'existe plus : il faut déclarer une sortie.
 */
class GlobeView {

    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var vertexCount = 0

    private var uViewProj = 0
    private var uSun = 0
    private var uEye = 0

    val triangleCount: Int get() = vertexCount / 3

    init {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uViewProj = glGetUniformLocation(program, "uViewProj")
        uSun = glGetUniformLocation(program, "uSun")
        uEye = glGetUniformLocation(program, "uEye")

        vao = glGenVertexArrays()
        vbo = glGenBuffers()
    }

    fun upload(mesh: PlanetMesh) {
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)

        val buffer = BufferUtils.createFloatBuffer(mesh.vertexData.size)
        buffer.put(mesh.vertexData)
        buffer.flip()
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW)

        val stride = PlanetMesh.FLOATS_PER_VERTEX * 4
        // Position, couleur, normale, matériau : la disposition du tampon
        // est celle de PlanetMesh, partagée avec Android.
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, PlanetMesh.OFFSET_POSITION * 4L)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, PlanetMesh.OFFSET_COLOR * 4L)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, PlanetMesh.OFFSET_NORMAL * 4L)
        glEnableVertexAttribArray(2)
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, PlanetMesh.OFFSET_MATERIAL * 4L)
        glEnableVertexAttribArray(3)

        vertexCount = mesh.vertexCount
        glBindVertexArray(0)
        println("Globe téléversé : ${vertexCount / 3} triangles")
    }

    fun draw(yawRad: Double, pitchRad: Double, distance: Double, aspect: Float, time: Double) {
        if (vertexCount == 0) return

        // Œil sur une orbite ; la planète est un disque unité, donc les
        // coordonnées restent petites et le float32 suffit largement —
        // contrairement au rendu métrique du terrain (lot 10.2), qui
        // devra reprendre la soustraction en double d'Android.
        val cp = cos(pitchRad)
        val ex = (distance * cp * sin(yawRad)).toFloat()
        val ey = (distance * sin(pitchRad)).toFloat()
        val ez = (distance * cp * cos(yawRad)).toFloat()

        val proj = Mat4.perspective(0.9f, aspect, 0.02f, 60f)
        val view = Mat4.lookDirection(-ex, -ey, -ez, 0f, 1f, 0f)
        // Les positions du maillage sont absolues : on translate d'abord de
        // −œil, ce que la matrice de vue en coordonnées relatives attend.
        val translate = Mat4.identity()
        translate[12] = -ex
        translate[13] = -ey
        translate[14] = -ez
        val viewProj = Mat4.multiply(proj, Mat4.multiply(view, translate))

        // Soleil tournant lentement : de quoi juger le terminateur sans
        // avoir encore l'horloge du monde branchée (lot 10.2).
        val sunAngle = time * 0.08
        glUseProgram(program)
        glUniformMatrix4fv(uViewProj, false, viewProj)
        glUniform3f(uSun, cos(sunAngle).toFloat(), 0.25f, sin(sunAngle).toFloat())
        glUniform3f(uEye, ex, ey, ez)

        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, vertexCount)
        glBindVertexArray(0)
    }

    fun dispose() {
        if (vbo != 0) glDeleteBuffers(vbo)
        if (vao != 0) glDeleteVertexArrays(vao)
        if (program != 0) glDeleteProgram(program)
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compile(GL_VERTEX_SHADER, vertexSource, "sommet")
        val fs = compile(GL_FRAGMENT_SHADER, fragmentSource, "fragment")
        val prog = glCreateProgram()
        glAttachShader(prog, vs)
        glAttachShader(prog, fs)
        glLinkProgram(prog)
        check(glGetProgrami(prog, GL_LINK_STATUS) == GL_TRUE) {
            "Édition de liens du programme : ${glGetProgramInfoLog(prog)}"
        }
        // Les objets shaders sont attachés au programme, qui en garde une
        // référence : on peut les libérer immédiatement.
        glDeleteShader(vs)
        glDeleteShader(fs)
        return prog
    }

    private fun compile(type: Int, source: String, label: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        check(glGetShaderi(shader, GL_COMPILE_STATUS) == GL_TRUE) {
            "Compilation du shader $label : ${glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private companion object {

        // GLSL 3.30 : `in`/`out` au lieu de `attribute`/`varying`, sortie
        // de fragment déclarée au lieu de gl_FragColor. Le corps de
        // l'éclairage est celui du globe d'Android, à l'identique.
        const val VERTEX_SHADER = """
            #version 330 core

            uniform mat4 uViewProj;

            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aColor;
            layout(location = 2) in vec3 aNormal;
            layout(location = 3) in float aMaterial;

            out vec3 vColor;
            out vec3 vNormal;
            out vec3 vSphere;
            out float vMaterial;

            void main() {
                gl_Position = uViewProj * vec4(aPosition, 1.0);
                vColor = aColor;
                vNormal = aNormal;
                vSphere = normalize(aPosition);
                vMaterial = aMaterial;
            }
        """

        const val FRAGMENT_SHADER = """
            #version 330 core

            uniform vec3 uSun;
            uniform vec3 uEye;

            in vec3 vColor;
            in vec3 vNormal;
            in vec3 vSphere;
            in float vMaterial;

            out vec4 fragColor;

            void main() {
                vec3 n = normalize(vNormal);
                vec3 sun = normalize(uSun);
                vec3 view = normalize(uEye - vSphere);

                float day = clamp(dot(vSphere, sun) * 2.2 + 0.22, 0.0, 1.0);
                float diffuse = max(dot(n, sun), 0.0);
                vec3 color = vColor * (0.10 + 0.85 * diffuse) * day;

                if (vMaterial > 0.5) {
                    vec3 halfway = normalize(sun + view);
                    float spec = pow(max(dot(vSphere, halfway), 0.0), 160.0);
                    color += vec3(0.90, 0.94, 1.0) * spec * 0.32 * day;
                }

                float rim = pow(1.0 - max(dot(vSphere, view), 0.0), 3.5);
                color += vec3(0.28, 0.50, 0.95) * rim * 0.55 * day;
                color += vColor * 0.018;

                float peak = max(color.r, max(color.g, color.b));
                float knee = 0.82;
                if (peak > knee) {
                    float over = peak - knee;
                    color *= (knee + over / (1.0 + over * 1.7)) / peak;
                }

                fragColor = vec4(color, 1.0);
            }
        """
    }
}
