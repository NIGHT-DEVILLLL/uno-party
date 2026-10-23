package com.example.unoparty

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject

/**
 * Direct phone-to-phone links (Bluetooth / Wi-Fi Direct) using Nearby Connections.
 * No internet and no server. Host = star centre, everyone else connects to the host.
 */
class NearbyRoom(ctx: Context, private val myName: String, private val l: Listener) {

    interface Listener {
        fun onRoomFound(id: String, name: String)
        fun onRoomLost(id: String)
        fun onPeerJoined(id: String, name: String)   // host side
        fun onPeerLeft(id: String)
        fun onConnectedToHost(id: String)            // client side
        fun onMessage(fromId: String, msg: JSONObject)
    }

    private val client = Nearby.getConnectionsClient(ctx)
    private val strategy = Strategy.P2P_STAR
    private val names = mutableMapOf<String, String>()
    private var hosting = false

    fun host(roomName: String) {
        hosting = true
        client.startAdvertising(
            roomName, SERVICE_ID, conn,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        )
    }

    fun discover() {
        client.startDiscovery(
            SERVICE_ID, disc,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        )
    }

    fun join(id: String) { client.requestConnection(myName, id, conn) }
    fun closeRoom() { client.stopAdvertising() }
    fun disconnect(id: String) { client.disconnectFromEndpoint(id) }

    fun send(id: String, m: JSONObject) {
        client.sendPayload(id, Payload.fromBytes(m.toString().toByteArray()))
    }

    fun stop() {
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
    }

    private val disc = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) =
            l.onRoomFound(id, info.endpointName)
        override fun onEndpointLost(id: String) = l.onRoomLost(id)
    }

    private val pay = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            p.asBytes()?.let { l.onMessage(id, JSONObject(String(it))) }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }

    private val conn = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            names[id] = info.endpointName
            client.acceptConnection(id, pay)
        }

        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (!r.status.isSuccess) { names.remove(id); return }
            if (hosting) l.onPeerJoined(id, names[id] ?: "Player")
            else { client.stopDiscovery(); l.onConnectedToHost(id) }
        }

        override fun onDisconnected(id: String) {
            names.remove(id); l.onPeerLeft(id)
        }
    }

    companion object { const val SERVICE_ID = "com.example.unoparty.rooms" }
}
