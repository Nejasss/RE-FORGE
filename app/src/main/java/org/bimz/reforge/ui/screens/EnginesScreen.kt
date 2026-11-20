package org.bimz.reforge.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bimz.reforge.engines.EngineStatus
import org.bimz.reforge.ui.components.BadgeTone
import org.bimz.reforge.ui.components.KeyValueRow
import org.bimz.reforge.ui.components.StatusPill
import org.bimz.reforge.ui.components.WorkstationCard
import org.bimz.reforge.ui.components.formatByteSize
import org.bimz.reforge.ui.state.WorkstationUiState
import org.bimz.reforge.ui.state.WorkstationViewModel

@Composable
fun EnginesScreen(
    state: WorkstationUiState,
    viewModel: WorkstationViewModel
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(state.engines, key = { it.id }) { engine ->
            val isUnsupported = engine.status == EngineStatus.UNSUPPORTED
            val showExpandedUi = engine.enabled && !isUnsupported

            val tone = when (engine.status) {
                EngineStatus.INTEGRATED -> BadgeTone.PASS
                EngineStatus.PARTIALLY_INTEGRATED, EngineStatus.AVAILABLE -> BadgeTone.WARNING
                EngineStatus.FAILED, EngineStatus.UNSUPPORTED -> BadgeTone.FAILED
                EngineStatus.DISABLED, EngineStatus.NOT_TESTED -> BadgeTone.NEUTRAL
            }

            WorkstationCard(
                title = engine.name,
                subtitle = if (showExpandedUi) "${engine.version} | ${engine.license}" else null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(label = engine.status.name, tone = tone)
                    if (!isUnsupported) {
                        Switch(
                            checked = engine.enabled,
                            onCheckedChange = { viewModel.toggleEngineEnabled(engine.id, it) }
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showExpandedUi,
                    enter = expandVertically(tween(240)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        KeyValueRow("Integration", engine.integrationType.name)
                        KeyValueRow("Target ABI", engine.androidAbi, monospaceValue = true)
                        KeyValueRow("Binary Size", formatByteSize(engine.estimatedBinarySizeBytes))
                        KeyValueRow("Source", engine.sourceRepository)
                        KeyValueRow("Features", engine.features.joinToString(", "))
                        KeyValueRow("Compatibility", engine.compatibilityReport)
                        KeyValueRow("Last Test", engine.lastTestResult, monospaceValue = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.runEngineSelfTest(engine.id) },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Test Engine")
                        }
                    }
                }
            }
        }
    }
}
