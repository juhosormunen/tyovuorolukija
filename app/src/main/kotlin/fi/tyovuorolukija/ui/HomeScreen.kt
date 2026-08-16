package fi.tyovuorolukija.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import fi.tyovuorolukija.ui.theme.ShiftColors

/**
 * Aloitusnäkymä. Kamera ei enää avaudu heti sovelluksen käynnistyessä — se vei
 * koko ruudun ja piilotti kaiken muun. Nyt reitit ovat näkyvissä ja skannaus on
 * niistä ensimmäinen ja korostetuin.
 */
@Composable
fun HomeScreen(
    undoable: UndoableBatch?,
    hasHistory: Boolean,
    onScan: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onTes: () -> Unit,
    onCleanup: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Lue työvuorolista valokuvasta, tarkista tulkinta ja vie vuorot kalenteriin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        MenuCard(
            icon = Icons.Default.PhotoCamera,
            accent = MaterialTheme.colorScheme.primary,
            title = "Skannaa työvuorolista",
            subtitle = "Ota kuva tulosteesta tai valitse kuva galleriasta",
            primary = true,
            onClick = onScan,
        )

        MenuCard(
            icon = Icons.Default.Insights,
            accent = ShiftColors.Night,
            title = "Historia ja tilastot",
            subtitle = if (hasHistory) {
                "Tunnit, lisät, palkkakehitys ja vuororytmi jaksoittain"
            } else {
                "Täyttyy kun tallennat ensimmäisen jakson kalenteriin"
            },
            enabled = hasHistory,
            onClick = onHistory,
        )

        MenuCard(
            icon = Icons.Default.Tune,
            accent = ShiftColors.Morning,
            title = "Asetukset",
            subtitle = "Kuukausipalkka, veroprosentti ja työaikakorvausten prosentit",
            onClick = onSettings,
        )

        MenuCard(
            icon = Icons.Default.Description,
            accent = ShiftColors.Evening,
            title = "Työehtosopimus",
            subtitle = "Mihin laskenta perustuu — pykälät, kellonajat ja prosentit",
            onClick = onTes,
        )

        MenuCard(
            icon = Icons.Default.DeleteSweep,
            accent = ShiftColors.Free,
            title = "Siivoa kalenteri",
            subtitle = "Poista sovelluksen luomat tapahtumat valitulta ajanjaksolta",
            onClick = onCleanup,
        )

        Text(
            "Versio ${fi.tyovuorolukija.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        // Virheen huomaa usein vasta kalenterista, joten kumous on tarjolla
        // vielä senkin jälkeen kun tallennusnäkymästä on poistuttu.
        undoable?.let {
            TextButton(onClick = onUndo, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                Text("  Kumoa edellinen tallennus (${it.calendarName})")
            }
        }
    }
}

@Composable
private fun MenuCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color(0xFF16302A))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
