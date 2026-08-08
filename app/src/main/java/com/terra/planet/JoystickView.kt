package com.terra.planet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Joystick virtuel pour le déplacement en mode sol (v0.17.1).
 *
 * Publie un vecteur normalisé ([vx], [vy]) dans [−1, 1], avec x vers la
 * droite et y vers le HAUT — la conversion vers les axes écran d'Android
 * (y vers le bas) est faite ici, une seule fois, pour que le consommateur
 * raisonne en termes humains : « pousser en haut » donne vy positif.
 *
 * La vue consomme ses événements tactiles : un doigt posé sur le joystick
 * ne doit jamais faire tourner le globe derrière. C'est le retour `true`
 * de [onTouchEvent] qui l'assure — l'activité ne reçoit que les événements
 * qu'aucune vue n'a consommés.
 */
class JoystickView(context: Context) : View(context) {

    /** Vecteur courant, normalisé. (0, 0) quand le manche est relâché. */
    var vx = 0f; private set
    var vy = 0f; private set

    /** Appelé quand le manche quitte le centre : démarre la boucle de déplacement. */
    var onEngaged: (() -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(56, 200, 220, 240)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(110, 200, 220, 240)
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 235, 250, 255)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val baseR = min(width, height) * 0.46f
        val knobR = baseR * 0.42f
        val travel = baseR - knobR
        canvas.drawCircle(cx, cy, baseR, basePaint)
        canvas.drawCircle(cx, cy, baseR, rimPaint)
        // y écran vers le bas : le pommeau monte quand vy est positif.
        canvas.drawCircle(cx + vx * travel, cy - vy * travel, knobR, knobPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val cx = width / 2f
                val cy = height / 2f
                val travel = min(width, height) * 0.46f * 0.58f   // baseR − knobR
                var dx = (e.x - cx) / travel
                var dy = (cy - e.y) / travel                       // inversion écran → haut
                val len = sqrt(dx * dx + dy * dy)
                if (len > 1f) { dx /= len; dy /= len }
                val wasIdle = vx == 0f && vy == 0f
                vx = dx; vy = dy
                invalidate()
                if (wasIdle && (vx != 0f || vy != 0f)) onEngaged?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vx = 0f; vy = 0f
                invalidate()
            }
        }
        return true
    }
}
