package fi.tyovuorolukija.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF1B5E5A)
private val TealLight = Color(0xFF7FCFC8)

private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = Color(0xFF4A635F),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    secondary = Color(0xFFB1CCC7),
)

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
