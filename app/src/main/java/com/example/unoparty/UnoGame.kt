package com.example.unoparty

import org.json.JSONArray
import org.json.JSONObject

enum class UnoColor { RED, YELLOW, GREEN, BLUE, WILD }
enum class Kind { NUMBER, SKIP, REVERSE, DRAW_TWO, WILD, WILD_DRAW_FOUR }

data class Card(val color: UnoColor, val kind: Kind, val number: Int = -1) {
    val points: Int
        get() = when (kind) {
            Kind.NUMBER -> number
            Kind.SKIP, Kind.REVERSE, Kind.DRAW_TWO -> 20
            else -> 50
        }

    val label: String
        get() = when (kind) {
            Kind.NUMBER -> number.toString()
            Kind.SKIP -> "⊘"
            Kind.REVERSE -> "⇄"
            Kind.DRAW_TWO -> "+2"
            Kind.WILD -> "W"
            Kind.WILD_DRAW_FOUR -> "+4"
        }

    fun encode() = "${color.ordinal},${kind.ordinal},$number"

    companion object {
        fun decode(s: String): Card {
            val p = s.split(",")
            return Card(UnoColor.values()[p[0].toInt()], Kind.values()[p[1].toInt()], p[2].toInt())
        }
    }
}

/** Standard 108-card UNO deck. */
fun buildDeck(): MutableList<Card> {
    val d = mutableListOf<Card>()
    for (c in listOf(UnoColor.RED, UnoColor.YELLOW, UnoColor.GREEN, UnoColor.BLUE)) {
        d += Card(c, Kind.NUMBER, 0)
        for (n in 1..9) repeat(2) { d += Card(c, Kind.NUMBER, n) }
        repeat(2) {
            d += Card(c, Kind.SKIP)
            d += Card(c, Kind.REVERSE)
            d += Card(c, Kind.DRAW_TWO)
        }
    }
    repeat(4) {
        d += Card(UnoColor.WILD, Kind.WILD)
        d += Card(UnoColor.WILD, Kind.WILD_DRAW_FOUR)
    }
    return d
}

class Challenge(val by: Int, val target: Int, val hadColor: Boolean)

/**
 * Runs on the HOST phone only. Clients send actions, host validates and broadcasts state.
 * Player 0 is the host.
 */
class UnoGame(val names: List<String>) {
    val n = names.size
    val hands = List(n) { mutableListOf<Card>() }
    private val draw = ArrayDeque<Card>()
    private val discard = mutableListOf<Card>()
    var current = 0
    var dir = 1
    var color = UnoColor.RED
    var winner = -1
    var drawnCard: Card? = null
    var pendingChallenge: Challenge? = null
    private val unoCalled = BooleanArray(n)
    var vulnerable = -1
    val scores = IntArray(n)
    var log = ""

    private fun next(from: Int, steps: Int = 1) = ((from + dir * steps) % n + n) % n

    fun startRound() {
        hands.forEach { it.clear() }
        draw.clear(); discard.clear()
        draw.addAll(buildDeck().shuffled())
        repeat(7) { hands.forEach { h -> h.add(draw.removeFirst()) } }
        var first = draw.removeFirst()
        while (first.kind == Kind.WILD_DRAW_FOUR) { // not allowed as starter: reshuffle
            draw.addLast(first)
            val mixed = draw.shuffled(); draw.clear(); draw.addAll(mixed)
            first = draw.removeFirst()
        }
        discard.add(first)
        color = if (first.color == UnoColor.WILD)
            listOf(UnoColor.RED, UnoColor.YELLOW, UnoColor.GREEN, UnoColor.BLUE).random()
        else first.color
        dir = 1; current = 0; winner = -1
        drawnCard = null; pendingChallenge = null; vulnerable = -1
        unoCalled.fill(false)
        when (first.kind) {
            Kind.SKIP -> current = next(0)
            Kind.REVERSE -> { dir = -1; current = n - 1 }
            Kind.DRAW_TWO -> { drawCards(0, 2); current = next(0) }
            else -> {}
        }
        log = "New round!"
    }

    private fun refill() {
        if (draw.isNotEmpty() || discard.size <= 1) return
        val top = discard.removeAt(discard.lastIndex)
        draw.addAll(discard.shuffled())
        discard.clear(); discard.add(top)
    }

    private fun drawCards(p: Int, k: Int) {
        repeat(k) {
            refill()
            if (draw.isNotEmpty()) hands[p].add(draw.removeFirst())
        }
        unoCalled[p] = false
    }

    fun canPlay(c: Card): Boolean {
        val top = discard.last()
        return when (c.kind) {
            Kind.WILD, Kind.WILD_DRAW_FOUR -> true // +4 bluff is allowed, but can be challenged
            Kind.NUMBER -> c.color == color || (top.kind == Kind.NUMBER && top.number == c.number)
            else -> c.color == color || top.kind == c.kind
        }
    }

    /** Official rule: +4 only when you hold no card of the current colour. */
    private fun strictlyLegal(c: Card, hand: List<Card>) =
        if (c.kind == Kind.WILD_DRAW_FOUR) hand.none { it.color == color } else canPlay(c)

    fun matchWinner() = scores.indexOfFirst { it >= 500 }

