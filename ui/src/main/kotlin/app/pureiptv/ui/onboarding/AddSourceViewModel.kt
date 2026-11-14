package app.pureiptv.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pureiptv.core.data.source.SourceException
import app.pureiptv.core.data.source.SourceInput
import app.pureiptv.core.data.source.SourceManager
import app.pureiptv.core.model.SourceErrorKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AddMode { Link, Login }

data class AddSourceState(
    val mode: AddMode = AddMode.Link,
    val link: String = "",
    /** Optional guide address for a playlist that names none. */
    val guide: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val checking: Boolean = false,
    val error: SourceErrorKind? = null,
    val missingFields: Boolean = false,
    /** Set once the source is saved; the screen navigates to the import progress. */
    val addedSourceId: Long? = null,
)

@HiltViewModel
class AddSourceViewModel @Inject constructor(
    private val sources: SourceManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AddSourceState())
    val state: StateFlow<AddSourceState> = _state.asStateFlow()
    private var job: Job? = null

    fun setMode(mode: AddMode) = _state.update { it.copy(mode = mode, error = null, missingFields = false) }
    fun setLink(v: String) = _state.update { it.copy(link = v, error = null, missingFields = false) }
    fun setGuide(v: String) = _state.update { it.copy(guide = v, error = null) }
    fun setServer(v: String) = _state.update { it.copy(server = v, error = null, missingFields = false) }
    fun setUsername(v: String) = _state.update { it.copy(username = v, error = null, missingFields = false) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null, missingFields = false) }

    fun submit() {
        val s = _state.value
        if (s.checking) return
        val input = when (s.mode) {
            AddMode.Link -> s.link.takeIf { it.isNotBlank() }?.let { SourceInput.Link(it, s.guide.ifBlank { null }) }
            AddMode.Login -> if (s.server.isNotBlank() && s.username.isNotBlank() && s.password.isNotEmpty()) {
                SourceInput.Login(s.server, s.username, s.password)
            } else {
                null
            }
        }
        if (input == null) {
            _state.update { it.copy(missingFields = true) }
            return
        }
        _state.update { it.copy(checking = true, error = null) }
        job = viewModelScope.launch {
            try {
                val resolved = sources.resolve(input)
                val id = sources.add(resolved)
                _state.update { it.copy(checking = false, addedSourceId = id) }
            } catch (e: SourceException) {
                _state.update { it.copy(checking = false, error = e.kind) }
            }
        }
    }

    /** Called after navigating away, so returning to this screen doesn't navigate again. */
    fun consumeAdded() = _state.update { it.copy(addedSourceId = null) }
}
