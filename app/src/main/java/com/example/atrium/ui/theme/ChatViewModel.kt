package com.example.atrium.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import com.example.atrium.network.HistoryEntry
import com.example.atrium.network.HubApiClient
import com.example.atrium.network.HubApiListener
import com.example.atrium.network.HubHistoryClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ChatMessage(val sender: String, val text: String, val isUser: Boolean, val animateIn: Boolean = true)

enum class ConnectionStatus { CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING, ERROR }

private const val ROOM_KEY = "room"

class ChatViewModel(
    serverWsUrl: String,
    serverHttpUrl: String,
    authToken: String?,
) : ViewModel() {

    val messages = mutableStateListOf<ChatMessage>()
    val roomMessages = mutableStateListOf<ChatMessage>()
    val historyEntries = mutableStateListOf<HistoryEntry>()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTING)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _activePersona = MutableStateFlow("calista")
    val activePersona: StateFlow<String> = _activePersona

    private val _isRoomMode = MutableStateFlow(false)
    val isRoomMode: StateFlow<Boolean> = _isRoomMode

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val historyClient = HubHistoryClient(serverHttpUrl)

    // Keyed by "calista" / "mary" / "room". Showing the cached copy instantly
    // on switch — then quietly reconciling from the network — is what fixes
    // the "switching feels laggy" complaint: previously the view went blank
    // for the full round-trip before repopulating. The first time a key is
    // seen there's nothing cached yet, so it's a normal network wait same as
    // before; every switch after that feels instant.
    private val historyCache = mutableMapOf<String, List<ChatMessage>>()

    private val hubClient = HubApiClient(
        serverUrl = serverWsUrl,
        authToken = authToken,
        listener = object : HubApiListener {
            override fun onConnected() {
                _connectionStatus.value = ConnectionStatus.CONNECTED
            }

            override fun onDisconnected() {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }

            override fun onReconnecting(attempt: Int) {
                _connectionStatus.value = ConnectionStatus.RECONNECTING
            }

            override fun onPersonaSwitched(persona: String) {
                _activePersona.value = persona
                loadChatFromHistory(persona)
            }

            override fun onReply(persona: String, text: String) {
                // room_message can produce several replies per send (the
                // triangle cascade) — all of them arrive as this same
                // message type, so route by mode rather than by persona.
                val target = if (_isRoomMode.value) roomMessages else messages
                target.add(ChatMessage(sender = persona, text = text, isUser = false))
            }

            override fun onError(message: String) {
                _connectionStatus.value = ConnectionStatus.ERROR
                _errorMessage.value = message
            }
        },
    )

    init {
        hubClient.connect()
        loadChatFromHistory(_activePersona.value)
    }

    fun switchPersona(persona: String) = hubClient.switchPersona(persona)

    /**
     * Shared by both the 1:1 persona reload and Room's reload. Shows the
     * cached copy for `key` immediately if one exists (no blank flash),
     * then fetches fresh from /hub/history and reconciles — but only
     * applies the fresh result if `stillActive()` is still true by the time
     * it comes back, which guards against a fast double-switch (e.g.
     * Calista -> Mary -> Calista before the first fetch even returns)
     * writing stale data into the wrong list.
     */
    private fun hydrateFromHistory(key: String, target: SnapshotStateList<ChatMessage>, stillActive: () -> Boolean) {
        historyCache[key]?.let { cached ->
            target.clear()
            target.addAll(cached)
        }
        historyClient.fetchHistory(key) { entries ->
            if (entries != null) {
                val converted = entries.map { entry ->
                    ChatMessage(
                        sender = entry.role,
                        text = entry.content,
                        isUser = entry.role == "viper",
                        animateIn = false, // bulk history shouldn't replay entrance animations
                    )
                }
                historyCache[key] = converted
                if (stillActive()) {
                    target.clear()
                    target.addAll(converted)
                }
            }
        }
    }

    private fun loadChatFromHistory(persona: String) =
        hydrateFromHistory(persona, messages) { _activePersona.value == persona && !_isRoomMode.value }

    fun enterRoomMode() {
        _isRoomMode.value = true
        hydrateFromHistory(ROOM_KEY, roomMessages) { _isRoomMode.value }
    }

    fun exitRoomMode() {
        _isRoomMode.value = false
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        messages.add(ChatMessage(sender = "viper", text = text, isUser = true))
        hubClient.sendChat(text)
    }

    fun sendRoomMessage(text: String) {
        if (text.isBlank()) return
        roomMessages.add(ChatMessage(sender = "viper", text = text, isUser = true))
        hubClient.sendRoomMessage(text)
    }

    fun loadHistory(persona: String = _activePersona.value) {
        historyClient.fetchHistory(persona) { entries ->
            if (entries != null) {
                historyEntries.clear()
                historyEntries.addAll(entries)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        hubClient.disconnect()
    }
}