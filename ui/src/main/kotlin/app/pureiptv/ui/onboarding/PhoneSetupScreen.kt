package app.pureiptv.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pureiptv.core.data.remote.PhoneSetupEvent
import app.pureiptv.core.data.remote.PhoneSetupServer
import app.pureiptv.core.data.source.SourceException
import app.pureiptv.core.data.source.SourceManager
import app.pureiptv.core.model.SourceErrorKind
import app.pureiptv.ui.R
import app.pureiptv.ui.components.Screen
import app.pureiptv.ui.components.SecondaryButton
import app.pureiptv.ui.components.messageRes
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PhoneSetupUi(
    val url: String? = null,
    val noNetwork: Boolean = false,
    val checking: Boolean = false,
    val error: SourceErrorKind? = null,
    val addedSourceId: Long? = null,
)

@HiltViewModel
class PhoneSetupViewModel @Inject constructor(
    server: PhoneSetupServer,
    private val sources: SourceManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(PhoneSetupUi())
    val ui: StateFlow<PhoneSetupUi> = _ui.asStateFlow()

    init {
        // The server lives exactly as long as this screen's ViewModel.
        viewModelScope.launch {
            server.run().collect { event ->
                when (event) {
                    is PhoneSetupEvent.Ready -> _ui.update { it.copy(url = event.session.url) }
                    PhoneSetupEvent.NoNetwork -> _ui.update { it.copy(noNetwork = true) }
                    is PhoneSetupEvent.Received -> add(event)
                }
            }
        }
    }

    private fun add(event: PhoneSetupEvent.Received) {
        if (_ui.value.checking || _ui.value.addedSourceId != null) return
        _ui.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                val id = sources.add(sources.resolve(event.input))
                _ui.update { it.copy(checking = false, addedSourceId = id) }
            } catch (e: SourceException) {
                // Stay on this screen: the phone can correct and send again.
                _ui.update { it.copy(checking = false, error = e.kind) }
            }
        }
    }

    fun consumeAdded() = _ui.update { it.copy(addedSourceId = null) }
}

/** TV: scan a QR code with the phone, fill in the source there, the TV takes it from there. */
@Composable
fun PhoneSetupScreen(
    onAdded: (Long) -> Unit,
    onTypeInstead: () -> Unit,
    viewModel: PhoneSetupViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val buttonFocus = remember { FocusRequester() }
    LaunchedEffect(ui.addedSourceId) {
        ui.addedSourceId?.let {
            viewModel.consumeAdded()
            onAdded(it)
        }
    }
    LaunchedEffect(Unit) { runCatching { buttonFocus.requestFocus() } }

    Screen(contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(300.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val url = ui.url
                when {
                    url != null -> QrCode(url, Modifier.size(268.dp))
                    !ui.noNetwork -> CircularProgressIndicator(color = Color.Black)
                }
            }
            Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.phone_title), style = MaterialTheme.typography.headlineMedium)
                when {
                    ui.noNetwork -> Text(stringResource(R.string.phone_no_network), color = MaterialTheme.colorScheme.error)
                    else -> {
                        Text(stringResource(R.string.phone_steps), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ui.url?.let { Text(stringResource(R.string.phone_or_open, it), style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                if (ui.checking) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.add_checking))
                    }
                }
                ui.error?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
                SecondaryButton(stringResource(R.string.phone_type_instead), onTypeInstead, Modifier.focusRequester(buttonFocus))
            }
        }
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix: BitMatrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
