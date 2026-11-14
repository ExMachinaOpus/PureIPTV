package app.pureiptv.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pureiptv.ui.R
import app.pureiptv.ui.components.ChoiceButton
import app.pureiptv.ui.components.dpadLeavesTextField
import app.pureiptv.ui.components.PrimaryButton
import app.pureiptv.ui.components.Screen
import app.pureiptv.ui.components.SecondaryButton
import app.pureiptv.ui.components.messageRes

@Composable
fun AddSourceScreen(
    initialMode: AddMode,
    onAdded: (sourceId: Long) -> Unit,
    onCancel: () -> Unit,
    viewModel: AddSourceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val firstField = remember { FocusRequester() }

    LaunchedEffect(Unit) { viewModel.setMode(initialMode) }
    LaunchedEffect(state.addedSourceId) {
        state.addedSourceId?.let {
            viewModel.consumeAdded()
            onAdded(it)
        }
    }

    Screen(contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceButton(stringResource(R.string.add_mode_link), state.mode == AddMode.Link, { viewModel.setMode(AddMode.Link) })
                ChoiceButton(stringResource(R.string.add_mode_login), state.mode == AddMode.Login, { viewModel.setMode(AddMode.Login) })
            }

            val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
            val done = KeyboardActions(onDone = { viewModel.submit() })

            when (state.mode) {
                AddMode.Link -> {
                    OutlinedTextField(
                        value = state.link,
                        onValueChange = viewModel::setLink,
                        label = { Text(stringResource(R.string.add_field_link)) },
                        placeholder = { Text(stringResource(R.string.add_field_link_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        keyboardActions = next,
                        modifier = Modifier.fillMaxWidth().focusRequester(firstField).dpadLeavesTextField(focus),
                    )
                    // Optional: only for playlists that don't name their own guide.
                    OutlinedTextField(
                        value = state.guide,
                        onValueChange = viewModel::setGuide,
                        label = { Text(stringResource(R.string.add_field_guide)) },
                        placeholder = { Text(stringResource(R.string.add_field_guide_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = done,
                        modifier = Modifier.fillMaxWidth().dpadLeavesTextField(focus),
                    )
                }
                AddMode.Login -> {
                    OutlinedTextField(
                        value = state.server,
                        onValueChange = viewModel::setServer,
                        label = { Text(stringResource(R.string.add_field_server)) },
                        placeholder = { Text(stringResource(R.string.add_field_server_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        keyboardActions = next,
                        modifier = Modifier.fillMaxWidth().focusRequester(firstField).dpadLeavesTextField(focus),
                    )
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::setUsername,
                        label = { Text(stringResource(R.string.add_field_username)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        keyboardActions = next,
                        modifier = Modifier.fillMaxWidth().dpadLeavesTextField(focus),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::setPassword,
                        label = { Text(stringResource(R.string.add_field_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = done,
                        modifier = Modifier.fillMaxWidth().dpadLeavesTextField(focus),
                    )
                }
            }

            val message = when {
                state.missingFields -> stringResource(R.string.add_missing_fields)
                state.error != null -> stringResource(state.error!!.messageRes())
                else -> null
            }
            if (message != null) {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    text = stringResource(if (state.checking) R.string.add_checking else R.string.add_continue),
                    onClick = viewModel::submit,
                )
                SecondaryButton(stringResource(R.string.add_cancel), onClick = onCancel)
            }
        }
    }

    // Start on the first field: on TV OK opens the keyboard, on phones the keyboard opens right away.
    LaunchedEffect(state.mode) { runCatching { firstField.requestFocus() } }
}
