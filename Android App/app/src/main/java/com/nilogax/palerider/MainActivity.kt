package com.nilogax.palerider

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nilogax.palerider.ble.BackgroundReader
import com.nilogax.palerider.ble.SensorDiscovery
import com.nilogax.palerider.ble.TyreReaderService
import com.nilogax.palerider.data.Bike
import com.nilogax.palerider.data.BikeStore
import com.nilogax.palerider.data.NotificationGapPrefs
import com.nilogax.palerider.data.HoldDurationPrefs
import com.nilogax.palerider.data.OutriderConfig
import com.nilogax.palerider.data.PressureStatus
import com.nilogax.palerider.data.PressureUnit
import com.nilogax.palerider.data.TyreRepository
import com.nilogax.palerider.data.UnitPrefs
import com.nilogax.palerider.data.WheelId
import com.nilogax.palerider.data.WheelPosition
import com.nilogax.palerider.data.WheelReading
import com.nilogax.palerider.data.formatRange
import com.nilogax.palerider.data.toPsi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BikeStore.syncToRepository(this)
        UnitPrefs.syncToRepository(this)
        setContent {
            PaleRiderTheme {
                PaleRiderScreen()
            }
        }
    }
}

@Composable
fun PaleRiderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = Color(0xFF80CBC4),
            surface = Color(0xFF121417),
            surfaceVariant = Color(0xFF1E2228)
        )
        else -> lightColorScheme(
            primary = Color(0xFF00695C),
            surface = Color(0xFFF7F9FC),
            surfaceVariant = Color(0xFFE6ECF2)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaleRiderScreen() {
    val context = LocalContext.current
    val uiState by TyreRepository.state.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    var pairTarget by remember { mutableStateOf<WheelId?>(null) }
    var forgetTarget by remember { mutableStateOf<WheelId?>(null) }
    var showAddBike by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Bike?>(null) }
    var rangeTarget by remember { mutableStateOf<Bike?>(null) }
    var historyTarget by remember { mutableStateOf<Bike?>(null) }
    var deleteTarget by remember { mutableStateOf<Bike?>(null) }

    val anySensorPaired = uiState.bikes.any { it.pairedWheels.isNotEmpty() }
    val editEnabled = !uiState.keepAlive

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(uiState.keepAlive) {
        while (uiState.keepAlive) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val requiredPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    var permissionsGranted by remember {
        mutableStateOf(hasAllPermissions(context, requiredPermissions))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            TyreReaderService.registerBackgroundScan(context)
        }
    }

    LaunchedEffect(permissionsGranted) {
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            TyreReaderService.registerBackgroundScan(context)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 310.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                text = "Notifications",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Notify on tyre reading",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.backgroundWatchActive,
                            onCheckedChange = { enabled ->
                                if (enabled) TyreReaderService.registerBackgroundScan(context)
                                else TyreReaderService.unregisterBackgroundScan(context)
                            }
                        )
                    }

                    HorizontalDivider()

                    NotificationGapSetting()

                    HorizontalDivider()


                    var holdDuration by remember { mutableStateOf(HoldDurationPrefs.get(context)) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            ) {
                                Text(
                                    text = "Keep Alive",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "$holdDuration minutes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        if (holdDuration > 1) {
                                            holdDuration--
                                            HoldDurationPrefs.set(context, holdDuration)
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Text("−", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                                Text(
                                    text = "$holdDuration",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.widthIn(min = 28.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                FilledTonalIconButton(
                                    onClick = {
                                        if (holdDuration < 60) {
                                            holdDuration++
                                            HoldDurationPrefs.set(context, holdDuration)
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Text("+", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Pressure units",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        PressureUnit.values().forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = uiState.unit == option,
                                        onClick = { UnitPrefs.set(context, option) },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = uiState.unit == option, onClick = null)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = option.label.lowercase(Locale.ROOT),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open menu"
                            )
                        }
                    },
                    title = {
                        Text(
                            text = "PaleRider",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val keepAliveContainerColor = if (uiState.keepAlive)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant

                    val keepAliveActiveTextColor = if (isDark) Color(0xFF121417) else Color(0xFFF7F9FC)
                    val keepAliveTextCol = if (uiState.keepAlive) keepAliveActiveTextColor else MaterialTheme.colorScheme.onSurface

                    // Keep Alive Toggle Button
                    Card(
                        onClick = {
                            val newKeepAlive = !uiState.keepAlive
                            val durationMins = HoldDurationPrefs.get(context)
                            val intent = Intent(context, TyreReaderService::class.java).apply {
                                action = TyreReaderService.ACTION_SET_KEEP_ALIVE
                                putExtra(TyreReaderService.EXTRA_KEEP_ALIVE, newKeepAlive)
                                putExtra(TyreReaderService.EXTRA_KEEP_ALIVE_DURATION_MINS, durationMins)
                            }
                            context.startForegroundService(intent)
                        },
                        enabled = anySensorPaired || uiState.keepAlive,
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = keepAliveContainerColor,
                            contentColor = keepAliveTextCol
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "Keep Alive",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.keepAlive) keepAliveTextCol else MaterialTheme.colorScheme.primary
                            )

                            val remainingSecs = if (uiState.keepAlive && uiState.keepAliveExpirationMillis > 0L) {
                                ((uiState.keepAliveExpirationMillis - now) / 1000L).coerceAtLeast(0L)
                            } else {
                                0L
                            }
                            val mins = remainingSecs / 60
                            val secs = remainingSecs % 60
                            val countdownStr = String.format(Locale.UK, "%d:%02d remaining", mins, secs)

                            Text(
                                text = if (uiState.keepAlive) countdownStr else "Off",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = keepAliveTextCol
                            )
                        }
                    }

                    // Read All Button (Vertically Centered)
                    Card(
                        onClick = {
                            val intent = Intent(context, TyreReaderService::class.java).apply {
                                action = TyreReaderService.ACTION_BURST_READ
                            }
                            context.startForegroundService(intent)
                        },
                        enabled = permissionsGranted && anySensorPaired && !uiState.keepAlive,
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "Read All",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    if (uiState.bikes.isEmpty()) {
                        item(key = "empty_state") {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "No bikes yet",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Add a bike, then pair its front and rear tyre sensors.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    items(
                        items = uiState.bikes,
                        key = { it.id }
                    ) { bike ->
                        BikeSection(
                            bike = bike,
                            readings = uiState.readings,
                            unit = uiState.unit,
                            pairEnabled = permissionsGranted && editEnabled,
                            editEnabled = editEnabled,
                            onPair = { position -> pairTarget = WheelId(bike.id, position) },
                            onForget = { position -> forgetTarget = WheelId(bike.id, position) },
                            onTargetRange = { rangeTarget = bike },
                            onHistory = { historyTarget = bike },
                            onRename = { renameTarget = bike },
                            onDelete = { deleteTarget = bike }
                        )
                    }

                    item(key = "add_bike_button") {
                        OutlinedButton(
                            onClick = { showAddBike = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add bike")
                        }
                    }
                }
            }
        }
    }

    pairTarget?.let { wheel ->
        PairSensorDialog(wheel = wheel, onDismiss = { pairTarget = null })
    }

    forgetTarget?.let { wheel ->
        ForgetSensorDialog(
            wheel = wheel,
            onConfirm = {
                forgetSensor(context, wheel)
                forgetTarget = null
            },
            onDismiss = { forgetTarget = null }
        )
    }

    if (showAddBike) {
        BikeNameDialog(
            title = "Add bike",
            initialName = "",
            confirmLabel = "Add",
            onConfirm = { name ->
                val bike = BikeStore.addBike(context, name)
                TyreRepository.setStatus("${bike.name} added — pair its sensors")
                showAddBike = false
            },
            onDismiss = { showAddBike = false }
        )
    }

    renameTarget?.let { bike ->
        BikeNameDialog(
            title = "Rename bike",
            initialName = bike.name,
            confirmLabel = "Save",
            onConfirm = { name ->
                BikeStore.renameBike(context, bike.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    rangeTarget?.let { bike ->
        TargetRangeDialog(
            bike = bike,
            unit = uiState.unit,
            onSave = { fMin, fMax, rMin, rMax ->
                BikeStore.setTargetRanges(context, bike.id, fMin, fMax, rMin, rMax)
                TyreRepository.setStatus("${bike.name} target ranges updated")
                rangeTarget = null
            },
            onDismiss = { rangeTarget = null }
        )
    }

    historyTarget?.let { bike ->
        BikeHistoryDialog(
            bike = bike,
            unit = uiState.unit,
            onDismiss = { historyTarget = null }
        )
    }

    deleteTarget?.let { bike ->
        DeleteBikeDialog(
            bike = bike,
            onConfirm = {
                deleteBike(context, bike)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun NotificationGapSetting() {
    val context = LocalContext.current
    var totalMins by remember { mutableStateOf(NotificationGapPrefs.getMinutes(context)) }
    val hours = totalMins / 60
    val mins = totalMins % 60

    fun update(newTotal: Int) {
        totalMins = newTotal.coerceIn(0, NotificationGapPrefs.MAX_MINUTES)
        NotificationGapPrefs.setMinutes(context, totalMins)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(
                text = "Notification gap",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (totalMins == 0) {
                    "No minimum gap between notifications"
                } else {
                    "Minimum ${NotificationGapPrefs.format(totalMins)} between notifications"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GapStepper(
                label = "Hours",
                value = hours,
                canDecrease = hours > 0,
                canIncrease = hours < 23,
                onDecrease = { update(totalMins - 60) },
                onIncrease = { update(totalMins + 60) },
                modifier = Modifier.weight(1f)
            )
            GapStepper(
                label = "Minutes",
                value = mins,
                canDecrease = mins > 0,
                canIncrease = mins < 59 && totalMins < NotificationGapPrefs.MAX_MINUTES,
                onDecrease = { update(totalMins - 1) },
                onIncrease = { update(totalMins + 1) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
@Composable
private fun GapStepper(
    label: String,
    value: Int,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilledTonalIconButton(
                onClick = onDecrease,
                enabled = canDecrease,
                modifier = Modifier.size(36.dp)
            ) {
                Text("−", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Text(
                text = "$value",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.widthIn(min = 28.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            FilledTonalIconButton(
                onClick = onIncrease,
                enabled = canIncrease,
                modifier = Modifier.size(36.dp)
            ) {
                Text("+", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

private fun wheelName(position: WheelPosition) = position.name.lowercase(Locale.ROOT)

private fun wheelLabel(context: Context, wheel: WheelId): String {
    val bikeName = BikeStore.bike(context, wheel.bikeId)?.name ?: "bike"
    return "$bikeName ${wheelName(wheel.position)}"
}

private fun forgetSensor(context: Context, wheel: WheelId) {
    val label = wheelLabel(context, wheel)
    BikeStore.forgetSensor(context, wheel)
    BackgroundReader.cancel()
    TyreReaderService.registerBackgroundScan(context)
    TyreRepository.setStatus("$label sensor forgotten")
}

private fun deleteBike(context: Context, bike: Bike) {
    BikeStore.deleteBike(context, bike.id)
    BackgroundReader.cancel()
    TyreReaderService.cancelResultNotification(context, bike.id)
    TyreReaderService.registerBackgroundScan(context)
    TyreRepository.setStatus("${bike.name} deleted")
}

@Composable
private fun BikeSection(
    bike: Bike,
    readings: Map<WheelId, WheelReading>,
    unit: PressureUnit,
    pairEnabled: Boolean,
    editEnabled: Boolean,
    onPair: (WheelPosition) -> Unit,
    onForget: (WheelPosition) -> Unit,
    onTargetRange: () -> Unit,
    onHistory: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = bike.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("Manage") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Target range") },
                        onClick = {
                            menuOpen = false
                            onTargetRange()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("History") },
                        onClick = {
                            menuOpen = false
                            onHistory()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete bike") },
                        enabled = editEnabled,
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            WheelPosition.values().forEach { position ->
                val reading = readings[WheelId(bike.id, position)] ?: WheelReading()
                WheelPressureCard(
                    title = position.name,
                    reading = reading,
                    status = bike.statusFor(position, reading.psi),
                    rangeText = unit.formatRange(bike.minPsiFor(position), bike.maxPsiFor(position)),
                    paired = bike.macFor(position) != null,
                    unit = unit,
                    pairEnabled = pairEnabled,
                    forgetEnabled = editEnabled,
                    onPair = { onPair(position) },
                    onForget = { onForget(position) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun BikeHistoryDialog(
    bike: Bike,
    unit: PressureUnit,
    onDismiss: () -> Unit
) {
    val dateTimeFormatter = remember {
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
            Locale.getDefault()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "${bike.name} history",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (bike.history.isEmpty()) {
                Text(
                    text = "No recorded readings yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    bike.history.forEach { item ->
                        val frontStr = item.frontPsi?.let { "${unit.format(it)} ${unit.label}" } ?: "--"
                        val rearStr = item.rearPsi?.let { "${unit.format(it)} ${unit.label}" } ?: "--"

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = dateTimeFormatter.format(java.util.Date(item.timestampMillis)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Front: $frontStr",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = "Rear: $rearStr",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
@Composable
private fun TargetRangeDialog(
    bike: Bike,
    unit: PressureUnit,
    onSave: (Float?, Float?, Float?, Float?) -> Unit,
    onDismiss: () -> Unit
) {
    fun initialText(psi: Float?): String = when {
        psi == null -> ""
        unit == PressureUnit.PSI -> String.format(Locale.UK, "%.1f", psi)
        else -> String.format(Locale.UK, "%.2f", psi * OutriderConfig.PSI_TO_BAR)
    }

    var frontMinText by remember { mutableStateOf(initialText(bike.frontMinPsi)) }
    var frontMaxText by remember { mutableStateOf(initialText(bike.frontMaxPsi)) }
    var rearMinText by remember { mutableStateOf(initialText(bike.rearMinPsi)) }
    var rearMaxText by remember { mutableStateOf(initialText(bike.rearMaxPsi)) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun parseToPsi(raw: String): Float? {
        val trimmed = raw.trim().replace(',', '.')
        if (trimmed.isEmpty()) return null
        val value = trimmed.toFloatOrNull() ?: return Float.NaN
        if (value <= 0f) return Float.NaN
        return unit.toPsi(value)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${bike.name} target range (${unit.label})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Leave blank for no limit on that bound.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                errorMsg?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    text = "Front",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = frontMinText,
                        onValueChange = { frontMinText = it },
                        label = { Text("Min") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = frontMaxText,
                        onValueChange = { frontMaxText = it },
                        label = { Text("Max") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = "Rear",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = rearMinText,
                        onValueChange = { rearMinText = it },
                        label = { Text("Min") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = rearMaxText,
                        onValueChange = { rearMaxText = it },
                        label = { Text("Max") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val fMin = parseToPsi(frontMinText)
                    val fMax = parseToPsi(frontMaxText)
                    val rMin = parseToPsi(rearMinText)
                    val rMax = parseToPsi(rearMaxText)

                    if (listOfNotNull(fMin, fMax, rMin, rMax).any { it.isNaN() }) {
                        errorMsg = "Enter valid positive numbers"
                        return@TextButton
                    }
                    if (fMin != null && fMax != null && fMin > fMax) {
                        errorMsg = "Front min cannot exceed max"
                        return@TextButton
                    }
                    if (rMin != null && rMax != null && rMin > rMax) {
                        errorMsg = "Rear min cannot exceed max"
                        return@TextButton
                    }
                    onSave(fMin, fMax, rMin, rMax)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun BikeNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(BikeStore.MAX_NAME_LENGTH) },
                label = { Text("Bike name") },
                singleLine = true
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DeleteBikeDialog(
    bike: Bike,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${bike.name}?") },
        text = {
            Text("PaleRider will forget this bike and unpair its sensors. You can add it and pair them again at any time.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ForgetSensorDialog(
    wheel: WheelId,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forget ${wheelLabel(context, wheel)} sensor?") },
        text = {
            Text("PaleRider will stop reading and watching for this sensor. You can pair it again at any time.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Forget") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PairSensorDialog(
    wheel: WheelId,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val discovery by SensorDiscovery.state.collectAsState()
    val name = wheelLabel(context, wheel)
    val position = wheelName(wheel.position)

    DisposableEffect(wheel) {
        SensorDiscovery.start(context, wheel)
        onDispose { SensorDiscovery.stop() }
    }

    LaunchedEffect(discovery.paired) {
        if (discovery.paired) onDismiss()
    }

    val verifying = discovery.verifying != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair $name sensor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (verifying)
                        "Checking the sensor — keep the wheel spinning."
                    else
                        "Hold your phone close to the $position wheel and spin it, then pick the sensor below.",
                    style = MaterialTheme.typography.bodyMedium
                )

                discovery.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (discovery.scanning || verifying) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = if (verifying) "Verifying…" else "Scanning…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    discovery.candidates.forEach { candidate ->
                        CandidateRow(
                            candidate = candidate,
                            enabled = !verifying,
                            onClick = { SensorDiscovery.select(context, candidate.address) }
                        )
                    }
                    if (discovery.scanning && discovery.candidates.isEmpty()) {
                        Text(
                            text = "Nothing heard yet — keep spinning the wheel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!discovery.scanning && !verifying && !discovery.paired) {
                TextButton(onClick = { SensorDiscovery.start(context, wheel) }) { Text("Scan again") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CandidateRow(
    candidate: SensorDiscovery.Candidate,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.name ?: "Unnamed device",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (candidate.likely) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    text = candidate.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                candidate.pairedTo?.let {
                    Text(
                        text = "Paired to $it — picking it moves it here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (candidate.likely) {
                    Text(
                        text = "Likely",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "${candidate.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun WheelPressureCard(
    title: String,
    reading: WheelReading,
    status: PressureStatus,
    rangeText: String?,
    paired: Boolean,
    unit: PressureUnit,
    pairEnabled: Boolean,
    forgetEnabled: Boolean,
    onPair: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOutOfRange = paired && status != PressureStatus.NORMAL
    val cardColor = if (isOutOfRange) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isOutOfRange) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor,
            contentColor = contentColor
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isOutOfRange) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                if (paired) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isOutOfRange) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.error
                            ) {
                                Text(
                                    text = status.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (reading.isConnected)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else
                                Color.Transparent
                        ) {
                            Text(
                                text = if (reading.isConnected) "LIVE" else "",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (reading.isConnected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (!paired) {
                FilledTonalButton(
                    onClick = onPair,
                    enabled = pairEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pair")
                }
            } else {
                val value = unit.format(reading.psi)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 44.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isOutOfRange) contentColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                if (rangeText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = rangeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOutOfRange) contentColor.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                TextButton(
                    onClick = onForget,
                    enabled = forgetEnabled,
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    Text("Forget")
                }
            }
        }
    }
}

private fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean =
    permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }