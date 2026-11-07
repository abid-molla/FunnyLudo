package com.funny.ludo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The game screen: animated 3D-style board, tumbling dice, hopping pawns, effects. */
class LudoView(ctx: Context, private val sfx: Sfx) : View(ctx) {

    var onMenu: (() -> Unit)? = null
    var onPlayAgain: (() -> Unit)? = null

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float,
        val kind: Int, var rot: Float, val vr: Float, val grav: Float
    )

    private class Popup(val text: String, val x: Float, val y: Float, val color: Int, val t0: Long, val dur: Long)
    private class Ring(val x: Float, val y: Float, val color: Int, val t0: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val running = ArrayList<ValueAnimator>()
    private val layouts = HashMap<Int, BoardLayout>()
    private var lay = BoardLayout(4)
    private var game = Game(4, booleanArrayOf(true, false, true, false), booleanArrayOf(true, false, true, false), 4)

    // what is drawn (lags behind game.pos while a piece is animating)
    private val disp = IntArray(24) { -1 }
    private val ovOn = BooleanArray(24)
    private val ovX = FloatArray(24)
    private val ovY = FloatArray(24)
    private val ovZ = FloatArray(24)
    private val ovSx = FloatArray(24) { 1f }
    private val ovSy = FloatArray(24) { 1f }
    private val ovRot = FloatArray(24)

    private val lastDice = IntArray(6) { 1 }
    private var busy = true
    private var rolling = false
    private var picking = false
    private var movables: List<Int> = emptyList()
    private var message = ""
    private var diceAy = 0f
    private var diceAx = 0f
    private var diceLift = 0f
    private var winnerShown = false

    // geometry
    private var s = 10f
    private var ox = 0f
    private var oy = 0f
    private var dsz = 40f
    private var insetTop = 0f
    private var insetBottom = 0f
    private val badgeRect = Array(6) { RectF() }
    private val diceCenter = Array(6) { PointF() }
    private val homeBtn = RectF()
    private val soundBtn = RectF()
    private val playBtn = RectF()
    private val menuBtn = RectF()

    private var bg: Bitmap? = null
    private val sprites = arrayOfNulls<Bitmap>(6)
    private var spriteSize = 0
    private val dice = DiceRenderer()
    private val angTmp = FloatArray(2)

    private val particles = ArrayList<Particle>()
    private val popups = ArrayList<Popup>()
    private val rings = ArrayList<Ring>()
    private var shakeUntil = 0L
    private var shakeAmp = 0f
    private var lastFrame = 0L

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
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
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()

    // per-frame piece layout
    private val gx = FloatArray(24)
    private val gy = FloatArray(24)
    private val gsc = FloatArray(24)

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun px(x: Float) = ox + x * s
    private fun py(y: Float) = oy + y * s
    private fun seatColor(k: Int) = if (game.active[k]) Seats.COLORS[k] else Seats.GREY

    // =====================================================================================
    // game flow
    // =====================================================================================

    fun startGame(cfg: GameConfig) {
        stop()
        val arms = BoardLayout.armsFor(cfg.players)
        lay = layouts.getOrPut(arms) { BoardLayout(arms) }
        val seats = BoardLayout.seatsFor(cfg.players)
        val active = BooleanArray(arms)
        val human = BooleanArray(arms)
        for ((i, st) in seats.withIndex()) {
            active[st] = true
            human[st] = cfg.humans.getOrElse(i) { false }
        }
        game = Game(arms, active, human, cfg.tokens)
        disp.fill(-1)
        ovOn.fill(false)
        lastDice.fill(1)
        particles.clear()
        popups.clear()
        rings.clear()
        rolling = false
        picking = false
        movables = emptyList()
        winnerShown = false
        diceAy = 0f
        diceAx = 0f
        relayout()
        beginTurn()
        invalidate()
    }

    /** stop timers and animations (used when going back to the menu) */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        for (a in running.toList()) a.cancel()
        running.clear()
    }

    private fun anim(
        durationMs: Long, interp: TimeInterpolator = LinearInterpolator(),
        onUpdate: (Float) -> Unit, onEnd: () -> Unit = {}
    ) {
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = durationMs
        va.interpolator = interp
        var cancelled = false
        va.addUpdateListener { onUpdate(it.animatedValue as Float) }
        va.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                running.remove(va)
                if (!cancelled) onEnd()
            }
        })
        running.add(va)
        va.start()
    }

    private fun beginTurn(note: String = "") {
        picking = false
        movables = emptyList()
        val seat = game.turn
        val human = game.isHuman[seat]
        message = (if (note.isEmpty()) "" else "$note  ") + Seats.NAMES[seat] + "'s turn" + if (human) "" else " (Bot)"
        busy = !human
        if (!human) handler.postDelayed({ doRoll() }, 850)
    }

    private fun doRoll() {
        if (game.winner >= 0) return
        busy = true
        picking = false
        rolling = true
        val seat = game.turn
        val v = game.rollDice()
        sfx.play("roll")
        dice.finalAngles(v, angTmp)
        val fay = angTmp[0]
        val fax = angTmp[1]
        val spinY = (2 * PI * (2 + Random.nextInt(2)) * (if (Random.nextBoolean()) 1 else -1)).toFloat()
        val spinX = (2 * PI * (1 + Random.nextInt(2)) * (if (Random.nextBoolean()) 1 else -1)).toFloat()
        anim(950, DecelerateInterpolator(1.3f), { f ->
            val k = 1f - f
            diceAy = fay + spinY * k
            diceAx = fax + spinX * k
            diceLift = (abs(sin(PI * f * 3.2)) * (1f - f)).toFloat()
        }, {
            rolling = false
            diceAy = fay
            diceAx = fax
            diceLift = 0f
            lastDice[seat] = v
            afterRoll()
        })
    }

    private fun afterRoll() {
        val seat = game.turn
        val who = Seats.NAMES[seat]
        val bc = badgeCenter(seat)

        if (game.dice == 6) {
            sfx.six()
            confetti(bc.x, bc.y, 22, false)
            popup("SIX!", bc.x, bc.y + dp(30f), 0xFFFFD54F.toInt())
        }

        if (game.threeSixes) {
            message = "Three 6s in a row! $who loses the turn"
            handler.postDelayed({ sfx.fahh(); popup("3 SIXES!", width / 2f, oy + lay.size * s / 2f, 0xFFFF5252.toInt()) }, 700)
            handler.postDelayed({ game.endTurn(); beginTurn() }, 2300)
            return
        }

        val opts = game.movablePieces()
        if (opts.isEmpty()) {
            message = "$who can't move"
            handler.postDelayed({
                sfx.fahh()
                popup("NO MOVE", width / 2f, oy + lay.size * s / 2f, 0xFFFF8A80.toInt())
            }, if (game.dice == 6) 900 else 300)
            handler.postDelayed({ game.endTurn(); beginTurn() }, 1900)
            return
        }

        message = "$who rolled ${game.dice}"
        if (!game.isHuman[seat]) {
            handler.postDelayed({ doMove(game.botPick(opts)) }, 800)
        } else if (opts.size == 1) {
            handler.postDelayed({ doMove(opts[0]) }, 550)
        } else {
            movables = opts
            picking = true
            busy = false
            message = "$who rolled ${game.dice}: tap a glowing pawn"
        }
    }

    private fun badgeCenter(seat: Int) = PointF(badgeRect[seat].centerX(), badgeRect[seat].centerY())

    private fun relPoint(seat: Int, i: Int, rel: Int): PointF = when {
        rel == -1 -> lay.yardSpots[seat][yardIndex(i)]
        rel <= game.trackMax -> lay.track[game.absCell(seat, rel)]
        rel < game.finish -> lay.home[seat][rel - game.trackMax - 1]
        else -> lay.finishSpots[seat][i]
    }

    /** with 2 tokens per player use the two diagonal yard spots */
    private fun yardIndex(i: Int) = if (game.tokens == 2) (if (i == 0) 0 else 3) else i

    private fun doMove(piece: Int) {
        busy = true
        picking = false
        movables = emptyList()
        val res = game.apply(piece)
        val seat = res.player
        val i = piece % 4
        if (res.from == -1) {
            sfx.play("out")
            hop(piece, listOf(relPoint(seat, i, -1), relPoint(seat, i, 0)), listOf(-1, 0), 430L, 1.2f, {}) {
                afterMove(res)
            }
        } else {
            val pts = ArrayList<PointF>()
            val rels = ArrayList<Int>()
            for (r in res.from..res.to) {
                pts.add(relPoint(seat, i, r))
                rels.add(r)
            }
            hop(piece, pts, rels, 230L, 0.55f, { sfx.play("step") }) { afterMove(res) }
        }
    }

    /** hop a piece along `pts`; rels[i] is the logical position after landing on pts[i] */
    private fun hop(
        piece: Int, pts: List<PointF>, rels: List<Int>, hopMs: Long, arc: Float,
        onLand: (Int) -> Unit, onDone: () -> Unit
    ) {
        val n = pts.size - 1
        var landed = 0
        ovOn[piece] = true
        ovRot[piece] = 0f
        anim(n * hopMs, LinearInterpolator(), { f ->
            val t = f * n
            val seg = min(floor(t).toInt(), n - 1)
            val fr = t - seg
            while (landed < seg) {
                landed++
                disp[piece] = rels[landed]
                onLand(landed)
            }
            val a = pts[seg]
            val b = pts[seg + 1]
            ovX[piece] = a.x + (b.x - a.x) * fr
            ovY[piece] = a.y + (b.y - a.y) * fr
            ovZ[piece] = sin(PI * fr).toFloat() * arc
            val land = if (fr < 0.16f) (0.16f - fr) / 0.16f else 0f
            ovSy[piece] = 1f - 0.16f * land + 0.08f * sin(PI * fr).toFloat()
            ovSx[piece] = 1f + 0.12f * land
        }, {
            while (landed < n) {
                landed++
                disp[piece] = rels[landed]
                onLand(landed)
            }
            ovOn[piece] = false
            ovZ[piece] = 0f
            ovSx[piece] = 1f
            ovSy[piece] = 1f
            onDone()
        })
    }

    private fun flyBack(piece: Int) {
        val seat = piece / 4
        val i = piece % 4
        val from = relPoint(seat, i, disp[piece])
        val to = relPoint(seat, i, -1)
        ovOn[piece] = true
        anim(750, AccelerateDecelerateInterpolator(), { f ->
            ovX[piece] = from.x + (to.x - from.x) * f
            ovY[piece] = from.y + (to.y - from.y) * f
            ovZ[piece] = sin(PI * f).toFloat() * 2.6f
            ovRot[piece] = 720f * f
            val sc = 1f - 0.25f * sin(PI * f).toFloat()
            ovSx[piece] = sc
            ovSy[piece] = sc
        }, {
            disp[piece] = -1
            ovOn[piece] = false
            ovZ[piece] = 0f
            ovRot[piece] = 0f
            ovSx[piece] = 1f
            ovSy[piece] = 1f
        })
    }

    private fun afterMove(res: MoveResult) {
        val fp = relPoint(res.player, res.piece % 4, res.to)
        val cx = px(fp.x)
        val cy = py(fp.y)
        var delay = 380L

        if (res.captured.isNotEmpty()) {
            sfx.cut()
            delay = 1750L
            shake(550, dp(7f))
            val victims = res.captured.map { it / 4 }.distinct()
            for (v in victims) burst(cx, cy, Seats.COLORS[v], 18)
            burst(cx, cy, 0xFFFFD54F.toInt(), 14)
            rings.add(Ring(cx, cy, 0xFFFFFFFF.toInt(), SystemClock.uptimeMillis()))
            popup("CUT!", cx, cy - s * 0.6f, 0xFFFF5252.toInt())
            message = Seats.NAMES[res.player] + " cut " + victims.joinToString(" & ") { Seats.NAMES[it] } + "!"
            for (v in res.captured) flyBack(v)
        } else if (res.finished && game.winner < 0) {
            sfx.home()
            delay = 1500L
            confetti(cx, cy, 40, true)
            popup("HOME!", cx, cy - s * 0.6f, 0xFFFFD54F.toInt())
            message = Seats.NAMES[res.player] + " got a pawn home!"
        } else if (res.landedSafe) {
            sfx.safe()
            delay = 900L
            burst(cx, cy, 0xFFFFE082.toInt(), 14)
            rings.add(Ring(cx, cy, 0xFFFFE082.toInt(), SystemClock.uptimeMillis()))
            popup("SAFE!", cx, cy - s * 0.6f, 0xFF69F0AE.toInt())
        }

        if (game.winner >= 0) {
            busy = true
            winnerShown = true
            message = Seats.NAMES[game.winner] + " wins!"
            handler.postDelayed({ sfx.win() }, 500)
            confetti(width / 2f, height * 0.3f, 90, true)
            return
        }

        val note = if (res.extraTurn) "Roll again!" else ""
        handler.postDelayed({ beginTurn(note) }, delay)
    }

    // =====================================================================================
    // effects
    // =====================================================================================

    private fun popup(text: String, x: Float, y: Float, color: Int) {
        popups.add(Popup(text, x, y, color, SystemClock.uptimeMillis(), 1100))
    }

    private fun shake(ms: Long, amp: Float) {
        shakeUntil = SystemClock.uptimeMillis() + ms
        shakeAmp = amp
    }

    private fun burst(x: Float, y: Float, color: Int, n: Int) {
        repeat(n) {
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val v = dp(60f) + Random.nextFloat() * dp(220f)
            particles.add(
                Particle(
                    x, y, kotlin.math.cos(a) * v, kotlin.math.sin(a) * v - dp(60f),
                    0.7f + Random.nextFloat() * 0.5f, 1.2f, color, dp(3f) + Random.nextFloat() * dp(5f),
                    if (Random.nextBoolean()) 1 else 0, Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 600f, dp(420f)
                )
            )
        }
    }

    private fun confetti(x: Float, y: Float, n: Int, wide: Boolean) {
        val cols = Seats.COLORS
        repeat(n) {
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val v = dp(80f) + Random.nextFloat() * dp(if (wide) 360f else 200f)
            particles.add(
                Particle(
                    x, y, kotlin.math.cos(a) * v, kotlin.math.sin(a) * v - dp(180f),
                    1.2f + Random.nextFloat() * 1.0f, 2.2f, cols[Random.nextInt(cols.size)],
                    dp(4f) + Random.nextFloat() * dp(5f), 2, Random.nextFloat() * 360f,
                    (Random.nextFloat() - 0.5f) * 900f, dp(380f)
                )
            )
        }
    }

    // =====================================================================================
    // input
    // =====================================================================================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val x = e.x
        val y = e.y
        if (winnerShown) {
            if (playBtn.contains(x, y)) onPlayAgain?.invoke()
            else if (menuBtn.contains(x, y)) onMenu?.invoke()
            return true
        }
        if (homeBtn.contains(x, y)) {
            onMenu?.invoke()
            return true
        }
        if (soundBtn.contains(x, y)) {
            sfx.enabled = !sfx.enabled
            return true
        }
        if (busy || game.winner >= 0 || !game.isHuman[game.turn]) return true
        if (picking) {
            val pc = pieceAt(x, y)
            if (pc >= 0) doMove(pc)
        } else {
            rect.set(badgeRect[game.turn])
            rect.inset(-dp(14f), -dp(14f))
            if (rect.contains(x, y)) doRoll()
        }
        return true
    }

    private fun pieceAt(x: Float, y: Float): Int {
        computeGroups()
        var best = -1
        var bd = Float.MAX_VALUE
        for (pc in movables) {
            val dx = px(gx[pc]) - x
            val dy = py(gy[pc]) - y
            val d = dx * dx + dy * dy
            if (d < bd) {
                bd = d
                best = pc
            }
        }
        return if (best >= 0 && bd < (s * 1.15f) * (s * 1.15f)) best else -1
    }

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        insetTop = insets.systemWindowInsetTop.toFloat()
        insetBottom = insets.systemWindowInsetBottom.toFloat()
        relayout()
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    // =====================================================================================
    // layout
    // =====================================================================================

    private fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val top = max(insetTop, dp(8f))
        val bottom = max(insetBottom, dp(8f))
        val bw = min(dp(132f), (w - dp(12f)) / 3.15f)
        dsz = bw * 0.46f
        val bh = dsz * 1.55f + dp(30f)
        val barH = dp(44f)
        val reserveTop = top + barH + bh + dp(6f)
        val reserveBottom = bottom + bh + dp(6f)
        val availH = h - reserveTop - reserveBottom
        val eff = lay.size + 0.9f
        s = max(4f, min((w - dp(12f)) / eff, availH / eff))
        ox = (w - lay.size * s) / 2f
        oy = reserveTop + (availH - lay.size * s) / 2f
        val boardTop = oy - 0.45f * s
        val boardBottom = oy + lay.size * s + 0.45f * s
        for (k in 0 until lay.arms) {
            if (!game.active[k]) continue
            val ang = lay.armAngle[k] + (PI / lay.arms).toFloat()
            val isTop = sin(ang) < 0
            val yx = ox + lay.yardCenter[k].x * s
            val cx = yx.coerceIn(bw / 2 + dp(6f), w - bw / 2 - dp(6f))
            val cy = if (isTop) boardTop - bh / 2 - dp(2f) else boardBottom + bh / 2 + dp(2f)
            badgeRect[k].set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
            diceCenter[k].set(cx, badgeRect[k].top + dp(8f) + dsz * 0.62f)
        }
        val bsz = dp(40f)
        homeBtn.set(dp(10f), top + dp(2f), dp(10f) + bsz, top + dp(2f) + bsz)
        soundBtn.set(w - dp(10f) - bsz, top + dp(2f), w - dp(10f), top + dp(2f) + bsz)

        val sz = (s * 1.0f).toInt().coerceAtLeast(8)
        if (sz != spriteSize) {
            spriteSize = sz
            for (k in 0 until 6) sprites[k] = Gfx.pawn(Seats.COLORS[k], sz, (sz * 1.32f).toInt())
        }
        bg = null
    }

    // =====================================================================================
    // drawing: static layer (backdrop + board), rendered once into a bitmap
    // =====================================================================================

    private fun renderStatic() {
        val w = width
        val h = height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        p.reset()
        p.isAntiAlias = true

        // backdrop
        p.shader = RadialGradient(
            w / 2f, h * 0.42f, max(w, h) * 0.85f,
            intArrayOf(0xFF5B2FA3.toInt(), 0xFF24114F.toInt(), 0xFF0A0420.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val rnd = Random(5)
        p.color = 0x30FFFFFF
        repeat(70) { c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, rnd.nextFloat() * dp(2.2f) + dp(0.6f), p) }

        val bs = lay.size * s
        val frame = 0.38f * s
        val outer = RectF(ox - frame, oy - frame, ox + bs + frame, oy + bs + frame)
        val board = RectF(ox, oy, ox + bs, oy + bs)

        // wood frame with drop shadow
        p.color = Color.BLACK
        p.setShadowLayer(0.6f * s, 0f, 0.3f * s, 0xAA000000.toInt())
        shape(c, outer, 0.55f * s, p)
        p.clearShadowLayer()
        p.shader = LinearGradient(
            outer.left, outer.top, outer.right, outer.bottom,
            intArrayOf(0xFFD9A066.toInt(), 0xFF9C5F2B.toInt(), 0xFF5E3A1A.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        shape(c, outer, 0.55f * s, p)
        p.shader = null
        sp.style = Paint.Style.STROKE
        sp.strokeWidth = 0.05f * s
        sp.color = 0x55FFFFFF
        rect.set(outer)
        rect.inset(0.04f * s, 0.04f * s)
        shape(c, rect, 0.5f * s, sp)

        // board surface
        p.shader = LinearGradient(board.left, board.top, board.right, board.bottom, 0xFFFFFCF2.toInt(), 0xFFF0DFB6.toInt(), Shader.TileMode.CLAMP)
        shape(c, board, 0.25f * s, p)
        p.shader = null
        sp.strokeWidth = 0.06f * s
        sp.color = 0x66000000
        shape(c, board, 0.25f * s, sp)

        // yards
        for (k in 0 until lay.arms) drawYardStatic(c, k)

        // track cells
        for (i in 0 until lay.trackLen) {
            val seatOfStart = if (i % 13 == 0) i / 13 else -1
            val fill = if (seatOfStart >= 0) seatColor(seatOfStart) else 0xFFFFFFFF.toInt()
            val pt = lay.track[i]
            cellStatic(c, pt.x, pt.y, Math.toDegrees(lay.trackAngle[i].toDouble()).toFloat(), fill)
        }
        for (k in 0 until lay.arms) for (j in 0 until 5) {
            val pt = lay.home[k][j]
            cellStatic(c, pt.x, pt.y, Math.toDegrees(lay.armAngle[k].toDouble()).toFloat(), Gfx.lighten(seatColor(k), 0.08f))
        }

        // centre triangles
        val cx = px(lay.center.x)
        val cy = py(lay.center.y)
        val half = (PI / lay.arms).toFloat()
        for (k in 0 until lay.arms) {
            val col = seatColor(k)
            val a1 = lay.armAngle[k] - half
            val a2 = lay.armAngle[k] + half
            val r = lay.centerR * s
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx + r * kotlin.math.cos(a1), cy + r * kotlin.math.sin(a1))
            path.lineTo(cx + r * kotlin.math.cos(a2), cy + r * kotlin.math.sin(a2))
            path.close()
            val mx = cx + r * 0.7f * kotlin.math.cos(lay.armAngle[k])
            val my = cy + r * 0.7f * kotlin.math.sin(lay.armAngle[k])
            p.shader = RadialGradient(mx, my, r * 0.9f, Gfx.lighten(col, 0.35f), Gfx.darken(col, 0.2f), Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            p.shader = null
            sp.strokeWidth = 0.05f * s
            sp.color = 0xCCFFFFFF.toInt()
            c.drawPath(path, sp)
        }

        // stars on safe cells
        for (i in 0 until lay.trackLen) {
            val pt = lay.track[i]
            val m = i % 13
            if (m == 8) {
                Gfx.star(px(pt.x), py(pt.y), 0.36f * s, 0.15f * s, path)
                p.shader = LinearGradient(px(pt.x), py(pt.y) - 0.36f * s, px(pt.x), py(pt.y) + 0.36f * s, 0xFFFFE57F.toInt(), 0xFFFF9800.toInt(), Shader.TileMode.CLAMP)
                c.drawPath(path, p)
                p.shader = null
                sp.strokeWidth = 0.03f * s
                sp.color = 0xFF8D5A00.toInt()
                c.drawPath(path, sp)
            } else if (m == 0) {
                Gfx.star(px(pt.x), py(pt.y), 0.34f * s, 0.14f * s, path)
                p.color = 0xFFFFFFFF.toInt()
                c.drawPath(path, p)
                sp.strokeWidth = 0.03f * s
                sp.color = 0x66000000
                c.drawPath(path, sp)
            }
        }
        bg = bmp
    }

    private fun shape(c: Canvas, r: RectF, radius: Float, paint: Paint) {
        if (lay.round) c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, paint)
        else c.drawRoundRect(r, radius, radius, paint)
    }

    private fun cellStatic(c: Canvas, x: Float, y: Float, angDeg: Float, fill: Int) {
        c.save()
        c.translate(px(x), py(y))
        c.rotate(angDeg)
        val h = 0.5f * s - 0.03f * s
        rect.set(-h, -h, h, h)
        p.shader = LinearGradient(0f, -h, 0f, h, Gfx.lighten(fill, 0.07f), Gfx.darken(fill, 0.09f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, 0.1f * s, 0.1f * s, p)
        p.shader = null
        sp.strokeWidth = 0.03f * s
        sp.color = 0x40000000
        c.drawRoundRect(rect, 0.1f * s, 0.1f * s, sp)
        sp.strokeWidth = 0.05f * s
        sp.color = 0x70FFFFFF
        c.drawLine(-h * 0.8f, -h * 0.82f, h * 0.8f, -h * 0.82f, sp)
        c.restore()
    }

    private fun drawYardStatic(c: Canvas, k: Int) {
        val col = seatColor(k)
        val yc = lay.yardCenter[k]
        val cx = px(yc.x)
        val cy = py(yc.y)
        val half = lay.yardHalf * s
        if (!lay.round) {
            rect.set(cx - half, cy - half, cx + half, cy + half)
            p.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, Gfx.lighten(col, 0.25f), Gfx.darken(col, 0.18f), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, 0.35f * s, 0.35f * s, p)
            val ir = RectF(rect)
            ir.inset(0.85f * s, 0.85f * s)
            p.shader = LinearGradient(ir.left, ir.top, ir.left, ir.bottom, 0xFFFFFFFF.toInt(), 0xFFEADFC8.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(ir, 0.45f * s, 0.45f * s, p)
            p.shader = null
            sp.strokeWidth = 0.08f * s
            sp.color = 0x33000000
            c.drawRoundRect(ir, 0.45f * s, 0.45f * s, sp)
        } else {
            p.shader = LinearGradient(cx - half, cy - half, cx + half, cy + half, Gfx.lighten(col, 0.25f), Gfx.darken(col, 0.18f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, half, p)
            p.shader = LinearGradient(cx, cy - half, cx, cy + half, 0xFFFFFFFF.toInt(), 0xFFEADFC8.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, half - 0.45f * s, p)
            p.shader = null
            sp.strokeWidth = 0.08f * s
            sp.color = 0x33000000
            c.drawCircle(cx, cy, half - 0.45f * s, sp)
        }
        if (!game.active[k]) return
        for (i in 0 until game.tokens) {
            val spt = lay.yardSpots[k][yardIndex(i)]
            val sx = px(spt.x)
            val sy = py(spt.y)
            p.shader = RadialGradient(
                sx, sy - 0.1f * s, 0.66f * s,
                intArrayOf(Gfx.lighten(col, 0.75f), Gfx.lighten(col, 0.4f), Gfx.darken(col, 0.25f)),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(sx, sy, 0.6f * s, p)
            p.shader = null
            sp.strokeWidth = 0.05f * s
            sp.color = 0x80FFFFFF.toInt()
            c.drawCircle(sx, sy, 0.6f * s, sp)
        }
    }

    // =====================================================================================
    // drawing: dynamic layer
    // =====================================================================================

    override fun onDraw(c: Canvas) {
        if (width == 0 || height == 0) return
        if (bg == null) renderStatic()
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now

        c.save()
        if (now < shakeUntil) {
            val k = (shakeUntil - now) / 550f
            c.translate((Random.nextFloat() - 0.5f) * 2f * shakeAmp * k, (Random.nextFloat() - 0.5f) * 2f * shakeAmp * k)
        }
        c.drawBitmap(bg!!, 0f, 0f, null)

        drawTurnGlow(c, now)
        computeGroups()
        drawHighlights(c, now)
        drawPieces(c, now)
        for (k in 0 until lay.arms) if (game.active[k]) drawBadge(c, k, now)
        drawEffects(c, now, dt)
        c.restore()

        drawTopBar(c)
        if (winnerShown) drawWinner(c, now)
        postInvalidateOnAnimation()
    }

    private fun drawTurnGlow(c: Canvas, now: Long) {
        if (game.winner >= 0) return
        val k = game.turn
        val pulse = (sin(now / 220.0) * 0.5 + 0.5).toFloat()
        val yc = lay.yardCenter[k]
        val cx = px(yc.x)
        val cy = py(yc.y)
        val half = lay.yardHalf * s
        sp.color = Gfx.withAlpha(0xFFFFFFFF.toInt(), 90 + (140 * pulse).toInt())
        sp.strokeWidth = 0.14f * s
        if (!lay.round) {
            rect.set(cx - half, cy - half, cx + half, cy + half)
            c.drawRoundRect(rect, 0.35f * s, 0.35f * s, sp)
        } else c.drawCircle(cx, cy, half, sp)
    }

    private fun drawHighlights(c: Canvas, now: Long) {
        if (!picking) return
        val pulse = (sin(now / 130.0) * 0.5 + 0.5).toFloat()
        for (pc in movables) {
            val x = px(gx[pc])
            val y = py(gy[pc]) + 0.25f * s
            p.color = Gfx.withAlpha(0xFFFFFFFF.toInt(), 70 + (90 * pulse).toInt())
            rect.set(x - s * (0.62f + 0.12f * pulse), y - s * (0.3f + 0.06f * pulse), x + s * (0.62f + 0.12f * pulse), y + s * (0.3f + 0.06f * pulse))
            c.drawOval(rect, p)
            sp.strokeWidth = 0.06f * s
            sp.color = 0xFFFFD54F.toInt()
            c.drawOval(rect, sp)
        }
    }

    private fun validPieces(action: (Int) -> Unit) {
        for (k in 0 until lay.arms) if (game.active[k]) for (i in 0 until game.tokens) action(k * 4 + i)
    }

    private fun computeGroups() {
        val groups = HashMap<Int, MutableList<Int>>()
        validPieces { pc ->
            val pt = relPoint(pc / 4, pc % 4, disp[pc])
            gx[pc] = pt.x
            gy[pc] = pt.y
            gsc[pc] = if (disp[pc] >= game.finish) 0.6f else 1f
            if (!ovOn[pc] && disp[pc] >= 0 && disp[pc] < game.finish) {
                val key = (pt.x * 10).roundToInt() * 10000 + (pt.y * 10).roundToInt()
                groups.getOrPut(key) { mutableListOf() }.add(pc)
            }
        }
        for (g in groups.values) {
            if (g.size < 2) continue
            for ((idx, pc) in g.withIndex()) {
                gx[pc] += (idx - (g.size - 1) / 2f) * 0.3f
                gsc[pc] = 0.8f
            }
        }
    }

    private fun drawPieces(c: Canvas, now: Long) {
        val list = ArrayList<Int>()
        validPieces { list.add(it) }
        fun key(pc: Int): Float = (if (ovOn[pc]) ovY[pc] + 50f else gy[pc])
        list.sortBy { key(it) }
        for (pc in list) {
            val seat = pc / 4
            val spr = sprites[seat] ?: continue
            var ux = gx[pc]
            var uy = gy[pc]
            var z = 0f
            var sx = 1f
            var sy = 1f
            var rot = 0f
            var sc = gsc[pc]
            if (ovOn[pc]) {
                ux = ovX[pc]
                uy = ovY[pc]
                z = ovZ[pc]
                sx = ovSx[pc]
                sy = ovSy[pc]
                rot = ovRot[pc]
                sc = 1f
            } else if (picking && pc in movables) {
                z = 0.1f + 0.14f * abs(sin(now / 170.0)).toFloat()
            }
            val cx = px(ux)
            val ground = py(uy) + 0.3f * s
            val lift = z * s
            // shadow
            val sh = 1f - min(0.5f, z * 0.25f)
            p.color = Gfx.withAlpha(Color.BLACK, (95 * sh).toInt())
            rect.set(cx - 0.36f * s * sc * sh, ground - 0.1f * s * sc, cx + 0.36f * s * sc * sh, ground + 0.1f * s * sc)
            c.drawOval(rect, p)
            // pawn
            val pw = 0.95f * s * sc * sx
            val ph = 1.25f * s * sc * sy
            c.save()
            if (rot != 0f) c.rotate(rot, cx, ground - lift - ph / 2f)
            rect.set(cx - pw / 2f, ground - lift - ph, cx + pw / 2f, ground - lift)
            c.drawBitmap(spr, null, rect, bp)
            c.restore()
        }
    }

    private fun drawBadge(c: Canvas, k: Int, now: Long) {
        val r = badgeRect[k]
        val col = Seats.COLORS[k]
        val isTurn = k == game.turn && game.winner < 0
        val pulse = (sin(now / 160.0) * 0.5 + 0.5).toFloat()
        val rad = dp(14f)

        if (isTurn) {
            sp.color = Gfx.withAlpha(col, 70 + (120 * pulse).toInt())
            sp.strokeWidth = dp(7f)
            rect.set(r)
            rect.inset(-dp(2f), -dp(2f))
            c.drawRoundRect(rect, rad, rad, sp)
        }
        p.shader = LinearGradient(
            r.left, r.top, r.left, r.bottom,
            Gfx.withAlpha(Gfx.lighten(col, 0.1f), if (isTurn) 240 else 150),
            Gfx.withAlpha(Gfx.darken(col, 0.55f), if (isTurn) 240 else 150), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
        sp.color = Gfx.withAlpha(0xFFFFFFFF.toInt(), if (isTurn) 230 else 90)
        sp.strokeWidth = dp(if (isTurn) 2.5f else 1.2f)
        c.drawRoundRect(r, rad, rad, sp)

        // dice with ground shadow
        val dc = diceCenter[k]
        val waiting = isTurn && !busy && !picking && game.isHuman[k]
        val size = dsz * (1f + if (waiting) 0.1f * pulse else 0f)
        val lift = if (isTurn && rolling) diceLift * dsz * 0.55f else 0f
        val sh = 1f - min(0.5f, lift / dsz)
        p.color = Gfx.withAlpha(Color.BLACK, (80 * sh).toInt())
        rect.set(dc.x - size * 0.42f * sh, dc.y + size * 0.52f - size * 0.06f, dc.x + size * 0.42f * sh, dc.y + size * 0.52f + size * 0.06f)
        c.drawOval(rect, p)
        if (isTurn && rolling) dice.draw(c, dc.x, dc.y - lift, size, diceAy, diceAx, 255)
        else {
            dice.finalAngles(lastDice[k], angTmp)
            dice.draw(c, dc.x, dc.y, size, angTmp[0], angTmp[1], if (isTurn) 255 else 190)
        }

        // name + progress
        tp.color = Color.WHITE
        tp.textSize = dp(13f)
        c.drawText(Seats.NAMES[k] + if (game.isHuman[k]) "" else " (Bot)", r.centerX(), r.bottom - dp(18f), tp)
        tp.textSize = dp(10f)
        tp.color = 0xFFFFE9A8.toInt()
        val sub = if (waiting) "TAP TO ROLL" else "Home " + game.finishedCount(k) + "/" + game.tokens
        c.drawText(sub, r.centerX(), r.bottom - dp(6f), tp)
    }

    private fun drawEffects(c: Canvas, now: Long, dt: Float) {
        // shock rings
        val ri = rings.iterator()
        while (ri.hasNext()) {
            val g = ri.next()
            val t = (now - g.t0) / 650f
            if (t >= 1f) {
                ri.remove()
                continue
            }
            sp.color = Gfx.withAlpha(g.color, ((1f - t) * 220).toInt())
            sp.strokeWidth = dp(5f) * (1f - t) + 1f
            c.drawCircle(g.x, g.y, s * (0.3f + 2.2f * t), sp)
        }
        // particles
        val it = particles.iterator()
        while (it.hasNext()) {
            val q = it.next()
            q.life -= dt
            if (q.life <= 0f || q.y > height + 50f) {
                it.remove()
                continue
            }
            q.vy += q.grav * dt
            q.x += q.vx * dt
            q.y += q.vy * dt
            q.rot += q.vr * dt
            val a = min(1f, q.life / (q.maxLife * 0.5f))
            p.color = Gfx.withAlpha(q.color, (255 * a).toInt())
            when (q.kind) {
                0 -> c.drawCircle(q.x, q.y, q.size, p)
                1 -> {
                    Gfx.star(q.x, q.y, q.size * 1.6f, q.size * 0.7f, path)
                    c.drawPath(path, p)
                }
                else -> {
                    c.save()
                    c.rotate(q.rot, q.x, q.y)
                    c.drawRect(q.x - q.size, q.y - q.size * 0.5f, q.x + q.size, q.y + q.size * 0.5f, p)
                    c.restore()
                }
            }
        }
        // pop-up words
        val pi = popups.iterator()
        while (pi.hasNext()) {
            val g = pi.next()
            val t = (now - g.t0).toFloat() / g.dur
            if (t >= 1f) {
                pi.remove()
                continue
            }
            val pop = if (t < 0.18f) 0.4f + 1.0f * (t / 0.18f) else if (t < 0.3f) 1.4f - 0.4f * ((t - 0.18f) / 0.12f) else 1f
            val alpha = if (t > 0.65f) ((1f - t) / 0.35f) else 1f
            val y = g.y - t * dp(46f)
            val size = dp(30f) * pop
            tpo.textSize = size
            tpo.strokeWidth = size * 0.18f
            tpo.color = Gfx.withAlpha(0xFF1B0F3F.toInt(), (255 * alpha).toInt())
            c.drawText(g.text, g.x, y, tpo)
            tp.textSize = size
            tp.color = Gfx.withAlpha(g.color, (255 * alpha).toInt())
            c.drawText(g.text, g.x, y, tp)
        }
    }

    private fun drawTopBar(c: Canvas) {
        iconButton(c, homeBtn, 0)
        iconButton(c, soundBtn, if (sfx.enabled) 1 else 2)
        tp.textSize = dp(15f)
        tpo.textSize = dp(15f)
        tpo.strokeWidth = dp(3f)
        tpo.color = 0xCC1B0F3F.toInt()
        val x = width / 2f
        val y = homeBtn.centerY() + dp(5f)
        c.drawText(message, x, y, tpo)
        tp.color = Color.WHITE
        c.drawText(message, x, y, tp)
    }

    /** kind 0 = home, 1 = sound on, 2 = sound off */
    private fun iconButton(c: Canvas, r: RectF, kind: Int) {
        val cx = r.centerX()
        val cy = r.centerY()
        val h = r.width() / 2f
        p.shader = LinearGradient(cx, r.top, cx, r.bottom, 0xCC6A4BC4.toInt(), 0xCC2A1666.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, h, p)
        p.shader = null
        sp.color = 0xAAFFFFFF.toInt()
        sp.strokeWidth = dp(1.5f)
        c.drawCircle(cx, cy, h, sp)
        p.color = Color.WHITE
        val u = h * 0.5f
        if (kind == 0) {
            path.reset()
            path.moveTo(cx, cy - u * 1.1f)
            path.lineTo(cx - u * 1.2f, cy - u * 0.05f)
            path.lineTo(cx + u * 1.2f, cy - u * 0.05f)
            path.close()
            c.drawPath(path, p)
            c.drawRect(cx - u * 0.8f, cy - u * 0.05f, cx + u * 0.8f, cy + u * 1.0f, p)
            p.color = 0xFF4527A0.toInt()
            c.drawRect(cx - u * 0.22f, cy + u * 0.35f, cx + u * 0.22f, cy + u * 1.0f, p)
        } else {
            path.reset()
            path.moveTo(cx - u * 1.0f, cy - u * 0.4f)
            path.lineTo(cx - u * 0.4f, cy - u * 0.4f)
            path.lineTo(cx + u * 0.25f, cy - u * 1.0f)
            path.lineTo(cx + u * 0.25f, cy + u * 1.0f)
            path.lineTo(cx - u * 0.4f, cy + u * 0.4f)
            path.lineTo(cx - u * 1.0f, cy + u * 0.4f)
            path.close()
            c.drawPath(path, p)
            sp.color = Color.WHITE
            sp.strokeWidth = dp(2f)
            if (kind == 1) {
                rect.set(cx - u * 0.2f, cy - u * 0.6f, cx + u * 0.8f, cy + u * 0.6f)
                c.drawArc(rect, -50f, 100f, false, sp)
                rect.set(cx - u * 0.45f, cy - u * 1.0f, cx + u * 1.3f, cy + u * 1.0f)
                c.drawArc(rect, -50f, 100f, false, sp)
            } else {
                sp.color = 0xFFFF5252.toInt()
                sp.strokeWidth = dp(3f)
                c.drawLine(cx - u * 1.1f, cy - u * 1.1f, cx + u * 1.1f, cy + u * 1.1f, sp)
            }
        }
    }

    private fun drawWinner(c: Canvas, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = 0xB0100620.toInt()
        c.drawRect(0f, 0f, w, h, p)
        val col = Seats.COLORS[game.winner]
        val pulse = (sin(now / 200.0) * 0.5 + 0.5).toFloat()

        // spinning gold star trophy
        val cy = h * 0.30f
        c.save()
        c.rotate((now / 25 % 360).toFloat(), w / 2f, cy)
        Gfx.star(w / 2f, cy, dp(80f) + dp(8f) * pulse, dp(36f), path)
        p.shader = LinearGradient(w / 2f, cy - dp(80f), w / 2f, cy + dp(80f), 0xFFFFF59D.toInt(), 0xFFFF8F00.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        sp.color = 0xFFFFFFFF.toInt()
        sp.strokeWidth = dp(3f)
        c.drawPath(path, sp)
        c.restore()

        val big = dp(40f)
        tpo.textSize = big
        tpo.strokeWidth = big * 0.2f
        tpo.color = 0xFF1B0F3F.toInt()
        c.drawText(Seats.NAMES[game.winner].uppercase() + " WINS!", w / 2f, h * 0.46f, tpo)
        tp.textSize = big
        tp.color = Gfx.lighten(col, 0.3f)
        c.drawText(Seats.NAMES[game.winner].uppercase() + " WINS!", w / 2f, h * 0.46f, tp)

        val bw = min(dp(260f), w - dp(60f))
        val bh = dp(56f)
        playBtn.set((w - bw) / 2f, h * 0.55f, (w + bw) / 2f, h * 0.55f + bh)
        menuBtn.set((w - bw) / 2f, h * 0.55f + bh + dp(16f), (w + bw) / 2f, h * 0.55f + bh * 2 + dp(16f))
        bigButton(c, playBtn, 0xFF43A047.toInt(), "PLAY AGAIN")
        bigButton(c, menuBtn, 0xFF5E35B1.toInt(), "MAIN MENU")

        // keep the party going
        if (Random.nextInt(3) == 0) confetti(Random.nextFloat() * w, -dp(10f), 2, false)
    }

    private fun bigButton(c: Canvas, r: RectF, color: Int, label: String) {
        val rr = r.height() * 0.3f
        p.color = Gfx.darken(color, 0.45f)
        rect.set(r.left, r.top + dp(5f), r.right, r.bottom + dp(5f))
        c.drawRoundRect(rect, rr, rr, p)
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom, Gfx.lighten(color, 0.3f), Gfx.darken(color, 0.1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rr, rr, p)
        p.shader = null
        p.color = 0x44FFFFFF
        rect.set(r.left + dp(4f), r.top + dp(3f), r.right - dp(4f), r.centerY())
        c.drawRoundRect(rect, rr, rr, p)
        tp.textSize = dp(20f)
        tp.color = Color.WHITE
        c.drawText(label, r.centerX(), r.centerY() + dp(7f), tp)
    }
}
