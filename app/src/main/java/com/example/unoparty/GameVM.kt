package com.example.unoparty

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class Screen { LOADING, LOGIN, HOME, ROOM, GAME }

object Msg {
    fun play(i: Int, c: UnoColor? = null): JSONObject =
        JSONObject().put("t", "play").put("i", i).apply { c?.let { put("c", it.ordinal) } }
    fun draw(): JSONObject = JSONObject().put("t", "draw")
    fun pass(): JSONObject = JSONObject().put("t", "pass")
    fun uno(): JSONObject = JSONObject().put("t", "uno")
    fun catchUno(): JSONObject = JSONObject().put("t", "catch")
    fun challenge(v: Boolean): JSONObject = JSONObject().put("t", "challenge").put("v", v)
    fun next(): JSONObject = JSONObject().put("t", "next")
}

class GameVM(private val app: Application) : AndroidViewModel(app), NearbyRoom.Listener {

    private val db = Room.databaseBuilder(app, AppDb::class.java, "uno.db").build()

    var screen by mutableStateOf(Screen.LOADING)
    var user by mutableStateOf<User?>(null)
    var error by mutableStateOf<String?>(null)
    var permsOk by mutableStateOf(false)
    var isHost by mutableStateOf(false)
    var players by mutableStateOf(listOf<String>())
    var view by mutableStateOf<GameView?>(null)
    val rooms = mutableStateMapOf<String, String>()

    private var net: NearbyRoom? = null
    private var game: UnoGame? = null
    private val order = mutableListOf<String>()   // endpoint id per player index; "" = host
    private var hostId = ""
    private var statsSaved = false

    init {
        // Auto-detect who is using the phone: last signed-in user is logged in automatically.
        viewModelScope.launch {
            user = db.users().lastSignedIn()
            screen = if (user != null) Screen.HOME else Screen.LOGIN
        }
    }

    // ---------- Login (stored only on this phone) ----------

    private fun hash(name: String, pin: String) =
        MessageDigest.getInstance("SHA-256").digest("$name:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun login(name: String, pin: String) {
        viewModelScope.launch {
            val n = name.trim()
            if (n.length < 2 || pin.length != 4) { error = "Name (2+ letters) and 4-digit PIN needed"; return@launch }
            val h = hash(n, pin)
            val existing = db.users().get(n)
            val base = when {
                existing == null -> User(n, h)
                existing.pinHash == h -> existing
                else -> { error = "Wrong PIN"; return@launch }
            }
            val u = base.copy(lastLogin = System.currentTimeMillis())
            db.users().save(u)
            user = u; error = null; screen = Screen.HOME
        }
    }

    fun logout() {
        viewModelScope.launch {
            user?.let { db.users().save(it.copy(lastLogin = 0)) }
            shutdown(); user = null; screen = Screen.LOGIN
        }
    }

    // ---------- Rooms ----------

    fun permissionsReady() { permsOk = true }

    fun startScan() {
        val u = user ?: return
        if (!permsOk) return
        net?.stop()
        rooms.clear()
        net = NearbyRoom(app, u.name, this).also { it.discover() }
    }

    fun createRoom() {
        val u = user ?: return
        net?.stop()
        isHost = true
        net = NearbyRoom(app, u.name, this).also { it.host("${u.name}'s room") }
        order.clear(); order.add("")
        players = listOf(u.name)
        screen = Screen.ROOM
    }

    fun join(id: String) { net?.join(id) }

    fun leave() {
        if (isHost) broadcast(JSONObject().put("t", "closed"))
        shutdown()
        screen = Screen.HOME
        startScan()
    }

    private fun shutdown() {
        net?.stop(); net = null; game = null; view = null
        players = emptyList(); order.clear(); isHost = false; rooms.clear()
    }

    fun startGame() {
        if (!isHost || players.size < 2) return
        net?.closeRoom()
        game = UnoGame(players).also { it.startRound() }
        pushState()
    }

    // ---------- NearbyRoom.Listener ----------

    override fun onRoomFound(id: String, name: String) { rooms[id] = name }
    override fun onRoomLost(id: String) { rooms.remove(id) }

    override fun onPeerJoined(id: String, name: String) {
        if (!isHost) return
        if (game != null || order.size >= 10) { net?.disconnect(id); return }
        order.add(id); players = players + name
        broadcastLobby()
    }

    override fun onPeerLeft(id: String) {
        if (isHost) {
            val i = order.indexOf(id)
            if (i <= 0) return
            if (game == null) {
                order.removeAt(i)
                players = players.toMutableList().also { it.removeAt(i) }
                broadcastLobby()
            } else {
                error = "A player left, game ended"
                leave()
            }
        } else if (id == hostId) {
            error = "Host left the room"
            shutdown(); screen = Screen.HOME; startScan()
        }
    }

    override fun onConnectedToHost(id: String) {
        hostId = id; isHost = false; rooms.clear()
        players = emptyList(); screen = Screen.ROOM
    }

    override fun onMessage(fromId: String, msg: JSONObject) {
        if (isHost) {
            val p = order.indexOf(fromId)
            if (p > 0) hostAct(p, msg)
            return
        }
        when (msg.getString("t")) {
            "lobby" -> {
                val a = msg.getJSONArray("names")
                players = List(a.length()) { a.getString(it) }
                if (screen != Screen.GAME) screen = Screen.ROOM
            }
            "state" -> applyView(GameView.parse(msg))
            "closed" -> { shutdown(); screen = Screen.HOME; startScan() }
        }
    }

    // ---------- Actions ----------

    fun send(m: JSONObject) {
        if (isHost) hostAct(0, m) else net?.send(hostId, m)
    }

    private fun hostAct(p: Int, m: JSONObject) {
        val g = game ?: return
        val err: String? = when (m.getString("t")) {
            "play" -> g.play(p, m.getInt("i"), if (m.has("c")) UnoColor.values()[m.getInt("c")] else null)
            "draw" -> g.drawOne(p)
            "pass" -> g.pass(p)
            "uno" -> { g.callUno(p); null }
            "catch" -> if (g.catchUno(p)) null else "Nobody to catch"
            "challenge" -> g.resolveChallenge(p, m.getBoolean("v"))
            "next" ->
                if (p == 0 && g.winner >= 0) {
                    if (g.matchWinner() >= 0) g.scores.fill(0)
                    g.startRound(); null
                } else "Only the host can start the next round"
            else -> null
        }
        pushState(p, err)
    }

    private fun pushState(errPlayer: Int = -1, err: String? = null) {
        val g = game ?: return
        for (i in order.indices) {
            val j = g.snapshot(i, if (i == errPlayer) err else null)
            if (i == 0) applyView(GameView.parse(j)) else net?.send(order[i], j)
        }
    }

    private fun broadcastLobby() {
        broadcast(JSONObject().put("t", "lobby").put("names", JSONArray(players)))
    }

    private fun broadcast(m: JSONObject) {
        for (i in 1 until order.size) net?.send(order[i], m)
    }

    private fun applyView(v: GameView) {
        view = v; screen = Screen.GAME
        if (v.win < 0) { statsSaved = false; return }
        if (statsSaved) return
        statsSaved = true
        viewModelScope.launch {
            user?.let {
                val u = it.copy(played = it.played + 1, wins = it.wins + if (v.win == v.me) 1 else 0)
                db.users().save(u); user = u
            }
        }
    }
}
