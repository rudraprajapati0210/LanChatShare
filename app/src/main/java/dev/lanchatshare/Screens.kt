package dev.lanchatshare

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.lanchatshare.data.*
import dev.lanchatshare.design.brandFont

private val colours = listOf(0xff006457, 0xff813f32, 0xff415883, 0xff64466b)
private val destinations = listOf("Chats", "Nearby", "Library", "Settings")

@Composable private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.brand_mark), null, Modifier.size(48.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
        Text("LanChatShare", style = MaterialTheme.typography.titleMedium, fontFamily = brandFont)
    }
}

@Composable fun NameForm(profile: Profile?, busy: Boolean, failed: Boolean, save: (String, Int?) -> Unit) {
    var input by rememberSaveable(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var colour by rememberSaveable(profile?.id) { mutableIntStateOf(profile?.colour ?: 0) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val invalid = attempted && validatedName(input) == null
    val submit = {
        attempted = true
        if (!busy && validatedName(input) != null) save(input, if (profile == null) null else colour)
    }
    Column(Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (profile == null) {
            Wordmark()
            Spacer(Modifier.height(24.dp))
        }
        Text(if (profile == null) "What should we call you?" else "Profile",
            Modifier.semantics { heading() }, style = MaterialTheme.typography.displaySmall)
        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name") }, singleLine = true, isError = invalid,
            supportingText = { Text(if (invalid) "Enter a name with 1 to 24 characters." else "People nearby will see this name.") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
        if (profile != null) {
            Text("Avatar colour", style = MaterialTheme.typography.titleMedium)
            Row {
                colours.forEachIndexed { index, hex ->
                    val label = listOf("Teal", "Clay", "Slate blue", "Plum")[index]
                    IconButton(onClick = { colour = index }, modifier = Modifier.semantics {
                        contentDescription = label
                        selected = colour == index
                    }) {
                        Box(Modifier.size(32.dp).background(Color(hex), CircleShape), contentAlignment = Alignment.Center) {
                            if (colour == index) Text("✓", color = Color.White)
                        }
                    }
                }
            }
        }
        if (failed) Text("Your changes couldn’t be saved. Try again.", color = MaterialTheme.colorScheme.error)
        Button(onClick = submit, enabled = !busy && (profile == null || input != profile.name || colour != profile.colour)) {
            Text(if (profile == null) "Continue" else "Save changes")
        }
    }
}

@Composable fun Home(profile: Profile, busy: Boolean, failed: Boolean, trust: dev.lanchatshare.connectivity.TrustStore, chat: dev.lanchatshare.connectivity.LanChat, save: (String, Int?) -> Unit) {
    var section by rememberSaveable { mutableStateOf("Chats") }
    var detail by rememberSaveable { mutableStateOf("") }
    var sharing by rememberSaveable { mutableStateOf(false) }
    val trusted by trust.peers.collectAsStateWithLifecycle(emptyList())
    var peerId by rememberSaveable { mutableStateOf("") }
    val peer = trusted.find { it.id == peerId }
    val openChat: (dev.lanchatshare.connectivity.TrustedPeer) -> Unit = {
        section = "Chats"; detail = "Conversation"; peerId = it.id; sharing = false
    }
    val page = if (sharing) "Share files" else detail.ifEmpty { section }
    fun back() { if (sharing) sharing = false else if (detail.isNotEmpty()) detail = "" else section = "Chats" }
    BackHandler(sharing || detail.isNotEmpty() || section != "Chats") { back() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val select: (String) -> Unit = { section = it; detail = ""; sharing = false }
        Row {
            if (wide) NavigationRail { Destinations { name, icon, label -> NavigationRailItem(selected = section == name, onClick = { select(name) }, icon = icon, label = label) } }
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (sharing || detail.isNotEmpty()) TextButton(onClick = ::back) { Text("Back") }
                    Text(if (page == "Conversation" && peer != null) peerLabel(peer, trusted) else page, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    if (!sharing) FilledTonalButton(onClick = { sharing = true }) { Text("Share files") }
                }
                if (trusted.isNotEmpty()) ChatAccess()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (page) {
                        "Nearby" -> NearbyScreen(profile, trust, openChat)
                        "Conversation" -> peer?.let { Conversation(it, trust, chat) { section = "Settings"; detail = "Network and hotspot" } }
                        "Chats" -> ChatList(trusted, trust, chat, openChat) { select("Nearby") }
                        "Network and hotspot" -> NetworkSettings(chat)
                        "Profile" -> NameForm(profile, busy, failed, save)
                        "Settings" -> Column {
                            ListItem(headlineContent = { Text("Profile") }, supportingContent = { Text(profile.name) },
                                modifier = Modifier.clickable { detail = "Profile" })
                            ListItem(headlineContent = { Text("Network and hotspot") }, modifier = Modifier.clickable { detail = "Network and hotspot" })
                            ListItem(headlineContent = { Text("About") }, modifier = Modifier.clickable { detail = "About" })
                        }
                        "About" -> Column(Modifier.padding(24.dp)) { Wordmark(); Text("Local messaging and file sharing.") }
                        else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(when (page) {

                                "Library" -> "Files you send and receive will appear here."
                                else -> "File transfers are coming in milestone E."
                            })
                        }
                    }
                }
                if (!wide) NavigationBar { Destinations { name, icon, label -> NavigationBarItem(selected = section == name, onClick = { select(name) }, icon = icon, label = label) } }
            }
        }
    }
}

@Composable private fun Destinations(item: @Composable (String, @Composable () -> Unit, @Composable () -> Unit) -> Unit) {
    val marks = listOf(R.drawable.nav_chat, R.drawable.nav_nearby, R.drawable.nav_library, R.drawable.nav_settings)
    destinations.forEachIndexed { index, name ->
        val icon = @Composable { Icon(painterResource(marks[index]), null, Modifier.size(24.dp)) }
        val label = @Composable { Text(name) }
        item(name, icon, label)
    }
}


