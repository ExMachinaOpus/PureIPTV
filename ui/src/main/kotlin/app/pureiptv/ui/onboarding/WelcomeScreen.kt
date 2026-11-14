package app.pureiptv.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pureiptv.ui.R
import app.pureiptv.ui.components.PrimaryButton
import app.pureiptv.ui.components.Screen
import app.pureiptv.ui.components.SecondaryButton
import app.pureiptv.ui.components.isTv

/** First run. TV: one clear action (the add screen offers both kinds). Touch: both kinds directly. */
@Composable
fun WelcomeScreen(onAdd: (AddMode) -> Unit, onPhone: () -> Unit) {
    val primaryFocus = remember { FocusRequester() }
    val tv = isTv
    LaunchedEffect(Unit) { if (tv) primaryFocus.requestFocus() }

    Screen(contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Text(
                stringResource(R.string.app_name),
                style = if (tv) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            if (tv) {
                // Typing a password with a remote is the worst part of every TV app: offer the phone first.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PrimaryButton(
                        stringResource(R.string.welcome_phone),
                        onClick = onPhone,
                        modifier = Modifier.focusRequester(primaryFocus),
                    )
                    SecondaryButton(stringResource(R.string.welcome_type_on_tv), onClick = { onAdd(AddMode.Link) })
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        stringResource(R.string.welcome_paste_link),
                        onClick = { onAdd(AddMode.Link) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecondaryButton(
                        stringResource(R.string.welcome_sign_in),
                        onClick = { onAdd(AddMode.Login) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
