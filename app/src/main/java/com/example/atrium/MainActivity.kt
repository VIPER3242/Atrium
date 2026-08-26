package com.example.atrium

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.atrium.ui.ChatMessage
import com.example.atrium.ui.ChatViewModel
import com.example.atrium.ui.ConnectionStatus
import com.example.atrium.ui.theme.AtriumTheme

// TODO: replace with your actual bot-hosting.net host + exposed port.
// Use ws:// / http:// for now (cleartext, see AndroidManifest.xml note below);
// switch to wss:// / https:// if bot-hosting.net ever serves TLS on this port.
private const val SERVER_WS_URL = "ws://95.216.12.48:25052/hub/ws"
private const val SERVER_HTTP_URL = "http://95.216.12.48:25052"
private val AUTH_TOKEN: String? = "dvZYA!qJz#V8NeGSs8izVf"

/**
 * --- Atrium palette ---
 * Deliberately not Material-default blue/purple. Dark "atelier" base reflecting
 * the two personas' own look (silver-white hair, deep red eyes for Calista;
 * softer rose for Mary) rather than a generic app theme. Kept local to this file
 * since no Theme.kt/Color.kt was supplied to fold this into — move these into a
 * proper theme file whenever one exists.
 */
private object AtriumPalette {
    val background = Color(0xFF14121A)
    val surface = Color(0xFF1E1B24)
    val surfaceRaised = Color(0xFF262230)
    val divider = Color(0xFF332E3D)
    val textPrimary = Color(0xFFEDEAF0)
    val textMuted = Color(0xFF9A93A8)

    // Calista — deep red, silver-white
    val calista = Color(0xFFB33A4A)
    val calistaTint = Color(0xFF3A2229)

    // Mary — softer rose
    val mary = Color(0xFFD98A9D)
    val maryTint = Color(0xFF3A2630)

    // Room — neutral, belongs to neither sister individually
    val roomAccent = Color(0xFFB8A9D9)

    // Viper's own bubble — neutral, doesn't borrow either persona's accent
    val viperBubble = Color(0xFF34303E)

    val connected = Color(0xFF6FCF97)
    val connecting = Color(0xFFE0B84B)
    val error = Color(0xFFD9636B)
}

private fun personaAccent(persona: String): Color =
    if (persona == "mary") AtriumPalette.mary else AtriumPalette.calista

private fun personaTint(persona: String): Color =
    if (persona == "mary") AtriumPalette.maryTint else AtriumPalette.calistaTint

// Generic serif for persona display names — distinctive against the sans body
// text without needing a bundled font resource (FontFamily.Serif is built in).
private val displayFont = FontFamily.Serif

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AtriumTheme {
                Surface(color = AtriumPalette.background, modifier = Modifier.fillMaxSize()) {
                    AtriumApp()
                }
            }
        }
    }
}

@Composable
fun AtriumApp() {
    val viewModel: ChatViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(SERVER_WS_URL, SERVER_HTTP_URL, AUTH_TOKEN) as T
            }
        },
    )

    val status by viewModel.connectionStatus.collectAsState()
    val persona by viewModel.activePersona.collectAsState()
    val isRoomMode by viewModel.isRoomMode.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().background(AtriumPalette.background)) {
        AtriumHeader(persona = persona, status = status, isRoomMode = isRoomMode)

        if (status == ConnectionStatus.ERROR && errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = AtriumPalette.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Persona switcher is meaningless while Room is active — everyone's
        // present at once — so it's hidden rather than left sitting there
        // implying a choice that doesn't do anything in that mode.
        if (!isRoomMode) {
            PersonaSwitcher(current = persona, onSwitch = { viewModel.switchPersona(it) })
        }

        AtriumTabRow(
            selectedTab = selectedTab,
            accent = if (isRoomMode) AtriumPalette.roomAccent else personaAccent(persona),
            onSelect = { index ->
                selectedTab = index
                if (index == 1) viewModel.loadHistory()
            },
        )

        when (selectedTab) {
            0 -> ChatScreen(viewModel, persona, isRoomMode)
            1 -> HistoryScreen(viewModel, persona)
        }
    }
}

