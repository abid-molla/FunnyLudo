package com.funny.ludo

import kotlin.random.Random

/** What the menu hands to the game. humans[i] belongs to the i-th player (not seat). */
class GameConfig(val players: Int, val humans: BooleanArray, val tokens: Int)

class MoveResult(
    val player: Int,
    val piece: Int,
    val from: Int,
    val to: Int,
    val captured: List<Int>,
    val finished: Boolean,
    val landedSafe: Boolean,
    val extraTurn: Boolean
)

/**
 * Pure Ludo rules. Piece id = seat * 4 + n (n < tokens).
 * pos: -1 = in yard, 0..trackMax = on the shared track (relative to the owner's start),
 * trackMax+1 .. trackMax+5 = home column, finish = reached the centre.
 * Only seats flagged in `active` take part; the others are simply empty (no bots are added).
 */
class Game(val arms: Int, val active: BooleanArray, val isHuman: BooleanArray, val tokens: Int) {
    val trackLen = 13 * arms
    val trackMax = trackLen - 2
    val finish = trackMax + 6
    val pos = IntArray(arms * 4) { -1 }

    var turn = active.indexOfFirst { it }
        private set
    var dice = 0
        private set
    var sixes = 0
        private set
    var winner = -1
        private set

    val threeSixes get() = sixes >= 3

    fun absCell(seat: Int, rel: Int) = (13 * seat + rel) % trackLen

    /** start cells (index % 13 == 0) and star cells (index % 13 == 8) are safe */
    fun isSafe(abs: Int) = abs % 13 == 0 || abs % 13 == 8

    fun finishedCount(seat: Int) = (0 until tokens).count { pos[seat * 4 + it] == finish }

    fun rollDice(): Int {
        dice = Random.nextInt(1, 7)
        sixes = if (dice == 6) sixes + 1 else 0
        return dice
    }

    fun canMove(piece: Int): Boolean {
        val r = pos[piece]
        return if (r == -1) dice == 6 else r + dice <= finish
    }

    fun movablePieces(): List<Int> = (0 until tokens).map { turn * 4 + it }.filter { canMove(it) }

    fun endTurn() {
        sixes = 0
        do {
            turn = (turn + 1) % arms
        } while (!active[turn])
    }

    private fun enemiesAt(seat: Int, cell: Int): Int {
        var c = 0
        for (q in 0 until arms) if (q != seat && active[q]) for (j in 0 until tokens) {
            val r = pos[q * 4 + j]
            if (r in 0..trackMax && absCell(q, r) == cell) c++
        }
        return c
    }

    private fun threatened(seat: Int, cell: Int): Boolean {
        for (q in 0 until arms) if (q != seat && active[q]) for (j in 0 until tokens) {
            val r = pos[q * 4 + j]
            if (r in 0..trackMax) {
                val dist = (cell - absCell(q, r) + trackLen) % trackLen
                if (dist in 1..6) return true
            }
        }
        return false
    }

    /** simple bot brain: finish > cut > leave yard > reach a safe cell > run from danger > advance */
    fun botPick(options: List<Int>): Int {
        var best = options[0]
        var bestScore = Double.NEGATIVE_INFINITY
        for (pc in options) {
            val seat = pc / 4
            val r = pos[pc]
            val nr = if (r == -1) 0 else r + dice
            var s = nr * 0.5 + Random.nextDouble() * 3
            if (r == -1) s += 40
            if (nr == finish) s += 100
            if (nr in 0..trackMax) {
                val a = absCell(seat, nr)
                if (!isSafe(a) && enemiesAt(seat, a) > 0) s += 80
                if (isSafe(a)) s += 15 else if (threatened(seat, a)) s -= 20
            }
            if (r in 0..trackMax) {
                val here = absCell(seat, r)
                if (!isSafe(here) && threatened(seat, here)) s += 25
            }
            if (s > bestScore) {
                bestScore = s
                best = pc
            }
        }
        return best
    }

    fun apply(piece: Int): MoveResult {
        val seat = piece / 4
        val from = pos[piece]
        val to = if (from == -1) 0 else from + dice
        pos[piece] = to

        val captured = mutableListOf<Int>()
        var landedSafe = false
        if (to in 0..trackMax) {
            val a = absCell(seat, to)
            if (isSafe(a)) {
                landedSafe = from != -1
            } else {
                for (q in 0 until arms) if (q != seat && active[q]) for (j in 0 until tokens) {
                    val pc = q * 4 + j
                    val r = pos[pc]
                    if (r in 0..trackMax && absCell(q, r) == a) {
                        pos[pc] = -1
                        captured.add(pc)
                    }
                }
            }
        }
        val finished = to == finish
        if (finished && (0 until tokens).all { pos[seat * 4 + it] == finish }) winner = seat

        val extra = dice == 6 || captured.isNotEmpty() || finished
        if (!extra && winner < 0) endTurn()
        return MoveResult(seat, piece, from, to, captured, finished, landedSafe, extra)
    }
}
