package dev.lanchatshare.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
@Composable fun LanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, typography = Typography(
        displaySmall = type(36,42,650), headlineSmall = type(24,32,650), titleMedium = type(17,24,600),
        bodyLarge = type(16,24,400), labelLarge = type(14,20,600), labelSmall = type(12,16,500)
    ), content = content)
}
