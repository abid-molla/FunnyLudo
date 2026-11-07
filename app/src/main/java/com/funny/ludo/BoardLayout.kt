package com.funny.ludo

import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry of the board in "board units" (one track cell = 1 x 1).
 *
 * arms = 4 -> the classic 15x15 cross board (2-4 players).
 * arms = 6 -> a six-armed star board (5-6 players).
 *
 * Seat k owns arm k. Seats run clockwise on screen: arm 0 points West, then North-West/North ...
 * Every arm contributes 13 track cells, so the shared loop has 13 * arms cells.
 * A seat starts on track index 13*k and travels trackLen-1 cells before turning into its own home column.
 */
class BoardLayout(val arms: Int) {
    val trackLen = 13 * arms
    val r0 = if (arms == 4) 2f else 2.8f
    val armAngle = FloatArray(arms) { (PI + it * 2 * PI / arms).toFloat() }   // radians, clockwise on screen

    val track: Array<PointF>
    val trackAngle: FloatArray            // orientation of each track cell (the arm it sits in)
    val home: Array<Array<PointF>>        // [seat][5] home-column cells, outer to inner
    val yardCenter: Array<PointF>
    val yardSpots: Array<Array<PointF>>   // [seat][4]
    val finishSpots: Array<Array<PointF>> // [seat][4] where finished pieces rest
    val yardHalf: Float                   // half-size of a square yard, radius of a round one
    val centerR: Float                    // radius of the coloured centre polygon corners
    val size: Float                       // board is size x size units
    val round: Boolean get() = arms != 4
    val center: PointF

    /** point at distance d along the arm axis, lateral offset l (clockwise side positive), relative to the centre */
    private fun rel(a: Float, d: Float, l: Float): PointF {
        val ux = cos(a)
        val uy = sin(a)
        return PointF(d * ux - l * uy, d * uy + l * ux)
    }

    init {
        val tr = ArrayList<PointF>()
        val ta = ArrayList<Float>()
        for (k in 0 until arms) {
            val a = armAngle[k]
            val b = armAngle[(k + 1) % arms]
            for (j in 4 downTo 0) { tr.add(rel(a, r0 + j, 1f)); ta.add(a) }     // inward along own arm
            for (j in 0..5) { tr.add(rel(b, r0 + j, -1f)); ta.add(b) }          // out along the next arm
            tr.add(rel(b, r0 + 5, 0f)); ta.add(b)                              // tip
            tr.add(rel(b, r0 + 5, 1f)); ta.add(b)
        }
        check(tr.size == trackLen)

        val ryD = if (arms == 4) 4.5f * sqrt(2f) else 8.0f
        yardHalf = if (arms == 4) 3f else 2.2f
        val so = if (arms == 4) 1f else 0.8f
        size = if (arms == 4) 15f else 2f * (ryD + yardHalf + 0.35f)
        center = PointF(size / 2f, size / 2f)
        fun at(p: PointF) = PointF(p.x + center.x, p.y + center.y)

        track = Array(trackLen) { at(tr[it]) }
        trackAngle = ta.toFloatArray()
        home = Array(arms) { k -> Array(5) { j -> at(rel(armAngle[k], r0 + 4 - j, 0f)) } }
        yardCenter = Array(arms) { k ->
            val ang = armAngle[k] + (PI / arms).toFloat()
            at(PointF(ryD * cos(ang), ryD * sin(ang)))
        }
        yardSpots = Array(arms) { k ->
            val c = yardCenter[k]
            arrayOf(PointF(c.x - so, c.y - so), PointF(c.x + so, c.y - so),
                PointF(c.x - so, c.y + so), PointF(c.x + so, c.y + so))
        }
        val dd = (r0 - 0.5f) * 0.55f
        finishSpots = Array(arms) { k -> Array(4) { i -> at(rel(armAngle[k], dd, (i - 1.5f) * 0.36f)) } }
        centerR = ((r0 - 0.5f) / cos(PI / arms)).toFloat()
    }

    companion object {
        /** which board seats are used for a given number of players */
        fun seatsFor(players: Int): IntArray = when (players) {
            2 -> intArrayOf(0, 2)
            3 -> intArrayOf(0, 1, 2)
            4 -> intArrayOf(0, 1, 2, 3)
            5 -> intArrayOf(0, 1, 2, 3, 4)
            else -> intArrayOf(0, 1, 2, 3, 4, 5)
        }

        fun armsFor(players: Int) = if (players <= 4) 4 else 6
    }
}
