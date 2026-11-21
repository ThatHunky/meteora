package dev.thathunky.meteora.ui.pairing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.thathunky.meteora.R
import dev.thathunky.meteora.companion.discovery.DiscoveredPc

@Composable
fun PairingScreen(onDone: () -> Unit, modifier: Modifier = Modifier, vm: PairingViewModel = hiltViewModel()) {
    val step by vm.step.collectAsStateWithLifecycle()
    val found by vm.discovered.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = step,
        contentKey = { it::class },
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() },
        modifier = modifier.fillMaxSize(),
        label = "pairing",
    ) { s ->
        when (s) {
            PairingStep.Choose -> ChooseStep(found, vm::pair, vm::pairManual)
            is PairingStep.Probing -> Centered {
                LoadingIndicator(Modifier.size(96.dp))
                Text(stringResource(R.string.pair_probing, s.name), style = MaterialTheme.typography.titleMedium)
            }
            is PairingStep.WaitingForAuthToggle -> Centered {
                ContainedLoadingIndicator(Modifier.size(96.dp))
                Text(stringResource(R.string.pair_enable_auth_title), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
                Text(stringResource(R.string.pair_enable_auth_body), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = vm::cancel) { Text(stringResource(android.R.string.cancel)) }
            }
            is PairingStep.ShowCode -> Centered {
                Text(stringResource(R.string.pair_code_title), style = MaterialTheme.typography.titleLarge)
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Text(
                        s.code.toCharArray().joinToString(" "),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp),
                    )
                }
                Text(stringResource(R.string.pair_code_body, s.name), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LoadingIndicator()
            }
            is PairingStep.Failed -> Centered {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(
                        when (s.reason) {
                            PairingStep.Reason.Unreachable -> R.string.pair_fail_unreachable
                            PairingStep.Reason.Denied -> R.string.pair_fail_denied
                            PairingStep.Reason.TooMany -> R.string.pair_fail_too_many
                            PairingStep.Reason.Other -> R.string.pair_fail_other
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = vm::cancel) { Text(stringResource(R.string.action_try_again)) }
            }
            is PairingStep.Done -> Centered {
                Icon(Icons.Outlined.TaskAlt, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.pair_done, s.name), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
                Button(onClick = onDone, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.action_continue)) }
            }
        }
    }
}

@Composable
private fun ChooseStep(found: List<DiscoveredPc>, onPick: (DiscoveredPc) -> Unit, onManual: (String) -> Unit) {
    val manualState = rememberTextFieldState()
    val manual = manualState.text.toString()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.displaySmallEmphasized)
            Text(stringResource(R.string.pair_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
        if (found.isEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Text(stringResource(R.string.pair_searching), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(found, key = { it.name }) { pc ->
            Card(
                onClick = { onPick(pc) },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.animateItem(),
            ) {
                ListItem(
                    headlineContent = { Text(pc.name, style = MaterialTheme.typography.titleMediumEmphasized) },
                    supportingContent = { Text(pc.addresses.joinToString(" · ")) },
                    leadingContent = { Icon(Icons.Outlined.Computer, null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.pair_manual), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                state = manualState,
                label = { Text(stringResource(R.string.pair_manual_hint)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                onKeyboardAction = { if (manual.isNotBlank()) onManual(manual) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onManual(manual) }, enabled = manual.isNotBlank(), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.action_connect))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
