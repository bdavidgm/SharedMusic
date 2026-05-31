package com.bdavidgm.sharedmusic.ui.session

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bdavidgm.sharedmusic.domain.model.NodeMode
import com.bdavidgm.sharedmusic.domain.model.SessionPhase
import com.bdavidgm.sharedmusic.domain.model.SessionState

@Composable
fun SessionScreen(
    state: SessionState,
    modifier: Modifier = Modifier,
    onSelectTrack: (android.net.Uri) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopPlayback: () -> Unit,
    onStopSession: () -> Unit
) {
    val pickTrack = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onSelectTrack) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StatusCard(state)

        if (state.mode == NodeMode.SERVER || state.mode == NodeMode.REPEATER) {
            NetworkCard(state)
        }

        state.track?.let { TrackCard(state) }

        if (state.phase == SessionPhase.TRANSFERRING) {
            LinearProgressIndicator(
                progress = { state.transferProgress },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (state.mode == NodeMode.SERVER) {
            ServerControls(
                state = state,
                onPickTrack = { pickTrack.launch("audio/*") },
                onPlay = onPlay,
                onPause = onPause,
                onResume = onResume,
                onStopPlayback = onStopPlayback
            )
        } else {
            ClientStatus(state)
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = onStopSession, modifier = Modifier.fillMaxWidth()) {
            Text("Salir de la sesión")
        }
    }
}

@Composable
private fun StatusCard(state: SessionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Modo: ${state.mode?.label() ?: "—"}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text("Estado: ${state.phase.label()}")
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun NetworkCard(state: SessionState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Esta es tu dirección para que otros se conecten:",
                style = MaterialTheme.typography.labelMedium)
            Text(
                text = "${state.localAddress ?: "IP desconocida"}:${state.listenPort}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Divider(Modifier.padding(vertical = 4.dp))
            Text("Clientes conectados: ${state.downstreamPeers.size}",
                style = MaterialTheme.typography.titleSmall)
            state.downstreamPeers.forEach { peer ->
                Text("• ${peer.address}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TrackCard(state: SessionState) {
    val track = state.track ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pista", style = MaterialTheme.typography.labelMedium)
            Text(track.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val sizeMb = track.sizeBytes / (1024.0 * 1024.0)
            Text("Tamaño: ${"%.1f".format(sizeMb)} MB · ${track.mimeType}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServerControls(
    state: SessionState,
    onPickTrack: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopPlayback: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPickTrack, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.track == null) "Seleccionar canción" else "Cambiar canción")
        }

        val canPlay = state.track != null &&
            state.phase != SessionPhase.TRANSFERRING &&
            !state.isPlaying

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPlay,
                enabled = canPlay,
                modifier = Modifier.weight(1f)
            ) { Text("Reproducir") }

            if (state.isPlaying) {
                Button(onClick = onPause, modifier = Modifier.weight(1f)) { Text("Pausa") }
            } else if (state.phase == SessionPhase.PAUSED) {
                Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("Reanudar") }
            }
        }

        OutlinedButton(
            onClick = onStopPlayback,
            enabled = state.isPlaying || state.phase == SessionPhase.PAUSED,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Detener reproducción") }
    }
}

@Composable
private fun ClientStatus(state: SessionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Desfase de reloj con el upstream: ${state.clockOffsetMs} ms",
                style = MaterialTheme.typography.bodyMedium)
            Text(
                if (state.isPlaying) "Reproduciendo en sincronía" else "En espera del servidor…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun NodeMode.label(): String = when (this) {
    NodeMode.SERVER -> "Servidor"
    NodeMode.CLIENT -> "Cliente"
    NodeMode.REPEATER -> "Repetidor"
}

private fun SessionPhase.label(): String = when (this) {
    SessionPhase.IDLE -> "Inactivo"
    SessionPhase.STARTING -> "Iniciando"
    SessionPhase.CONNECTING -> "Conectando"
    SessionPhase.READY -> "Listo"
    SessionPhase.TRANSFERRING -> "Transfiriendo pista"
    SessionPhase.PLAYING -> "Reproduciendo"
    SessionPhase.PAUSED -> "En pausa"
    SessionPhase.ERROR -> "Error"
}
