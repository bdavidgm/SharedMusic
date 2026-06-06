package com.bdavidgm.sharedmusic.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bdavidgm.sharedmusic.domain.model.NodeMode
import com.bdavidgm.sharedmusic.domain.model.SessionState
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    onStartServer: (Int) -> Unit,
    onStartClient: (String, Int) -> Unit,
    onStartRepeater: (String, Int, Int) -> Unit,
    onStarted: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(NodeMode.SERVER) }
    var host by remember { mutableStateOf("192.168.43.1") }
    var upstreamPort by remember { mutableStateOf(SessionState.DEFAULT_PORT.toString()) }
    var listenPort by remember { mutableStateOf(SessionState.DEFAULT_PORT.toString()) }
    var scanParseError by remember { mutableStateOf<String?>(null) }
    var pendingQrAfterCamera by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scanContract = remember { ScanContract() }
    val scanOptions = remember {
        ScanOptions().apply {
            setDesiredBarcodeFormats(listOf(BarcodeFormat.QR_CODE.name))
            setPrompt("Apunta al código QR del servidor")
            setBeepEnabled(false)
            // Por defecto ZXing fija orientación horizontal; false = seguir la del dispositivo.
            setOrientationLocked(false)
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(scanContract) { result ->
        scanParseError = null
        val contents = result.contents
        if (contents.isNullOrBlank()) return@rememberLauncherForActivityResult
        val parsed = parseConnectionQrPayload(contents)
        if (parsed == null) {
            scanParseError = "El código no es una dirección ip:puerto válida."
        } else {
            host = parsed.first
            upstreamPort = parsed.second.toString()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingQrAfterCamera) {
            pendingQrAfterCamera = false
            scanLauncher.launch(scanOptions)
        } else {
            pendingQrAfterCamera = false
            if (!granted) {
                scanParseError = "Se necesita permiso de cámara para leer el QR."
            }
        }
    }

    val openQrScanner: () -> Unit = {
        scanParseError = null
        val hasCamera = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCamera) {
            scanLauncher.launch(scanOptions)
        } else {
            pendingQrAfterCamera = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "SharedMusic",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Reproduce una canción en sincronía en varios dispositivos por WiFi.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(text = "Modo", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)

            ModeOption(
                title = "Servidor",
                description = "Emite la música y la distribuye a los clientes.",
                selected = selectedMode == NodeMode.SERVER,
                onClick = { selectedMode = NodeMode.SERVER }
            )
            ModeOption(
                title = "Cliente",
                description = "Se conecta a un servidor o repetidor y reproduce.",
                selected = selectedMode == NodeMode.CLIENT,
                onClick = { selectedMode = NodeMode.CLIENT }
            )
            ModeOption(
                title = "Repetidor",
                description = "Cliente que además retransmite la música a otros.",
                selected = selectedMode == NodeMode.REPEATER,
                onClick = { selectedMode = NodeMode.REPEATER }
            )

            when (selectedMode) {
                NodeMode.SERVER -> {
                    PortField(
                        label = "Puerto de escucha",
                        value = listenPort,
                        onValueChange = { listenPort = it }
                    )
                }

                NodeMode.CLIENT -> {
                    HostField(host) { host = it }
                    PortField("Puerto del Servidor", upstreamPort) { upstreamPort = it }
                }

                NodeMode.REPEATER -> {
                    HostField(host) { host = it }
                    PortField("Puerto del Servidor", upstreamPort) { upstreamPort = it }
                    PortField("Puerto de escucha", listenPort) { listenPort = it }
                }
            }

            if (selectedMode == NodeMode.CLIENT || selectedMode == NodeMode.REPEATER) {
                OutlinedButton(
                    onClick = openQrScanner,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Leer código QR", fontWeight = FontWeight.Medium)
                }
                scanParseError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    when (selectedMode) {
                        NodeMode.SERVER ->
                            onStartServer(listenPort.toIntOrNull() ?: SessionState.DEFAULT_PORT)

                        NodeMode.CLIENT -> onStartClient(
                            host.trim(),
                            upstreamPort.toIntOrNull() ?: SessionState.DEFAULT_PORT
                        )

                        NodeMode.REPEATER -> onStartRepeater(
                            host.trim(),
                            upstreamPort.toIntOrNull() ?: SessionState.DEFAULT_PORT,
                            listenPort.toIntOrNull() ?: SessionState.DEFAULT_PORT
                        )
                    }
                    onStarted()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Text("Iniciar", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 4.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = MaterialTheme.colorScheme.outline
                )
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HostField(value: String, onValueChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("IP del Servidor", color = scheme.secondary) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outline,
            cursorColor = scheme.primary,
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariant,
            focusedTextColor = scheme.onSurface,
            unfocusedTextColor = scheme.onSurface
        )
    )
}

@Composable
private fun PortField(label: String, value: String, onValueChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label, color = scheme.secondary) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outline,
            cursorColor = scheme.primary,
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariant,
            focusedTextColor = scheme.onSurface,
            unfocusedTextColor = scheme.onSurface
        )
    )
}
