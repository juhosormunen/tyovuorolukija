package fi.tyovuorolukija.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brändin petroli on sama väri kuin sovellusikonin tausta.
private val Brand = Color(0xFF0F6E56)
private val BrandLight = Color(0xFF7FCFC8)

private val LightColors = lightColorScheme(
    primary = Brand,
    secondary = Color(0xFF4A635F),
)

private val DarkColors = darkColorScheme(
    primary = BrandLight,
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
