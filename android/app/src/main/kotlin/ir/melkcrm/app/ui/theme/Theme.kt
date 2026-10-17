package ir.melkcrm.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ir.melkcrm.app.R

/** Vazirmatn (SIL Open Font License) covers Persian and Latin, so both languages share one typeface. */
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0B5C55),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2E6),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    secondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFF8A5100),
    tertiaryContainer = Color(0xFFFFDCBE),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFF8F9FC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AD5CA),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFA6F2E6),
    secondary = Color(0xFFB1CCC6),
    secondaryContainer = Color(0xFF334B47),
    tertiary = Color(0xFFFFB870),
    tertiaryContainer = Color(0xFF693C00),
)

private fun Typography.withFamily(f: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

/** Match class colours. Labels always accompany them; colour is never the only signal. */
object ScoreColors {
    val excellent = Color(0xFF1B7F3B)
    val strong = Color(0xFF2E6FA8)
    val possible = Color(0xFF9A6B00)
    val weak = Color(0xFF7A7A7A)
    val excluded = Color(0xFFB3261E)
}

@Composable
fun MelkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography().withFamily(Vazirmatn),
        content = content,
    )
}
