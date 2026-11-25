package dev.lanchatshare

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.lanchatshare.design.LanTheme

data class IncomingShare(val uris: List<String>, val text: String)

@Suppress("DEPRECATION")
internal fun Intent.incomingShare(): IncomingShare? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val items = buildList {
        if (action == Intent.ACTION_SEND) androidx.core.content.IntentCompat.getParcelableExtra(this@incomingShare, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
        else addAll(androidx.core.content.IntentCompat.getParcelableArrayListExtra(this@incomingShare, Intent.EXTRA_STREAM, Uri::class.java).orEmpty())
        clipData?.let { clip -> repeat(clip.itemCount) { clip.getItemAt(it).uri?.let(::add) } }
    }.filter { it.scheme == "content" }.distinct().map(Uri::toString)
    val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
    return IncomingShare(items, text).takeIf { items.isNotEmpty() || text.isNotBlank() }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var trustStore: dev.lanchatshare.connectivity.TrustStore
    @javax.inject.Inject lateinit var chat: dev.lanchatshare.connectivity.LanChat
    private val profileModel: ProfileModel by viewModels()
    private var pendingShare by mutableStateOf<IncomingShare?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingShare = intent.incomingShare()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingShare?.let {
            outState.putStringArrayList("incomingUris", ArrayList(it.uris))
            outState.putString("incomingText", it.text)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingShare = if (savedInstanceState == null) intent.incomingShare()
            else savedInstanceState.getStringArrayList("incomingUris")?.let { IncomingShare(it, savedInstanceState.getString("incomingText").orEmpty()) }
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { profileModel.state.value == Gate.Loading }
        setContent {
            val state by profileModel.state.collectAsStateWithLifecycle()
            val busy by profileModel.busy.collectAsStateWithLifecycle()
            val failed by profileModel.saveFailed.collectAsStateWithLifecycle()
            LanTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding().imePadding()) {
                        when (val current = state) {
                            Gate.Loading -> Unit
                            Gate.Failed -> Column(Modifier.padding(24.dp)) {
                                Text("Your profile couldn’t be opened.")
                                Button(onClick = profileModel::read) { Text("Try again") }
                            }
                            is Gate.Ready -> current.profile?.let {
                                Home(it, busy, failed, trustStore, chat, pendingShare, { pendingShare = null }, profileModel::save)
                            } ?: NameForm(null, busy, failed, profileModel::save)
                        }
                    }
                }
            }
        }
    }
}


