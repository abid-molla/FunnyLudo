package com.funny.ludo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object Seats {
    val NAMES = arrayOf("Red", "Green", "Yellow", "Blue", "Purple", "Orange")
    val COLORS = intArrayOf(
        0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt(),
        0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt()
    )
    val GREY = 0xFFB9B1A4.toInt()
}

object Gfx {
    fun lighten(c: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = min(1f, hsv[2] * (1f + f))
        hsv[1] = max(0f, hsv[1] * (1f - f * 0.45f))
        return Color.HSVToColor(Color.alpha(c), hsv)
    }

    fun darken(c: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = max(0f, hsv[2] * (1f - f))
        return Color.HSVToColor(Color.alpha(c), hsv)
    }

    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun star(cx: Float, cy: Float, outer: Float, inner: Float, path: Path = Path()): Path {
        path.reset()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = (-PI / 2 + i * PI / 5).toFloat()
            val x = cx + r * cos(a)
            val y = cy + r * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** A shiny 3D-looking "goti" (pawn): base disc, tapered body, spherical head. Anchored at the bottom centre. */
    fun pawn(color: Int, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val W = w.toFloat()
        val H = h.toFloat()
        val dark = darken(color, 0.45f)
        val light = lighten(color, 0.55f)
        val edge = darken(color, 0.7f)

        val baseRect = RectF(W * 0.06f, H * 0.78f, W * 0.94f, H * 0.99f)
        p.shader = LinearGradient(0f, baseRect.top, 0f, baseRect.bottom, light, dark, Shader.TileMode.CLAMP)
        c.drawOval(baseRect, p)

        val body = Path().apply {
            moveTo(W * 0.17f, H * 0.89f)
            quadTo(W * 0.36f, H * 0.80f, W * 0.40f, H * 0.46f)
            lineTo(W * 0.60f, H * 0.46f)
            quadTo(W * 0.64f, H * 0.80f, W * 0.83f, H * 0.89f)
            quadTo(W * 0.5f, H * 0.98f, W * 0.17f, H * 0.89f)
            close()
        }
        p.shader = LinearGradient(
            W * 0.17f, 0f, W * 0.83f, 0f,
            intArrayOf(dark, color, light, color, dark),
            floatArrayOf(0f, 0.25f, 0.42f, 0.72f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPath(body, p)

        // collar
        val collar = RectF(W * 0.30f, H * 0.42f, W * 0.70f, H * 0.53f)
        p.shader = LinearGradient(0f, collar.top, 0f, collar.bottom, light, dark, Shader.TileMode.CLAMP)
        c.drawOval(collar, p)

        // head
        val hx = W * 0.5f
        val hy = H * 0.29f
        val hr = W * 0.27f
        p.shader = RadialGradient(
            hx - hr * 0.35f, hy - hr * 0.4f, hr * 1.6f,
            intArrayOf(Color.WHITE, light, color, dark), floatArrayOf(0f, 0.18f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(hx, hy, hr, p)

        // outlines
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, W * 0.035f)
        p.color = withAlpha(edge, 200)
        c.drawOval(baseRect, p)
        c.drawPath(body, p)
        c.drawCircle(hx, hy, hr, p)

        // specular glint
        p.style = Paint.Style.FILL
        p.color = withAlpha(Color.WHITE, 190)
        c.drawOval(RectF(hx - hr * 0.62f, hy - hr * 0.72f, hx - hr * 0.12f, hy - hr * 0.32f), p)
        return bmp
    }
}

/**
 * A real 3D die: six faces are rotated with a 3D matrix and projected, so it can tumble.
 * Faces: 1 +z, 6 -z, 2 +x, 5 -x, 3 +y, 4 -y (opposite faces add up to 7).
 */
class DiceRenderer {
    private class Face(val value: Int, val n: FloatArray, val eu: FloatArray, val ev: FloatArray)

    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    private val faces = listOf(
        Face(1, v(0f, 0f, 1f), v(1f, 0f, 0f), v(0f, 1f, 0f)),
        Face(6, v(0f, 0f, -1f), v(1f, 0f, 0f), v(0f, 1f, 0f)),
        Face(2, v(1f, 0f, 0f), v(0f, 0f, 1f), v(0f, 1f, 0f)),
        Face(5, v(-1f, 0f, 0f), v(0f, 0f, 1f), v(0f, 1f, 0f)),
        Face(3, v(0f, 1f, 0f), v(1f, 0f, 0f), v(0f, 0f, 1f)),
        Face(4, v(0f, -1f, 0f), v(1f, 0f, 0f), v(0f, 0f, 1f))
    )

    private val pips = arrayOf(
        intArrayOf(), intArrayOf(4), intArrayOf(0, 8), intArrayOf(0, 4, 8),
        intArrayOf(0, 2, 6, 8), intArrayOf(0, 2, 4, 6, 8), intArrayOf(0, 2, 3, 5, 6, 8)
    )

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99503C28.toInt()
    }
    private val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val tmp = FloatArray(3)
    private val out = FloatArray(3)

    private val vx = -0.36f   // fixed viewing tilt: the rolled face dominates, two side faces peek out
    private val vy = -0.48f
    private val light = floatArrayOf(-0.35f, -0.65f, 0.68f)

    /** angles (ay, ax) that bring the face with this value to the front */
    fun finalAngles(value: Int, res: FloatArray) {
        val hp = (PI / 2).toFloat()
        when (value) {
            1 -> { res[0] = 0f; res[1] = 0f }
            6 -> { res[0] = PI.toFloat(); res[1] = 0f }
            2 -> { res[0] = -hp; res[1] = 0f }
            5 -> { res[0] = hp; res[1] = 0f }
            3 -> { res[0] = 0f; res[1] = hp }
            else -> { res[0] = 0f; res[1] = -hp }
        }
    }

    private fun xf(p: FloatArray, ay: Float, ax: Float, o: FloatArray) {
        val x = p[0]
        val y = p[1]
        val z = p[2]
        val y1 = y * cos(ax) - z * sin(ax)
        val z1 = y * sin(ax) + z * cos(ax)
        val x2 = x * cos(ay) + z1 * sin(ay)
        val z2 = -x * sin(ay) + z1 * cos(ay)
        val x3 = x2 * cos(vy) + z2 * sin(vy)
        val z3 = -x2 * sin(vy) + z2 * cos(vy)
        o[0] = x3
        o[1] = y1 * cos(vx) - z3 * sin(vx)
        o[2] = y1 * sin(vx) + z3 * cos(vx)
    }

    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, ay: Float, ax: Float, alpha: Int = 255) {
        val sc = size * 0.36f
        facePaint.pathEffect = CornerPathEffect(size * 0.07f)
        edgePaint.pathEffect = CornerPathEffect(size * 0.07f)
        edgePaint.strokeWidth = max(1f, size * 0.025f)
        val corner = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)
        for (f in faces) {
            xf(f.n, ay, ax, out)
            if (out[2] < 0.02f) continue                        // back face
            val diffuse = max(0f, out[0] * light[0] + out[1] * light[1] + out[2] * light[2])
            val shade = 0.58f + 0.42f * diffuse
            path.reset()
            for (i in 0 until 4) {
                val sx = corner[i * 2]
                val sy = corner[i * 2 + 1]
                tmp[0] = f.n[0] + f.eu[0] * sx + f.ev[0] * sy
                tmp[1] = f.n[1] + f.eu[1] * sx + f.ev[1] * sy
                tmp[2] = f.n[2] + f.eu[2] * sx + f.ev[2] * sy
                xf(tmp, ay, ax, out)
                val px = cx + out[0] * sc
                val py = cy + out[1] * sc
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            val g = (255 * shade).toInt().coerceIn(0, 255)
            facePaint.color = Color.argb(alpha, g, (g * 0.985f).toInt(), (g * 0.94f).toInt())
            c.drawPath(path, facePaint)
            edgePaint.alpha = (alpha * 0.6f).toInt()
            c.drawPath(path, edgePaint)

            // pips
            val big = f.value == 1
            val pr = if (big) 0.3f else 0.17f
            pipPaint.color = if (big) Color.argb(alpha, 215, 30, 45) else Color.argb(alpha, 30, 28, 40)
            for (idx in pips[f.value]) {
                val pu = (idx % 3 - 1) * 0.52f
                val pv = (idx / 3 - 1) * 0.52f
                path.reset()
                for (s in 0 until 14) {
                    val t = (s * 2 * PI / 14).toFloat()
                    val ru = pr * cos(t)
                    val rv = pr * sin(t)
                    tmp[0] = f.n[0] + f.eu[0] * (pu + ru) + f.ev[0] * (pv + rv)
                    tmp[1] = f.n[1] + f.eu[1] * (pu + ru) + f.ev[1] * (pv + rv)
                    tmp[2] = f.n[2] + f.eu[2] * (pu + ru) + f.ev[2] * (pv + rv)
                    xf(tmp, ay, ax, out)
                    val px = cx + out[0] * sc
                    val py = cy + out[1] * sc
                    if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                c.drawPath(path, pipPaint)
            }
        }
    }
}
