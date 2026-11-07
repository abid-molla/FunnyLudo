package com.funny.ludo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Main menu + the "how many players" setup screen. */
class MenuView(ctx: Context, private val sfx: Sfx, private val onStart: (GameConfig) -> Unit) : View(ctx) {

    private enum class Screen { MAIN, SETUP }
    private enum class Mode { CLASSIC, FRIENDS, BOTS }

    private class Btn(val id: String, val r: RectF)

    private var screen = Screen.MAIN
    private var mode = Mode.CLASSIC
    private var players = 4
    private var tokens = 4
    private val humans = BooleanArray(6) { it == 0 }
    private val buttons = ArrayList<Btn>()
    private var pressed: String? = null

    private var insetTop = 0f
    private var insetBottom = 0f
    private var bgBmp: Bitmap? = null
    private var pawns: Array<Bitmap>? = null
    private val dice = DiceRenderer()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val tpo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val rect = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        insetTop = insets.systemWindowInsetTop.toFloat()
        insetBottom = insets.systemWindowInsetBottom.toFloat()
        return super.onApplyWindowInsets(insets)
    }

    fun showMain() {
        screen = Screen.MAIN
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgBmp = null
    }

    private fun renderBackground() {
        val w = width
        val h = height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        p.shader = RadialGradient(
            w / 2f, h * 0.3f, max(w, h) * 0.95f,
            intArrayOf(0xFF7B3FD1.toInt(), 0xFF3A1B7E.toInt(), 0xFF0E0527.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        bgBmp = bmp
        val sz = dp(54f).toInt()
        pawns = Array(4) { Gfx.pawn(Seats.COLORS[it], sz, (sz * 1.32f).toInt()) }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        if (bgBmp == null) renderBackground()
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        buttons.clear()
        c.drawBitmap(bgBmp!!, 0f, 0f, null)

        // drifting bokeh
        for (i in 0 until 18) {
            val fx = ((i * 0.37f + t * 0.012f * (1 + i % 3)) % 1f)
            val fy = 1f - ((i * 0.23f + t * 0.02f * (1 + i % 4)) % 1f)
            p.color = Gfx.withAlpha(Seats.COLORS[i % 6], 34)
            c.drawCircle(fx * w, fy * h, dp(10f + (i % 5) * 9f), p)
        }

        if (screen == Screen.MAIN) drawMain(c, w, h, t) else drawSetup(c, w, h, t)
        postInvalidateOnAnimation()
    }

    private fun drawTitle(c: Canvas, w: Float, y: Float, t: Float, size: Float) {
        val text = "FunnyLudo"
        tp.textSize = size
        tpo.textSize = size
        tpo.strokeWidth = size * 0.2f
        tpo.color = 0xFF1B0F3F.toInt()
        var total = 0f
        val widths = FloatArray(text.length) { tp.measureText(text[it].toString()) }
        for (v in widths) total += v
        var x = (w - total) / 2f
        tp.textAlign = Paint.Align.LEFT
        tpo.textAlign = Paint.Align.LEFT
        for (i in text.indices) {
            val bob = sin(t * 3f + i * 0.6f) * dp(4f)
            val col = Seats.COLORS[(i / 2) % 4]
            // soft drop shadow
            tpo.color = 0x66000000
            c.drawText(text[i].toString(), x + dp(2f), y + dp(5f) + bob, tpo)
            tpo.color = 0xFF1B0F3F.toInt()
            c.drawText(text[i].toString(), x, y + bob, tpo)
            tp.color = Gfx.lighten(col, 0.15f)
            c.drawText(text[i].toString(), x, y + bob, tp)
            x += widths[i]
        }
        tp.textAlign = Paint.Align.CENTER
        tpo.textAlign = Paint.Align.CENTER
    }

    private fun drawMain(c: Canvas, w: Float, h: Float, t: Float) {
        val top = max(insetTop, dp(12f))
        val bottom = max(insetBottom, dp(12f))

        // tumbling 3D dice pair
        val ds = dp(64f)
        val dy = top + dp(58f)
        dice.draw(c, w / 2f - ds * 0.95f, dy + sin(t * 2f) * dp(5f), ds, t * 1.1f, t * 0.8f)
        dice.draw(c, w / 2f + ds * 0.95f, dy + sin(t * 2f + 2f) * dp(5f), ds, -t * 0.9f + 2f, t * 1.3f)

        drawTitle(c, w, dy + ds * 1.2f, t, min(w * 0.14f, dp(56f)))
        tp.textSize = dp(14f)
        tp.color = 0xFFFFE9A8.toInt()
        c.drawText("Roll  •  Race  •  Laugh  •  2 to 6 players", w / 2f, dy + ds * 1.2f + dp(30f), tp)

        val bw = min(w - dp(48f), dp(340f))
        val gap = dp(14f)
        val areaTop = dy + ds * 1.2f + dp(60f)
        val areaBottom = h - bottom - dp(120f)
        val bh = min(dp(70f), (areaBottom - areaTop - gap * 3) / 4f)
        val x0 = (w - bw) / 2f
        val items = arrayOf(
            Triple("quick", "QUICK PLAY", "You vs 3 bots • 2 pawns each"),
            Triple("classic", "CLASSIC", "Full game • choose players & bots"),
            Triple("friends", "PLAY WITH FRIENDS", "2-6 players on one phone"),
            Triple("bots", "PLAY WITH BOTS", "You vs the computer")
        )
        val cols = intArrayOf(0xFFFB8C00.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFF8E24AA.toInt())
        for ((i, it) in items.withIndex()) {
            val r = RectF(x0, areaTop + i * (bh + gap), x0 + bw, areaTop + i * (bh + gap) + bh)
            buttons.add(Btn(it.first, r))
            glossy(c, r, cols[i], it.second, it.third, pressed == it.first)
        }

        // bobbing pawns + sound button
        val py = h - bottom - dp(112f)
        pawns?.let { pw ->
            for (i in 0 until 4) {
                val bob = abs4(sin(t * 4f + i * 1.3f)) * dp(10f)
                val px = w / 2f + (i - 1.5f) * dp(62f)
                rect.set(px - dp(24f), py - bob, px + dp(24f), py + dp(24f * 1.32f) - bob)
                c.drawBitmap(pw[i], null, rect, bp)
            }
        }
        val sr = RectF(w / 2f - dp(90f), h - bottom - dp(62f), w / 2f + dp(90f), h - bottom - dp(18f))
        buttons.add(Btn("sound", sr))
        glossy(c, sr, if (sfx.enabled) 0xFF00897B.toInt() else 0xFF757575.toInt(),
            if (sfx.enabled) "SOUND: ON" else "SOUND: OFF", null, pressed == "sound")
    }

    private fun abs4(v: Float) = if (v < 0f) -v else v

    private fun drawSetup(c: Canvas, w: Float, h: Float, t: Float) {
        val top = max(insetTop, dp(12f))
        val bottom = max(insetBottom, dp(12f))
        val title = when (mode) {
            Mode.CLASSIC -> "CLASSIC"
            Mode.FRIENDS -> "PLAY WITH FRIENDS"
            Mode.BOTS -> "PLAY WITH BOTS"
        }
        tpo.textSize = dp(26f)
        tpo.strokeWidth = dp(5f)
        tpo.color = 0xFF1B0F3F.toInt()
        c.drawText(title, w / 2f, top + dp(52f), tpo)
        tp.textSize = dp(26f)
        tp.color = Color.WHITE
        c.drawText(title, w / 2f, top + dp(52f), tp)

        var y = top + dp(100f)
        tp.textSize = dp(16f)
        tp.color = 0xFFFFE9A8.toInt()
        c.drawText(if (mode == Mode.BOTS) "How many players in total?" else "How many players?", w / 2f, y, tp)
        y += dp(18f)

        // number chips 2..6
        val chip = dp(52f)
        val gap = dp(10f)
        val rowW = chip * 5 + gap * 4
        var cx = (w - rowW) / 2f
        for (n in 2..6) {
            val r = RectF(cx, y, cx + chip, y + chip)
            buttons.add(Btn("p$n", r))
            val sel = n == players
            val pr = pressed == "p$n"
            p.shader = LinearGradient(r.left, r.top, r.left, r.bottom,
                if (sel) 0xFFFFE57F.toInt() else 0xFF7E57C2.toInt(), if (sel) 0xFFFF9800.toInt() else 0xFF311B92.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(r.centerX(), r.centerY() + (if (pr) dp(2f) else 0f), chip / 2f, p)
            p.shader = null
            sp.color = if (sel) Color.WHITE else 0x88FFFFFF.toInt()
            sp.strokeWidth = dp(if (sel) 3f else 1.5f)
            c.drawCircle(r.centerX(), r.centerY(), chip / 2f, sp)
            tp.textSize = dp(24f)
            tp.color = if (sel) 0xFF3E2723.toInt() else Color.WHITE
            c.drawText(n.toString(), r.centerX(), r.centerY() + dp(9f), tp)
            cx += chip + gap
        }
        y += chip + dp(14f)

        tp.textSize = dp(12f)
        tp.color = 0xCCFFFFFF.toInt()
        c.drawText(
            if (players <= 4) "Classic cross board" else "Big 6-arm star board",
            w / 2f, y, tp
        )
        y += dp(12f)
        val tr0 = RectF(w / 2f - dp(105f), y, w / 2f + dp(105f), y + dp(38f))
        buttons.add(Btn("ntok", tr0))
        glossy(c, tr0, 0xFF00ACC1.toInt(), "PAWNS EACH: $tokens  (tap)", null, pressed == "ntok", small = true)
        y += dp(52f)

        val seats = BoardLayout.seatsFor(players)
        val listBottom = h - bottom - dp(150f)
        if (mode == Mode.CLASSIC) {
            val rh = min(dp(52f), (listBottom - y) / players - dp(8f))
            val rw = min(w - dp(40f), dp(360f))
            for (i in 0 until players) {
                val r = RectF((w - rw) / 2f, y, (w + rw) / 2f, y + rh)
                p.color = 0x33FFFFFF
                c.drawRoundRect(r, rh * 0.3f, rh * 0.3f, p)
                p.color = Seats.COLORS[seats[i]]
                c.drawCircle(r.left + rh * 0.6f, r.centerY(), rh * 0.3f, p)
                tp.textAlign = Paint.Align.LEFT
                tp.textSize = dp(16f)
                tp.color = Color.WHITE
                c.drawText("Player ${i + 1}  •  ${Seats.NAMES[seats[i]]}", r.left + rh * 1.1f, r.centerY() + dp(6f), tp)
                tp.textAlign = Paint.Align.CENTER
                val tr = RectF(r.right - dp(96f), r.top + rh * 0.14f, r.right - dp(10f), r.bottom - rh * 0.14f)
                buttons.add(Btn("t$i", tr))
                glossy(c, tr, if (humans[i]) 0xFF43A047.toInt() else 0xFFE53935.toInt(),
                    if (humans[i]) "HUMAN" else "BOT", null, pressed == "t$i", small = true)
                y += rh + dp(8f)
            }
        } else {
            val msg = if (mode == Mode.FRIENDS) "Pass the phone around - every player is a human."
            else "You play Red. The other ${players - 1} seat(s) are bots."
            tp.textSize = dp(14f)
            tp.color = Color.WHITE
            c.drawText(msg, w / 2f, y + dp(10f), tp)
            val dot = dp(20f)
            var dx = (w - (players * dot * 2 + (players - 1) * dp(6f))) / 2f
            for (i in 0 until players) {
                p.color = Seats.COLORS[seats[i]]
                c.drawCircle(dx + dot, y + dp(52f) + (sin(t * 4f + i) * dp(3f)), dot, p)
                sp.color = Color.WHITE
                sp.strokeWidth = dp(2f)
                c.drawCircle(dx + dot, y + dp(52f) + (sin(t * 4f + i) * dp(3f)), dot, sp)
                dx += dot * 2 + dp(6f)
            }
        }

        val bw = min(w - dp(60f), dp(300f))
        val sr = RectF((w - bw) / 2f, h - bottom - dp(130f), (w + bw) / 2f, h - bottom - dp(66f))
        buttons.add(Btn("start", sr))
        glossy(c, sr, 0xFF43A047.toInt(), "START GAME", null, pressed == "start")
        val br = RectF((w - bw) / 2f, h - bottom - dp(56f), (w + bw) / 2f, h - bottom - dp(10f))
        buttons.add(Btn("back", br))
        glossy(c, br, 0xFF5E35B1.toInt(), "BACK", null, pressed == "back", small = true)
    }

    private fun glossy(c: Canvas, r: RectF, color: Int, title: String, sub: String?, down: Boolean, small: Boolean = false) {
        val rr = r.height() * 0.3f
        val off = if (down) dp(3f) else 0f
        val depth = dp(if (small) 3f else 5f)
        p.color = Gfx.darken(color, 0.5f)
        rect.set(r.left, r.top + depth, r.right, r.bottom + depth - off)
        c.drawRoundRect(rect, rr, rr, p)
        rect.set(r.left, r.top + off, r.right, r.bottom + off)
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom, Gfx.lighten(color, 0.32f), Gfx.darken(color, 0.12f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rr, rr, p)
        p.shader = null
        // gloss
        p.color = 0x40FFFFFF
        val g = RectF(rect.left + dp(4f), rect.top + dp(3f), rect.right - dp(4f), rect.centerY())
        c.drawRoundRect(g, rr, rr, p)
        sp.color = 0xAAFFFFFF.toInt()
        sp.strokeWidth = dp(1.5f)
        c.drawRoundRect(rect, rr, rr, sp)

        val ty = rect.centerY()
        if (sub == null) {
            tp.textSize = dp(if (small) 14f else 20f)
            tpo.textSize = tp.textSize
            tpo.strokeWidth = tp.textSize * 0.14f
            tpo.color = Gfx.withAlpha(Gfx.darken(color, 0.6f), 200)
            c.drawText(title, rect.centerX(), ty + tp.textSize * 0.35f, tpo)
            tp.color = Color.WHITE
            c.drawText(title, rect.centerX(), ty + tp.textSize * 0.35f, tp)
        } else {
            tp.textSize = dp(19f)
            tpo.textSize = tp.textSize
            tpo.strokeWidth = tp.textSize * 0.14f
            tpo.color = Gfx.withAlpha(Gfx.darken(color, 0.6f), 200)
            c.drawText(title, rect.centerX(), ty - dp(2f), tpo)
            tp.color = Color.WHITE
            c.drawText(title, rect.centerX(), ty - dp(2f), tp)
            tp.textSize = dp(11.5f)
            tp.color = 0xDDFFFFFF.toInt()
            c.drawText(sub, rect.centerX(), ty + dp(16f), tp)
        }
    }

    // ------------------------------------------------------------------ input

    private fun hit(x: Float, y: Float): String? = buttons.firstOrNull { it.r.contains(x, y) }?.id

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> pressed = hit(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                val id = hit(e.x, e.y)
                val was = pressed
                pressed = null
                if (id != null && id == was) click(id)
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        return true
    }

    private fun click(id: String) {
        sfx.play("step")
        when {
            id == "quick" -> onStart(GameConfig(4, booleanArrayOf(true, false, false, false), 2))
            id == "classic" -> openSetup(Mode.CLASSIC)
            id == "friends" -> openSetup(Mode.FRIENDS)
            id == "bots" -> openSetup(Mode.BOTS)
            id == "sound" -> sfx.enabled = !sfx.enabled
            id == "ntok" -> tokens = if (tokens == 4) 2 else 4
            id == "back" -> screen = Screen.MAIN
            id.startsWith("p") -> players = id.substring(1).toInt()
            id.startsWith("t") -> {
                val i = id.substring(1).toInt()
                humans[i] = !humans[i]
            }
            id == "start" -> {
                val hs = when (mode) {
                    Mode.FRIENDS -> BooleanArray(players) { true }
                    Mode.BOTS -> BooleanArray(players) { it == 0 }
                    Mode.CLASSIC -> BooleanArray(players) { humans[it] }
                }
                onStart(GameConfig(players, hs, tokens))
            }
        }
    }

    private fun openSetup(m: Mode) {
        mode = m
        screen = Screen.SETUP
        players = 4
        tokens = 4
        for (i in humans.indices) humans[i] = i == 0
    }
}
