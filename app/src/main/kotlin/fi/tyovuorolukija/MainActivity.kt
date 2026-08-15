package fi.tyovuorolukija

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.tyovuorolukija.ui.CaptureScreen
import fi.tyovuorolukija.ui.CleanupScreen
import fi.tyovuorolukija.ui.HomeScreen
import fi.tyovuorolukija.ui.SettingsScreen
import fi.tyovuorolukija.ui.TesScreen
import fi.tyovuorolukija.ui.HistoryScreen
import fi.tyovuorolukija.ui.MainViewModel
import fi.tyovuorolukija.ui.ReviewScreen
import fi.tyovuorolukija.ui.UiState
import fi.tyovuorolukija.ui.theme.TyovuorolukijaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TyovuorolukijaTheme {
                AppRoot()
            }
        }
    }
}

private val CALENDAR_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var hasCamera by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var hasCalendar by remember { mutableStateOf(CALENDAR_PERMISSIONS.all(::granted)) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { hasCamera = it }

    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasCalendar = result.values.all { it }
        if (hasCalendar) viewModel.loadCalendars()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::scan) }

    // Kalenteriluvat kysytään vasta kun vuorot on tunnistettu — silloin käyttäjä
    // näkee mihin lupaa tarvitaan.
    LaunchedEffect(state) {
        if (state is UiState.Review && !hasCalendar) {
            calendarLauncher.launch(CALENDAR_PERMISSIONS)
        }
    }

    // Otsikko tulee palkista, ei näkymän sisältä — muuten sama sana toistuisi kahdesti.
    val title = when (state) {
        is UiState.Home -> stringResource(R.string.app_name)
        is UiState.Scanning -> "Skannaa"
        is UiState.Settings -> "Asetukset"
        is UiState.Tes -> "Työehtosopimus"
        is UiState.Cleanup -> "Siivoa kalenteri"
        is UiState.History -> "Historia ja tilastot"
        is UiState.Review -> "Tarkista vuorot"
        is UiState.Working -> stringResource(R.string.app_name)
        is UiState.Done -> "Valmis"
        is UiState.Failed -> stringResource(R.string.app_name)
    }

    // Aloitusnäkymää lukuun ottamatta jokaisesta näkymästä pääsee takaisin sekä
    // palkin nuolesta että laitteen takaisin-eleellä. Aiemmin paluu oli vain
    // yksittäisten näkymien omien painikkeiden varassa, ja osasta se puuttui.
    val atHome = state is UiState.Home
    BackHandler(enabled = !atHome) { viewModel.reset() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (!atHome) {
                        IconButton(onClick = viewModel::reset) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Takaisin aloitusnäkymään",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding)

        when (val s = state) {
            is UiState.Home -> HomeScreen(
                undoable = s.undoable,
                hasHistory = s.hasHistory,
                onScan = viewModel::openScan,
                onHistory = viewModel::openHistory,
                onSettings = viewModel::openSettings,
                onTes = viewModel::openTes,
                onCleanup = viewModel::openCleanup,
                onUndo = viewModel::undo,
                modifier = content,
            )

            is UiState.Scanning -> CaptureScreen(
                hasCameraPermission = hasCamera,
                onRequestCameraPermission = {
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                },
                onPickFromGallery = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onImage = viewModel::scan,
                onBack = viewModel::reset,
                modifier = content,
            )

            is UiState.Settings -> SettingsScreen(
                form = s.payForm,
                onFormChange = viewModel::updatePayForm,
                onBack = viewModel::reset,
                modifier = content,
            )

            is UiState.Cleanup -> CleanupScreen(
                state = s,
                onRangeChange = viewModel::updateCleanupRange,
                onSelectCalendar = viewModel::selectCleanupCalendar,
                onSearch = viewModel::searchCleanup,
                onDelete = viewModel::deleteCleanup,
                onBack = viewModel::reset,
                modifier = content,
            )

            is UiState.Tes -> TesScreen(
                form = s.payForm,
                onBack = viewModel::reset,
                modifier = content,
            )

            is UiState.History -> HistoryScreen(
                periods = s.periods,
                years = s.years,
                days = s.days,
                totals = s.totals,
                onBack = viewModel::reset,
                modifier = content,
            )

            is UiState.Working -> Centered(content) {
                CircularProgressIndicator()
                Text(s.step, style = MaterialTheme.typography.bodyLarge)
            }

            is UiState.Review -> ReviewScreen(
                state = s,
                onRowChange = viewModel::updateRow,
                onPayFormChange = viewModel::updatePayForm,
                onSelectCalendar = viewModel::selectCalendar,
                onSave = viewModel::save,
                onCancel = viewModel::reset,
                modifier = content,
            )

            is UiState.Done -> Centered(content) {
                val undone = s.undone
                if (undone == null) {
                    Text("Valmis", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Kalenteriin \"${s.calendarName}\": " +
                            "${s.summary.inserted} lisätty, ${s.summary.updated} päivitetty, " +
                            "${s.summary.deleted} poistettu.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (s.summary.failed.isNotEmpty()) {
                        Text(
                            "Epäonnistui:\n" + s.summary.failed.joinToString("\n"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (s.summary.changeCount > 0) {
                        OutlinedButton(onClick = viewModel::undo, enabled = !s.undoing) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                            Text(if (s.undoing) "  Kumotaan…" else "  Kumoa tallennus")
                        }
                    }
                } else {
                    Text("Kumottu", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${undone.removed} tapahtumaa poistettu, " +
                            "${undone.restored} palautettu ennalleen.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (undone.failed.isNotEmpty()) {
                        Text(
                            "Epäonnistui:\n" + undone.failed.joinToString("\n"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Button(onClick = viewModel::reset) { Text("Takaisin alkuun") }
                OutlinedButton(onClick = viewModel::openScan) { Text("Skannaa uusi lista") }
            }

            is UiState.Failed -> Centered(content) {
                Text("Ei onnistunut", style = MaterialTheme.typography.headlineSmall)
                Text(s.message, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = viewModel::openScan) { Text("Yritä uudelleen") }
                OutlinedButton(onClick = viewModel::reset) { Text("Takaisin alkuun") }
            }
        }
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
