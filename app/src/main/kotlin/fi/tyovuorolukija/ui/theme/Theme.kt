package fi.tyovuorolukija.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Väriteema johdettu brändin petrolista (#0F6E56, sama kuin sovellusikonin tausta).
 *
 * Roolit on määriteltävä kokonaisuutena: pelkän `primary`-värin asettaminen jättää
 * `primaryContainer`-, `surface`- ja `onSurfaceVariant`-roolit Material 3:n
 * oletusliilaksi, jolloin korostetut kortit näyttävät liilalta vaikka painikkeet
 * ovat petrolia. Tämä oli todellinen vika, joka näkyi vasta laitteella.
 *
 * Pinnat ovat tarkoituksella lähes neutraaleja: graafien väripaletti on validoitu
 * juuri näitä pintoja vasten, ja voimakkaasti sävytetty pinta muuttaisi kontrastit.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F6E56),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F0D8),
    onPrimaryContainer = Color(0xFF002018),
    secondary = Color(0xFF4A635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8DF),
    onSecondaryContainer = Color(0xFF06201A),
    background = Color(0xFFFBFDFA),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFBFDFA),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDCE5E0),
    onSurfaceVariant = Color(0xFF414945),
    outline = Color(0xFF717975),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FCFC8),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF00513C),
    onPrimaryContainer = Color(0xFFA8F0D8),
    secondary = Color(0xFFB1CCC4),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334B45),
    onSecondaryContainer = Color(0xFFCCE8DF),
    background = Color(0xFF191C1B),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF191C1B),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF414945),
    onSurfaceVariant = Color(0xFFC0C9C3),
    outline = Color(0xFF8B938E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Graafien validoinnissa käytetyt pintavärit — pidä synkassa yllä olevien kanssa. */
object ChartSurfaces {
    const val LIGHT = "#FBFDFA"
    const val DARK = "#191C1B"
}

@Composable
fun TyovuorolukijaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