    fun play(p: Int, idx: Int, chosen: UnoColor?): String? {
        if (winner >= 0 || pendingChallenge != null || p != current) return "Not your turn"
        val card = hands[p].getOrNull(idx) ?: return "No such card"
        if (drawnCard != null && card !== drawnCard) return "Play the drawn card or pass"
        if (!canPlay(card)) return "That card doesn't match"
        if (card.color == UnoColor.WILD && (chosen == null || chosen == UnoColor.WILD)) return "Choose a colour"

        val hadColor = hands[p].any { it !== card && it.color == color }
        hands[p].removeAt(idx)
        discard.add(card)
        color = if (card.color == UnoColor.WILD) chosen!! else card.color
        drawnCard = null
        vulnerable = if (hands[p].size == 1 && !unoCalled[p]) p else -1
        if (hands[p].size > 1) unoCalled[p] = false
        log = "${names[p]} played ${card.label}"

        val win = hands[p].isEmpty()
        when (card.kind) {
            Kind.NUMBER, Kind.WILD -> current = next(p)
            Kind.SKIP -> current = next(p, 2)
            Kind.REVERSE -> { dir = -dir; current = if (n == 2) p else next(p) }
            Kind.DRAW_TWO -> { drawCards(next(p), 2); current = next(p, 2) }
            Kind.WILD_DRAW_FOUR ->
                if (win) drawCards(next(p), 4)
                else { pendingChallenge = Challenge(next(p), p, hadColor); current = next(p) }
        }
        if (win) {
            winner = p
            scores[p] += hands.filterIndexed { i, _ -> i != p }.sumOf { h -> h.sumOf { it.points } }
            log = "${names[p]} wins the round!"
        }
        return null
    }

    fun drawOne(p: Int): String? {
        if (winner >= 0 || pendingChallenge != null || p != current) return "Not your turn"
        if (drawnCard != null) return "You already drew"
        if (hands[p].any { strictlyLegal(it, hands[p]) }) return "You have a playable card"
        vulnerable = -1
        refill()
        if (draw.isEmpty()) { current = next(p); return null }
        val c = draw.removeFirst()
        hands[p].add(c); unoCalled[p] = false
        log = "${names[p]} drew a card"
        if (canPlay(c)) drawnCard = c else current = next(p)
        return null
    }

    fun pass(p: Int): String? {
        if (winner >= 0 || pendingChallenge != null || p != current) return "Not your turn"
        if (drawnCard == null) return "Draw a card first"
        vulnerable = -1
        drawnCard = null
        current = next(p)
        log = "${names[p]} kept the card"
        return null
    }

    fun resolveChallenge(p: Int, challenge: Boolean): String? {
        val ch = pendingChallenge ?: return "Nothing to challenge"
        if (p != ch.by) return "Not your decision"
        pendingChallenge = null
        if (challenge) {
            if (ch.hadColor) {
                drawCards(ch.target, 4); current = ch.by
                log = "Challenge won! ${names[ch.target]} draws 4"
            } else {
                drawCards(ch.by, 6); current = next(ch.by)
                log = "Challenge failed! ${names[ch.by]} draws 6"
            }
        } else {
            drawCards(ch.by, 4); current = next(ch.by)
            log = "${names[ch.by]} draws 4"
        }
        return null
    }

    fun callUno(p: Int) {
        if (hands[p].size <= 2) unoCalled[p] = true
        if (vulnerable == p) vulnerable = -1
    }

    fun catchUno(by: Int): Boolean {
        val v = vulnerable
        if (v < 0 || v == by) return false
        drawCards(v, 2)
        vulnerable = -1
        log = "${names[by]} caught ${names[v]} without UNO! +2"
        return true
    }

    /** What one player is allowed to see (own hand only). */
    fun snapshot(p: Int, err: String?): JSONObject = JSONObject().apply {
        put("t", "state"); put("me", p)
        put("hand", JSONArray(hands[p].map { it.encode() }))
        put("top", discard.last().encode()); put("color", color.ordinal)
        put("cur", current); put("dir", dir); put("win", winner)
        put("names", JSONArray(names)); put("counts", JSONArray(hands.map { it.size }))
        put("scores", JSONArray(scores.toList()))
        put("drawn", drawnCard != null && current == p)
        put("chal", pendingChallenge?.by == p)
        put("vuln", vulnerable); put("log", log)
        err?.let { put("err", it) }
    }
}

data class GameView(
    val me: Int, val hand: List<Card>, val top: Card, val color: UnoColor,
    val cur: Int, val dir: Int, val win: Int, val names: List<String>,
    val counts: List<Int>, val scores: List<Int>, val drawn: Boolean,
    val chal: Boolean, val vuln: Int, val log: String, val err: String?
) {
    companion object {
        private fun ints(a: JSONArray) = List(a.length()) { a.getInt(it) }
        private fun strs(a: JSONArray) = List(a.length()) { a.getString(it) }
        fun parse(j: JSONObject) = GameView(
            me = j.getInt("me"),
            hand = strs(j.getJSONArray("hand")).map { Card.decode(it) },
            top = Card.decode(j.getString("top")),
            color = UnoColor.values()[j.getInt("color")],
            cur = j.getInt("cur"), dir = j.getInt("dir"), win = j.getInt("win"),
            names = strs(j.getJSONArray("names")),
            counts = ints(j.getJSONArray("counts")),
            scores = ints(j.getJSONArray("scores")),
            drawn = j.getBoolean("drawn"), chal = j.getBoolean("chal"),
            vuln = j.getInt("vuln"), log = j.optString("log"),
            err = if (j.has("err")) j.getString("err") else null
        )
    }
}
