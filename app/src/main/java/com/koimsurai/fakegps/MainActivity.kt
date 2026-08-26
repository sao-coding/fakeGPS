// Author: koimsurai

package com.koimsurai.fakegps

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.koimsurai.fakegps.ui.theme.CoordinateTextStyle
import com.koimsurai.fakegps.ui.theme.FakeGpsTheme
import com.koimsurai.fakegps.ui.theme.LiveCoordinateTextStyle
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val permissionsRequestCode = 1

    private var isBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isBound = true
            viewModel.setServiceState(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            viewModel.setServiceState(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the status and navigation bars — the map is the hero and runs edge to edge.
        // The default SystemBarStyle.auto() picks bar icon/scrim contrast from the system's own
        // light/dark setting, which is exactly right now that FakeGpsTheme follows it too (see
        // Theme.kt) — the bars and the app content always agree on which mode they're in.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Handle permissions
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            "android.permission.ACCESS_MOCK_LOCATION",
            Manifest.permission.POST_NOTIFICATIONS
        )
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), permissionsRequestCode)
        }

        // Load osmdroid configuration
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))

        viewModel.loadFavorites(this)
        viewModel.loadLastLocation(this)
        viewModel.loadJitterSettings(this)

        setContent {
            FakeGpsTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val isRunning = isServiceRunning()
        if (isRunning) {
            Intent(this, MockLocationService::class.java).also { intent ->
                bindService(intent, serviceConnection, 0)
            }
        } else {
            viewModel.clearStaleMockProvider(this)
        }
        viewModel.setServiceState(isRunning)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveLastLocation(this)
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (MockLocationService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scaffoldState = rememberBottomSheetScaffoldState()
    var showAddFavoriteDialog by remember { mutableStateOf(false) }
    var favoriteToDelete by remember { mutableStateOf<String?>(null) }
    var preflightIssues by remember { mutableStateOf<List<PreflightIssue>>(emptyList()) }
    // The peek must clear the gesture bar as well as the content, or the primary button ends up
    // sitting under the navigation bar.
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // A hardcoded peek height needs a separate guess for every content combination (mocking on/off
    // changes whether the live-position row is present) and is exactly the kind of number that's
    // wrong by a few dp in one direction or the other. Instead, the "peek section" below measures
    // its own real height and reports it here; the peek is then sized to that, plus one fixed,
    // intentional gap (PEEK_BOTTOM_GAP) — so the sheet always ends flush just past the button,
    // never short (button clipped against the screen edge) and never long (dead space above the
    // nav bar), regardless of what's currently showing.
    val density = LocalDensity.current
    var peekSectionHeight by remember { mutableStateOf(0.dp) }
    val peekHeight = SHEET_DRAG_HANDLE_HEIGHT + PEEK_TOP_PADDING + peekSectionHeight + PEEK_BOTTOM_GAP

    if (showAddFavoriteDialog) {
        AddFavoriteDialog(
            existingNames = uiState.favorites.keys,
            onDismiss = { showAddFavoriteDialog = false },
            onConfirm = { name ->
                viewModel.addFavorite(name, context)
                showAddFavoriteDialog = false
            }
        )
    }

    favoriteToDelete?.let { name ->
        DeleteFavoriteDialog(
            name = name,
            onDismiss = { favoriteToDelete = null },
            onConfirm = {
                viewModel.deleteFavorite(name, context)
                favoriteToDelete = null
            }
        )
    }

    if (preflightIssues.isNotEmpty()) {
        PreflightDialog(
            issues = preflightIssues,
            onDismiss = { preflightIssues = emptyList() }
        )
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight + navBarInset,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetShadowElevation = 16.dp,
        sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        // A fixed-height replacement for the default drag handle: sheetPeekHeight has to account
        // for the handle's own height, and the default's isn't a documented, stable number to add
        // into that math — this one is exactly SHEET_DRAG_HANDLE_HEIGHT, always.
        sheetDragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
        },
        sheetContent = {
            ControlSheet(
                uiState = uiState,
                onToggleMock = {
                    if (uiState.isMocking) {
                        viewModel.stopMockLocation(context)
                    } else {
                        // Checked before the tap does anything: GPS mocking needs location
                        // services on AND "allow mock locations" granted, and failing that only
                        // after starting the service produced the isMocking/state-desync bug this
                        // preflight replaces.
                        val issues = viewModel.checkMockingPreflight(context)
                        if (issues.isEmpty()) {
                            viewModel.startMockLocation(context)
                        } else {
                            preflightIssues = issues
                        }
                    }
                },
                onAddFavorite = { if (uiState.selectedPoint != null) showAddFavoriteDialog = true },
                onFavoriteSelected = { viewModel.selectPoint(it) },
                onFavoriteDeleteRequested = { favoriteToDelete = it },
                onJitterEnabledChange = { viewModel.setJitterEnabled(it, context) },
                onJitterRangeChange = { viewModel.setJitterRange(it, context) },
                onPeekSectionMeasured = { heightPx ->
                    peekSectionHeight = with(density) { heightPx.toDp() }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (uiState.mapVisible) {
                MapViewContainer(
                    selectedPoint = uiState.selectedPoint,
                    onPointSelected = { viewModel.selectPoint(it) }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.map_hidden_message),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SearchBarRow(
                value = uiState.searchInput,
                mapVisible = uiState.mapVisible,
                onValueChange = { viewModel.setSearchInput(it) },
                onSearch = { viewModel.searchAddress(context) },
                onToggleMap = { viewModel.toggleMapVisibility() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // Anchored just above the sheet, which is what innerPadding's bottom inset represents.
            LocateButton(
                isLocating = uiState.isLocating,
                onClick = { viewModel.useCurrentLocation(context) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = innerPadding.calculateBottomPadding() + 16.dp, end = 16.dp)
            )
        }
    }
}

// Kept in sync with the fixed sheetDragHandle supplied to BottomSheetScaffold and the outer
// Column's top padding below — see the peekHeight comment in MainScreen for why these are
// constants instead of being folded into a single guessed number.
private val SHEET_DRAG_HANDLE_HEIGHT = 28.dp
private val PEEK_TOP_PADDING = 4.dp
private val PEEK_BOTTOM_GAP = 20.dp

@Composable
private fun ControlSheet(
    uiState: MainUiState,
    onToggleMock: () -> Unit,
    onAddFavorite: () -> Unit,
    onFavoriteSelected: (GeoPoint) -> Unit,
    onFavoriteDeleteRequested: (String) -> Unit,
    onJitterEnabledChange: (Boolean) -> Unit,
    onJitterRangeChange: (Float) -> Unit,
    onPeekSectionMeasured: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .padding(top = PEEK_TOP_PADDING, bottom = 24.dp)
    ) {
        // Everything through the primary button is what's visible when the sheet is collapsed —
        // measuring exactly this block is what lets the peek height in MainScreen match it exactly.
        Column(modifier = Modifier.onGloballyPositioned { onPeekSectionMeasured(it.size.height) }) {
            StatusRow(isMocking = uiState.isMocking, jitterEnabled = uiState.jitterEnabled, jitterRangeMeters = uiState.jitterRangeMeters)

            Spacer(Modifier.height(16.dp))
            CoordinateReadout(point = uiState.selectedPoint)

            // Proof that the jitter is doing something: the position actually going out to the
            // system, refreshed every second, next to how far it currently sits from the picked point.
            val livePoint by MockLocationBus.liveLocation.collectAsState()
            val basePoint = uiState.selectedPoint
            AnimatedVisibility(visible = livePoint != null && basePoint != null) {
                if (livePoint != null && basePoint != null) {
                    LivePositionRow(base = basePoint, live = livePoint!!)
                }
            }

            Spacer(Modifier.height(20.dp))
            PrimaryActionButton(isMocking = uiState.isMocking, enabled = uiState.selectedPoint != null, onClick = onToggleMock)
        }

        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Spacer(Modifier.height(20.dp))
        JitterSection(
            enabled = uiState.jitterEnabled,
            rangeMeters = uiState.jitterRangeMeters,
            onEnabledChange = onJitterEnabledChange,
            onRangeChange = onJitterRangeChange
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Spacer(Modifier.height(20.dp))
        FavoritesSection(
            favorites = uiState.favorites,
            canAdd = uiState.selectedPoint != null,
            onAdd = onAddFavorite,
            onSelected = onFavoriteSelected,
            onDeleteRequested = onFavoriteDeleteRequested
        )
    }
}

@Composable
private fun StatusRow(isMocking: Boolean, jitterEnabled: Boolean, jitterRangeMeters: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // A slow pulse is the clearest at-a-glance signal that mocking is live.
        val pulse = rememberInfiniteTransition(label = "statusPulse")
        val dotAlpha by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "dotAlpha"
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(if (isMocking) dotAlpha else 1f)
                .clip(CircleShape)
                .background(if (isMocking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (isMocking) stringResource(R.string.mocking_status_active) else stringResource(R.string.mocking_status_inactive),
            style = MaterialTheme.typography.titleMedium,
            color = if (isMocking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        if (jitterEnabled) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = stringResource(R.string.jitter_chip_format, jitterRangeMeters),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun CoordinateReadout(point: GeoPoint?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        CoordinateColumn(
            label = stringResource(R.string.latitude_label),
            value = point?.latitude,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(16.dp))
        CoordinateColumn(
            label = stringResource(R.string.longitude_label),
            value = point?.longitude,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LivePositionRow(base: GeoPoint, live: GeoPoint) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.live_position_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.coordinates_format, live.latitude, live.longitude),
                    style = LiveCoordinateTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(
                    R.string.live_offset_format,
                    MockLocationBus.offsetMeters(base, live)
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun CoordinateColumn(label: String, value: Double?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value?.let { stringResource(R.string.coordinate_component_format, it) }
                ?: stringResource(R.string.no_location_selected),
            style = CoordinateTextStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PrimaryActionButton(isMocking: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        colors = if (isMocking) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
    ) {
        Icon(
            imageVector = if (isMocking) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (isMocking) stringResource(R.string.stop_mock_location) else stringResource(R.string.start_mock_location),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun JitterSection(
    enabled: Boolean,
    rangeMeters: Float,
    onEnabledChange: (Boolean) -> Unit,
    onRangeChange: (Float) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.jitter_switch_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.jitter_switch_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }

    AnimatedVisibility(visible = enabled) {
        Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val presets = listOf(
                JitterPreset.SMALL to R.string.jitter_range_small,
                JitterPreset.MEDIUM to R.string.jitter_range_medium,
                JitterPreset.LARGE to R.string.jitter_range_large
            )
            presets.forEach { (value, labelRes) ->
                FilterChip(
                    selected = rangeMeters == value,
                    onClick = { onRangeChange(value) },
                    shape = MaterialTheme.shapes.small,
                    label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
    }
}

// Above this many entries the list gets its own scroll instead of stretching the sheet forever —
// a heavy favorites list would otherwise push the jitter/status sections an arbitrary distance
// away and make the sheet itself unwieldy to drag.
private const val FAVORITES_VISIBLE_ROWS = 4
private val FAVORITES_ROW_HEIGHT = 72.dp

@Composable
private fun FavoritesSection(
    favorites: Map<String, GeoPoint>,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onSelected: (GeoPoint) -> Unit,
    onDeleteRequested: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.favorites_title) +
                if (favorites.isNotEmpty()) stringResource(R.string.favorites_count_suffix_format, favorites.size) else "",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        FilledTonalButton(
            onClick = onAdd,
            enabled = canAdd,
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.quick_action_add), style = MaterialTheme.typography.labelLarge)
        }
    }

    Spacer(Modifier.height(8.dp))

    if (favorites.isEmpty()) {
        Text(
            text = stringResource(R.string.favorites_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    } else {
        val entries = remember(favorites) { favorites.toList() }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = FAVORITES_ROW_HEIGHT * FAVORITES_VISIBLE_ROWS),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(entries, key = { it.first }) { (name, geoPoint) ->
                FavoriteRow(name = name, geoPoint = geoPoint, onSelected = onSelected, onDeleteRequested = onDeleteRequested)
            }
        }
    }
}

@Composable
private fun FavoriteRow(
    name: String,
    geoPoint: GeoPoint,
    onSelected: (GeoPoint) -> Unit,
    onDeleteRequested: (String) -> Unit
) {
    Surface(
        onClick = { onSelected(geoPoint) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = FAVORITES_ROW_HEIGHT - 8.dp)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = stringResource(R.string.coordinates_format, geoPoint.latitude, geoPoint.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onDeleteRequested(name) }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_favorite),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SearchBarRow(
    value: String,
    mapVisible: Boolean,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onToggleMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            stringResource(R.string.search_address_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                FilledIconButton(
                    onClick = onSearch,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Surface(
            onClick = onToggleMap,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 6.dp,
            modifier = Modifier.size(52.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Map,
                    contentDescription = stringResource(R.string.toggle_map_visibility),
                    tint = if (mapVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun LocateButton(isLocating: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp)
    ) {
        if (isLocating) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.MyLocation,
                contentDescription = stringResource(R.string.use_current_location),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun AddFavoriteDialog(
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val trimmedName = name.trim()
    val isDuplicate = trimmedName.isNotEmpty() && existingNames.any { it.equals(trimmedName, ignoreCase = true) }
    // Blank is simply disabled rather than shown as an error — an empty required field on first
    // open isn't a mistake yet, it just hasn't been filled in.
    val isValid = trimmedName.isNotEmpty() && !isDuplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.add_favorite_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.favorite_name_hint)) },
                    shape = MaterialTheme.shapes.small,
                    singleLine = true,
                    isError = isDuplicate,
                    supportingText = {
                        if (isDuplicate) {
                            Text(
                                stringResource(R.string.favorite_name_duplicate_error),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmedName) },
                enabled = isValid,
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun DeleteFavoriteDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.delete_favorite_confirm_title)) },
        text = { Text(stringResource(R.string.delete_favorite_confirm_message, name)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun PreflightDialog(
    issues: List<PreflightIssue>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.preflight_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                issues.forEach { issue -> PreflightIssueRow(issue) { openPreflightSettings(context, issue) } }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun PreflightIssueRow(issue: PreflightIssue, onOpenSettings: () -> Unit) {
    val (message, actionLabel) = when (issue) {
        PreflightIssue.LOCATION_SERVICES_OFF ->
            stringResource(R.string.preflight_location_off) to stringResource(R.string.preflight_location_off_action)
        PreflightIssue.MOCK_LOCATION_NOT_ALLOWED ->
            stringResource(R.string.preflight_mock_not_allowed) to stringResource(R.string.preflight_mock_not_allowed_action)
    }
    Column {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onOpenSettings, shape = MaterialTheme.shapes.small) {
            Text(actionLabel)
        }
    }
}

/** No public API deep-links to the exact "select mock location app" screen — Developer options is as close as it gets. */
private fun openPreflightSettings(context: Context, issue: PreflightIssue) {
    val action = when (issue) {
        PreflightIssue.LOCATION_SERVICES_OFF -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        PreflightIssue.MOCK_LOCATION_NOT_ALLOWED -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
    }
    try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun MapViewContainer(
    selectedPoint: GeoPoint?,
    onPointSelected: (GeoPoint) -> Unit
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    // Marker needs to be remembered as well
    val marker = remember { Marker(mapView) }

    AndroidView(
        factory = {
            mapView.apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(18.0)

                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.setOnMarkerClickListener { _, _ -> true } // Disable the info window
                marker.icon = ContextCompat.getDrawable(context, R.drawable.ic_marker)
                overlays.add(marker)

                val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        onPointSelected(p)
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint): Boolean {
                        return false
                    }
                })
                overlays.add(0, mapEventsOverlay)
            }
        },
        update = { view ->
            selectedPoint?.let {
                view.controller.animateTo(it)
                marker.position = it
            }
            view.invalidate()
        },
        modifier = Modifier.fillMaxSize()
    )

    // Set initial center using LaunchedEffect
    LaunchedEffect(Unit) {
        if (selectedPoint != null) {
            mapView.controller.setCenter(selectedPoint)
            marker.position = selectedPoint
        } else {
            // Default to Taipei 101 if no last location
            val defaultPoint = GeoPoint(25.0330, 121.5654)
            mapView.controller.setCenter(defaultPoint)
            marker.position = defaultPoint
            onPointSelected(defaultPoint)
        }
    }
}