@Composable
fun AtriumHeader(persona: String, status: ConnectionStatus, isRoomMode: Boolean) {
    val label = if (isRoomMode) "Room" else persona.replaceFirstChar { it.uppercase() }
    val accent = if (isRoomMode) AtriumPalette.roomAccent else personaAccent(persona)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // AnimatedContent here is deliberately scoped to just this one Text —
        // it's a cheap, isolated crossfade+slide, not something that touches
        // the message list, so it doesn't reintroduce the bulk-animation lag
        // that bit the chat bubbles.
        AnimatedContent(
            targetState = label to accent,
            transitionSpec = {
                (fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 3 }) togetherWith
                        (fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 3 })
            },
            label = "headerLabel",
        ) { (currentLabel, currentAccent) ->
            Text(
                text = currentLabel,
                fontFamily = displayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 26.sp,
                color = currentAccent,
            )
        }
        StatusDot(status)
    }
}

@Composable
fun StatusDot(status: ConnectionStatus) {
    val (targetColor, label) = when (status) {
        ConnectionStatus.CONNECTING -> AtriumPalette.connecting to "connecting"
        ConnectionStatus.CONNECTED -> AtriumPalette.connected to "connected"
        ConnectionStatus.DISCONNECTED -> AtriumPalette.textMuted to "offline"
        ConnectionStatus.RECONNECTING -> AtriumPalette.connecting to "reconnecting..."
        ConnectionStatus.ERROR -> AtriumPalette.error to "error"
    }
    val animatedColor by animateColorAsState(targetValue = targetColor, animationSpec = tween(300), label = "statusDot")

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(animatedColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, color = AtriumPalette.textMuted, fontSize = 12.sp)
    }
}

@Composable
fun PersonaSwitcher(current: String, onSwitch: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(AtriumPalette.surface)
            .padding(4.dp),
    ) {
        listOf("calista", "mary").forEach { p ->
            val selected = current == p
            val accent = personaAccent(p)
            val bg by animateColorAsState(
                targetValue = if (selected) accent else Color.Transparent,
                animationSpec = tween(250),
                label = "switcherBg",
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) AtriumPalette.background else AtriumPalette.textMuted,
                animationSpec = tween(250),
                label = "switcherText",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .clickable(enabled = !selected) { onSwitch(p) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = p.replaceFirstChar { it.uppercase() },
                    color = textColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun AtriumTabRow(selectedTab: Int, accent: Color, onSelect: (Int) -> Unit) {
    TabRow(
        selectedTabIndex = selectedTab,
        containerColor = AtriumPalette.background,
        contentColor = accent,
        divider = { HorizontalDivider(color = AtriumPalette.divider) },
    ) {
        Tab(
            selected = selectedTab == 0,
            onClick = { onSelect(0) },
            text = { Text("Chat", color = if (selectedTab == 0) accent else AtriumPalette.textMuted) },
        )
        Tab(
            selected = selectedTab == 1,
            onClick = { onSelect(1) },
            text = { Text("History", color = if (selectedTab == 1) accent else AtriumPalette.textMuted) },
        )
    }
}

@Composable
fun ChatScreen(viewModel: ChatViewModel, persona: String, isRoomMode: Boolean) {
    var input by remember { mutableStateOf("") }
    val accent = if (isRoomMode) AtriumPalette.roomAccent else personaAccent(persona)
    val listState = rememberLazyListState()
    val displayed = if (isRoomMode) viewModel.roomMessages else viewModel.messages

    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 2
        }
    }

    // imePadding() here (not on the whole screen) means only this column
    // reacts to the keyboard — header/switcher/tabs stay put, the message
    // list shrinks, and the input row rides up above the keyboard instead
    // of hiding behind it. Also needs android:windowSoftInputMode="adjustResize"
    // set on the activity in AndroidManifest.xml for this to behave
    // consistently across API levels — add it if it's not already there.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        RoomModeToggle(
            isRoomMode = isRoomMode,
            onToggle = { turnOn ->
                if (turnOn) viewModel.enterRoomMode() else viewModel.exitRoomMode()
            },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // index-based key is fine here since both lists are append-only —
            // nothing is ever reordered or removed mid-list.
            items(displayed.size, key = { it }) { index ->
                val msg = displayed[index]
                // In Room mode, color/label come from who actually sent the
                // message (msg.sender), not the outer 1:1 "active persona" —
                // three parties can appear in the same stream.
                AnimatedChatBubble(msg = msg, persona = if (isRoomMode) msg.sender else persona)
            }
        }

        LaunchedEffect(displayed.size) {
            if (displayed.isEmpty()) return@LaunchedEffect
            val lastMsg = displayed.last()
            when {
                // Bulk/history load (cache hydration or a fresh fetch) —
                // jump straight there, no animation. Animating a scroll
                // through dozens of simultaneously-appearing bubbles is
                // exactly what made switching feel laggy before.
                !lastMsg.animateIn -> listState.scrollToItem(displayed.size - 1)
                // A genuinely new live message, and the user is already at
                // (or near) the bottom — follow it smoothly. If they've
                // scrolled up to read older messages, leave them alone.
                isNearBottom -> listState.animateScrollToItem(displayed.size - 1)
            }
        }

        MessageInput(
            input = input,
            onInputChange = { input = it },
            accent = accent,
            onSend = {
                if (input.isNotBlank()) {
                    if (isRoomMode) viewModel.sendRoomMessage(input) else viewModel.sendMessage(input)
                    input = ""
                }
            },
        )
    }
}

@Composable
fun RoomModeToggle(isRoomMode: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (isRoomMode) "Everyone's here" else "1:1",
            color = if (isRoomMode) AtriumPalette.roomAccent else AtriumPalette.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Switch(
            checked = isRoomMode,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AtriumPalette.roomAccent,
                checkedTrackColor = AtriumPalette.roomAccent.copy(alpha = 0.4f),
                uncheckedThumbColor = AtriumPalette.textMuted,
                uncheckedTrackColor = AtriumPalette.surfaceRaised,
            ),
        )
    }
}

@Composable
fun AnimatedChatBubble(msg: ChatMessage, persona: String) {
    // Bulk-loaded history (animateIn = false) renders immediately with no
    // transition at all — this is the fix for the "switching feels laggy"
    // complaint. The old version animated every bubble's entrance
    // unconditionally, so reloading 50 history rows on a persona switch
    // meant 50 simultaneous fade+slide+expand animations fighting for frame
    // time. Only genuinely new, live-arrived messages animate in now.
    if (!msg.animateIn) {
        ChatBubble(msg = msg, persona = persona)
        return
    }
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visibleState,
        // Dropped expandVertically from the previous version — it forces a
        // layout pass every animation frame, which is the expensive part.
        // fade + translate (slideInVertically) achieve basically the same
        // felt effect for a lot less cost.
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 },
    ) {
        ChatBubble(msg = msg, persona = persona)
    }
}

@Composable
fun ChatBubble(msg: ChatMessage, persona: String) {
    val isUser = msg.isUser
    val accent = personaAccent(persona)
    val tint = personaTint(persona)
    val bubbleColor = if (isUser) AtriumPalette.viperBubble else tint
    val labelColor = if (isUser) AtriumPalette.textMuted else accent
    val label = if (isUser) "Viper" else persona.replaceFirstChar { it.uppercase() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    )
                )
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                color = labelColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = msg.text,
                color = AtriumPalette.textPrimary,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
fun MessageInput(input: String, onInputChange: (String) -> Unit, accent: Color, onSend: () -> Unit) {
    Surface(color = AtriumPalette.surface, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp)),
                placeholder = { Text("Say something...", color = AtriumPalette.textMuted) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AtriumPalette.surfaceRaised,
                    unfocusedContainerColor = AtriumPalette.surfaceRaised,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = AtriumPalette.textPrimary,
                    unfocusedTextColor = AtriumPalette.textPrimary,
                    cursorColor = accent,
                ),
                singleLine = true,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onSend,
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier.height(48.dp),
            ) {
                Text("Send", color = AtriumPalette.background, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun HistoryScreen(viewModel: ChatViewModel, persona: String) {
    val accent = personaAccent(persona)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(viewModel.historyEntries) { entry ->
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Text(
                    text = entry.role.replaceFirstChar { it.uppercase() },
                    color = if (entry.role == "viper") AtriumPalette.textMuted else accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = entry.content, color = AtriumPalette.textPrimary, fontSize = 14.sp)
            }
            HorizontalDivider(color = AtriumPalette.divider)
        }
    }
}