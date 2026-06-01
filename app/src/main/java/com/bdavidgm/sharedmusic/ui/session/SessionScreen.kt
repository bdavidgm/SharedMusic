package com.bdavidgm.sharedmusic.ui.session

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bdavidgm.sharedmusic.domain.model.NodeMode
import com.bdavidgm.sharedmusic.domain.model.SessionPhase
import com.bdavidgm.sharedmusic.domain.model.SessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    state: SessionState,
    modifier: Modifier = Modifier,
    onStopSession: () -> Unit,
    onAddPlaylistItem: (android.net.Uri) -> Unit = {},
    onAddPlaylistFolder: (android.net.Uri) -> Unit = {},
    onTogglePlaylistTransport: () -> Unit = {},
    onRemovePlaylistItem: (Int) -> Unit = {}
) {
    val pickPlaylistSong = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onAddPlaylistItem) }

    val pickPlaylistFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(onAddPlaylistFolder) }

    var serverTabIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.mode?.label() ?: "Sesión",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onStopSession,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Salir de la sesión"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .then(
                    if (state.mode != NodeMode.SERVER) {
                        Modifier.verticalScroll(scroll)
                    } else {
                        Modifier
                    }
                )
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state.mode) {
                NodeMode.SERVER -> {
                    TabRow(selectedTabIndex = serverTabIndex) {
                        Tab(
                            selected = serverTabIndex == 0,
                            onClick = { serverTabIndex = 0 },
                            text = { Text("Conexión") }
                        )
                        Tab(
                            selected = serverTabIndex == 1,
                            onClick = { serverTabIndex = 1 },
                            text = { Text("Reproducción") }
                        )
                    }
                    when (serverTabIndex) {
                        0 -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            StatusCard(state)
                            NetworkCard(state)
                            ConnectionQrSection(
                                host = state.localAddress,
                                port = state.listenPort
                            )
                            state.track?.let { TrackCard(state) }
                            if (state.phase == SessionPhase.TRANSFERRING) {
                                LinearProgressIndicator(
                                    progress = { state.transferProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onStopSession,
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text("Salir de la sesión", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        1 -> ServerReproductionTab(
                            state = state,
                            onAddSong = { pickPlaylistSong.launch("audio/*") },
                            onAddFolder = { pickPlaylistFolder.launch(null) },
                            onTogglePlay = onTogglePlaylistTransport,
                            onRemoveItem = onRemovePlaylistItem
                        )
                    }
                }

                NodeMode.REPEATER -> {
                    StatusCard(state)
                    NetworkCard(state)
                    state.track?.let { TrackCard(state) }
                    if (state.phase == SessionPhase.TRANSFERRING) {
                        LinearProgressIndicator(
                            progress = { state.transferProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    ClientStatus(state)
                }

                NodeMode.CLIENT, null -> {
                    StatusCard(state)
                    state.track?.let { TrackCard(state) }
                    if (state.phase == SessionPhase.TRANSFERRING) {
                        LinearProgressIndicator(
                            progress = { state.transferProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    ClientStatus(state)
                }
            }

            if (state.mode != NodeMode.SERVER) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onStopSession,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("Salir de la sesión", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ConnectionQrSection(host: String?, port: Int) {
    val scheme = MaterialTheme.colorScheme
    val payload = if (!host.isNullOrBlank()) "$host:$port" else null
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Código QR (conexión)",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.primary
            )
            if (payload != null) {
                val bitmap = remember(payload) { QrCodeEncoder.encodeToBitmap(payload) }
                if (bitmap != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR con dirección $payload",
                            modifier = Modifier.size(200.dp)
                        )
                    }
                } else {
                    Text("No se pudo generar el QR.", color = scheme.error)
                }
                Text(
                    text = payload,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    "Activa la zona WiFi o espera a que aparezca la IP para generar el QR.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ServerReproductionTab(
    state: SessionState,
    onAddSong: () -> Unit,
    onAddFolder: () -> Unit,
    onTogglePlay: () -> Unit,
    onRemoveItem: (Int) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Cola de reproducción",
            style = MaterialTheme.typography.titleMedium,
            color = scheme.secondary
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(state.playlist, key = { index, item -> "$index-${item.uriString}" }) { index, item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = scheme.surface,
                        contentColor = scheme.onSurface
                    ),
                    border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${index + 1}. ${item.name}",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.mimeType,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onRemoveItem(index) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Quitar de la cola",
                                tint = scheme.error
                            )
                        }
                    }
                }
            }
        }
        if (state.playlist.isEmpty()) {
            Text(
                "La lista está vacía. Agrega canciones o una carpeta.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onAddSong,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.primary)
            ) {
                Text(
                    "Agregar canción",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = onAddFolder,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.primary)
            ) {
                Text(
                    "Agregar carpeta",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        val transportActive = state.playingFromPlaylist
        Button(
            onClick = onTogglePlay,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (transportActive) scheme.error else scheme.tertiary,
                contentColor = if (transportActive) scheme.onError else scheme.onTertiary
            ),
            enabled = state.playlist.isNotEmpty() || transportActive
        ) {
            Text(
                if (transportActive) "Detener reproducción" else "Reproducir",
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun StatusCard(state: SessionState) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surface,
            contentColor = scheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Modo: ${state.mode?.label() ?: "—"}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.primary
            )
            Text(
                "Estado: ${state.phase.label()}",
                color = scheme.onSurfaceVariant
            )
            state.message?.let {
                Text(it, color = scheme.secondary)
            }
            state.errorMessage?.let {
                Text(it, color = scheme.error)
            }
        }
    }
}

@Composable
private fun NetworkCard(state: SessionState) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Esta es tu dirección para que otros se conecten:",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onPrimaryContainer.copy(alpha = 0.85f)
            )
            Text(
                text = "${state.localAddress ?: "IP desconocida"}:${state.listenPort}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.primary
            )
            Divider(
                color = scheme.outline.copy(alpha = 0.35f),
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Text(
                "Clientes conectados: ${state.downstreamPeers.size}",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onPrimaryContainer
            )
            state.downstreamPeers.forEach { peer ->
                Text(
                    "• ${peer.address}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun TrackCard(state: SessionState) {
    val track = state.track ?: return
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Pista",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.secondary
            )
            Text(
                track.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onPrimaryContainer
            )
            val sizeMb = track.sizeBytes / (1024.0 * 1024.0)
            Text(
                "Tamaño: ${"%.1f".format(sizeMb)} MB · ${track.mimeType}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimaryContainer.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun ClientStatus(state: SessionState) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surface,
            contentColor = scheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Desfase de reloj con el upstream: ${state.clockOffsetMs} ms",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
            Text(
                if (state.isPlaying) "Reproduciendo en sincronía" else "En espera del servidor…",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.secondary
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
