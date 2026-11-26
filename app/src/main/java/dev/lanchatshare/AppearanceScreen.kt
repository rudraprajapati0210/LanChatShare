package dev.lanchatshare

import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lanchatshare.design.*
import kotlinx.coroutines.launch

@Composable internal fun AppearanceScreen(peer: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appearance by remember(peer) { context.appearance(peer) }.collectAsStateWithLifecycle(Appearance())
    LanTheme(peer) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer,shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Meet by the window?",Modifier.padding(8.dp))
                    Surface(modifier = Modifier.align(Alignment.End),color = MaterialTheme.colorScheme.primary,shape = MaterialTheme.shapes.medium) {
                        Text("On my way.",Modifier.padding(10.dp),color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            fun set(key: String,value: String) { scope.launch { context.visual(key,value,peer) } }
            Text("Display",style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("System","Light","Dark","AMOLED").forEach { mode -> FilterChip(appearance.mode == mode,{set("mode",mode)},{Text(mode)}) }
            }
            Text("Colour",style = MaterialTheme.typography.titleMedium)
            palettes.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { palette ->
                    FilterChip(appearance.palette == palette.name,{set("palette",palette.name)},{Text(palette.name)},
                        leadingIcon = { Box(Modifier.size(16.dp).background(palette.ink,CircleShape)) })
                }
            } }
            if (Build.VERSION.SDK_INT >= 31) FilterChip(appearance.palette == "Dynamic",{set("palette","Dynamic")},{Text("Device colours")})
            Text("Chat wallpaper",style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Plain","Tint","Dotted").forEach { paper -> FilterChip(appearance.wallpaper == paper,{set("wallpaper",paper)},{Text(paper)}) }
            }
            if (peer == null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reduce motion",Modifier.weight(1f))
                Switch(appearance.reduced,{scope.launch { context.reduceMotion(it) }})
            } else TextButton(onClick = { listOf("palette","mode","wallpaper").forEach { set(it,"Use default") } }) { Text("Use app defaults") }
        }
    }
}
