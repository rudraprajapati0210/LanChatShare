package dev.lanchatshare

import android.os.Bundle
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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var trustStore: dev.lanchatshare.connectivity.TrustStore
    private val profileModel: ProfileModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
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
                                Home(it, busy, failed, trustStore, profileModel::save)
                            } ?: NameForm(null, busy, failed, profileModel::save)
                        }
                    }
                }
            }
        }
    }
}

