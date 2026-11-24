package dev.lanchatshare.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.regex.Pattern

data class Profile(val name: String, val id: String, val colour: Int)

private val grapheme = Pattern.compile("\\X")
fun validatedName(input: String): String? {
    val name = input.trim()
    if (name.isEmpty() || name.any { it.isISOControl() }) return null
    val matcher = grapheme.matcher(name)
    var count = 0
    while (matcher.find()) if (++count > 24) return null
    return name
}

class ProfileRepository(private val store: DataStore<Preferences>) {
    private val name = stringPreferencesKey("name")
    private val id = stringPreferencesKey("id")
    private val colour = intPreferencesKey("colour")
    val profile = store.data.map { prefs ->
        prefs[name]?.let { Profile(it, checkNotNull(prefs[id]), checkNotNull(prefs[colour])) }
    }

    suspend fun save(input: String, avatar: Int? = null) {
        val normalized = requireNotNull(validatedName(input))
        store.edit { prefs ->
            val deviceId = prefs[id] ?: UUID.randomUUID().toString()
            prefs[id] = deviceId
            prefs[colour] = avatar ?: prefs[colour] ?: Math.floorMod(deviceId.hashCode(), 4)
            prefs[name] = normalized
        }
    }
}
