package dev.lanchatshare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lanchatshare.data.Profile
import dev.lanchatshare.data.ProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface Gate {
    data object Loading : Gate
    data object Failed : Gate
    data class Ready(val profile: Profile?) : Gate
}
@HiltViewModel
class ProfileModel @Inject constructor(private val repository: ProfileRepository) : ViewModel() {
    private val gate = MutableStateFlow<Gate>(Gate.Loading)
    val state = gate.asStateFlow()
    private val saving = MutableStateFlow(false)
    val busy = saving.asStateFlow()
    private val failure = MutableStateFlow(false)
    val saveFailed = failure.asStateFlow()
    private var reader: Job? = null
    init { read() }
    fun read() {
        reader?.cancel()
        reader = viewModelScope.launch {
            repository.profile.catch { if (it is IOException) gate.value = Gate.Failed else throw it }
                .collect { gate.value = Gate.Ready(it) }
        }
    }
    fun save(name: String, colour: Int?) {
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            try { failure.value = false; repository.save(name, colour) }
            catch (_: IOException) { failure.value = true }
            finally { saving.value = false }
        }
    }
}
