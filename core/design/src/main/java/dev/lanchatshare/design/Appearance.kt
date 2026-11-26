package dev.lanchatshare.design

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

data class Palette(val name: String, val ink: Color, val night: Color, val paper: Color)
val palettes = listOf(
    Palette("Estuary",Color(0xff006457),Color(0xff92dfcb),Color(0xffcfebe0)),
    Palette("Kiln",Color(0xff8a402a),Color(0xfff2b59c),Color(0xfff5d9ca)),
    Palette("Blueprint",Color(0xff344e79),Color(0xffb5caed),Color(0xffdfe6f3)),
    Palette("Orchard",Color(0xff4b602c),Color(0xffc2d893),Color(0xffe1e9ca)),
    Palette("Mulberry",Color(0xff73405c),Color(0xffe6b7cf),Color(0xfff2dbe8)),
    Palette("Carbon",Color(0xff48494a),Color(0xffd4d3cb),Color(0xffe6e5dc)),
)
data class Appearance(val mode: String = "System",val palette: String = "Estuary",val wallpaper: String = "Plain",val reduced: Boolean = false)
val LocalAppMotion = staticCompositionLocalOf { true }
val LocalWallpaper = staticCompositionLocalOf { "Plain" }
private val Context.visuals by preferencesDataStore("appearance")
fun Context.appearance(peer: String? = null) = visuals.data.map { p ->
    fun value(key: String, fallback: String) = peer?.let { p[stringPreferencesKey("$it/$key")] } ?: p[stringPreferencesKey(key)] ?: fallback
    Appearance(value("mode","System"),value("palette","Estuary"),value("wallpaper","Plain"),p[booleanPreferencesKey("reduced")] ?: false)
}
suspend fun Context.visual(key: String,value: String,peer: String? = null) {
    visuals.edit { prefs ->
        val option = stringPreferencesKey(if (peer == null) key else "$peer/$key")
        if (value == "Use default") prefs.remove(option) else prefs[option] = value
    }
}
suspend fun Context.reduceMotion(value: Boolean) { visuals.edit { it[booleanPreferencesKey("reduced")] = value } }
