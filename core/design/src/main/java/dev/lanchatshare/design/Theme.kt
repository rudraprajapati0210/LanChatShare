package dev.lanchatshare.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp

private val light = lightColorScheme(
    primary = Color(0xff006457), onPrimary = Color.White, background = Color(0xfffaf9f1),
    surface = Color(0xfffaf9f1), onBackground = Color(0xff152a29), onSurface = Color(0xff152a29),
    surfaceContainer = Color(0xffeaece4), secondaryContainer = Color(0xffcfebe0),
    onSecondaryContainer = Color(0xff00392f), onSurfaceVariant = Color(0xff475e59), error = Color(0xffa5302c)
)
private val dark = darkColorScheme(
    primary = Color(0xff92dfcb), onPrimary = Color(0xff00392f), background = Color(0xff101d1b),
    surface = Color(0xff101d1b), onBackground = Color(0xffedf4eb), onSurface = Color(0xffedf4eb),
    surfaceContainer = Color(0xff243632), secondaryContainer = Color(0xff284c42),
    onSecondaryContainer = Color(0xffedf4eb), onSurfaceVariant = Color(0xffb7cac2), error = Color(0xffffb4ab)
)
val brandFont = FontFamily(Font(R.font.dm_sans))
private fun type(size: Int, line: Int, weight: Int) = TextStyle(
    fontFamily = brandFont, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp
)
private object StillMotion : MotionScheme {
    override fun <T> defaultSpatialSpec() = snap<T>()
    override fun <T> fastSpatialSpec() = snap<T>()
    override fun <T> slowSpatialSpec() = snap<T>()
    override fun <T> defaultEffectsSpec() = snap<T>()
    override fun <T> fastEffectsSpec() = snap<T>()
    override fun <T> slowEffectsSpec() = snap<T>()
}
@Composable fun LanTheme(peer: String? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val appearance by remember(context,peer) { context.appearance(peer) }.collectAsState(Appearance())
    val night = when(appearance.mode) { "Light" -> false; "Dark","AMOLED" -> true; else -> isSystemInDarkTheme() }
    val palette = palettes.firstOrNull { it.name == appearance.palette } ?: palettes.first()
    val base = if (appearance.palette == "Dynamic" && android.os.Build.VERSION.SDK_INT >= 31) {
        if (night) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else (if (night) dark else light).copy(primary = if (night) palette.night else palette.ink,
        onPrimary = if (night) Color.Black else Color.White,
        secondaryContainer = if (night) palette.ink.copy(alpha = 1f) else palette.paper,
        onSecondaryContainer = if (night) Color.White else Color(0xff182321))
    val surface = if (appearance.mode == "AMOLED") Color.Black else base.surface
    var systemMotion by remember { mutableStateOf(android.animation.ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { systemMotion = android.animation.ValueAnimator.areAnimatorsEnabled() }
        }
        context.contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor("animator_duration_scale"),false,observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val motion = !appearance.reduced && systemMotion
    val duration = if (motion) 180 else 0
    val primary by animateColorAsState(base.primary,tween(duration),label="Accent")
    val ground by animateColorAsState(surface,tween(duration),label="Surface")
    CompositionLocalProvider(LocalAppMotion provides motion, LocalWallpaper provides appearance.wallpaper) {
    MaterialTheme(colorScheme = base.copy(primary = primary,surface = ground,background = ground),
        motionScheme = if (motion) MotionScheme.expressive() else StillMotion, typography = Typography(
        displaySmall = type(36,42,650), headlineSmall = type(24,32,650), titleMedium = type(17,24,600),
        bodyLarge = type(16,24,400), labelLarge = type(14,20,600), labelSmall = type(12,16,500)
    ), content = content)
    }
}
