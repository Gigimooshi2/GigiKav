package uk.noammm.kav.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Look { DARK, OLED, LIGHT }

/** Theme packs: Kav's own look, a Google Maps-like Material look, and a Moovit-like look. */
enum class Pack(val accent: Color, val live: Color?) {
    KAV(DefaultAccent, null),
    GOOGLE(Color(0xFF4C8DF6), Color(0xFF34A853)),
    MOOVIT(Color(0xFFF27B35), Color(0xFF3DBA6A)),
}

object K {
    var look by mutableStateOf(Look.DARK)
    val light get() = look == Look.LIGHT
    var liquid by mutableStateOf(false)
    var pack by mutableStateOf(Pack.KAV)

    var bg by mutableStateOf(Color(0xFF101012))
    var surface1 by mutableStateOf(Color(0xFF202023))
    var surface2 by mutableStateOf(Color(0xFF2B2B30))
    var surface3 by mutableStateOf(Color(0xFF343439))
    var surface4 by mutableStateOf(Color(0xFF44444A))
    var border by mutableStateOf(Color(0xFF343439))
    var borderStrong by mutableStateOf(Color(0xFF74747D))
    var dim by mutableStateOf(Color(0xFF9C9CA5))
    var muted by mutableStateOf(Color(0xFFC7C7CE))
    var text by mutableStateOf(Color(0xFFF5F5F7))
    var plate by mutableStateOf(Color(0x0FFFFFFF))
    var plateStrong by mutableStateOf(Color(0x1FFFFFFF))
    var sunken by mutableStateOf(Color(0xFF19191C))
    val badgePlate get() = surface3
    val co2Pill get() = surface2

    val rCard = 22.dp
    val rControl = 16.dp
    val rPill = 999.dp

    var accent by mutableStateOf(DefaultAccent)
    // Text on the accent follows its brightness: dark on pale accents (Kav's), white on deep ones.
    val onAccent get() = if (accent.luminance() > 0.42f) Color(0xFF111114) else Color.White
    val route get() = accent
    val live get() = pack.live ?: accent
    var routeIdle by mutableStateOf(Color(0xFF7E7E87))
    var problem by mutableStateOf(Color(0xFFE7C17A))
    var critical by mutableStateOf(Color(0xFFEE929A))
    val scheduled get() = muted

    /** Corner radius for this pack: round controls (24+) stay round; cards follow the pack. */
    fun r(radius: Dp): Dp = when (pack) {
        Pack.KAV -> radius
        Pack.GOOGLE -> if (radius >= 24.dp) radius else radius + 4.dp
        Pack.MOOVIT -> if (radius >= 24.dp) radius else minOf(radius, 10.dp)
    }
    /** Hairline around panels; Google and Moovit use flat tonal surfaces instead. */
    val outlined get() = pack == Pack.KAV

    val gap1 = 4.dp; val gap2 = 8.dp; val gap3 = 12.dp
    val gap4 = 16.dp; val gap5 = 20.dp; val gap6 = 24.dp; val gap8 = 32.dp

    // Every colour has to be set for every look, or a switch leaves strays from the last one.
    fun applyTheme(chosen: Look) {
        look = chosen
        when (pack) {
            Pack.GOOGLE -> { googlePalette(chosen); return }
            Pack.MOOVIT -> { moovitPalette(chosen); return }
            Pack.KAV -> Unit
        }
        when (chosen) {
            Look.DARK -> {
                bg = Color(0xFF101012); surface1 = Color(0xFF202023)
                surface2 = Color(0xFF2B2B30); surface3 = Color(0xFF343439)
                surface4 = Color(0xFF44444A); border = Color(0xFF343439)
                borderStrong = Color(0xFF74747D); dim = Color(0xFF9C9CA5)
                muted = Color(0xFFC7C7CE); text = Color(0xFFF5F5F7)
                plate = Color(0x0FFFFFFF); plateStrong = Color(0x1FFFFFFF)
                sunken = Color(0xFF19191C); routeIdle = Color(0xFF7E7E87)
                problem = Color(0xFFE7C17A); critical = Color(0xFFEE929A)
            }
            Look.OLED -> {
                bg = Color(0xFF000000); surface1 = Color(0xFF151517)
                surface2 = Color(0xFF1E1E21); surface3 = Color(0xFF28282C)
                surface4 = Color(0xFF38383E); border = Color(0xFF222226)
                borderStrong = Color(0xFF6C6C75); dim = Color(0xFF9C9CA5)
                muted = Color(0xFFC7C7CE); text = Color(0xFFF5F5F7)
                plate = Color(0x14FFFFFF); plateStrong = Color(0x24FFFFFF)
                sunken = Color(0xFF0A0A0C); routeIdle = Color(0xFF7E7E87)
                problem = Color(0xFFE7C17A); critical = Color(0xFFEE929A)
            }
            Look.LIGHT -> {
                bg = Color(0xFFF6F6F3); surface1 = Color(0xFFEBEBE7)
                surface2 = Color(0xFFE0E0DC); surface3 = Color(0xFFD5D5D1)
                surface4 = Color(0xFFC3C3BF); border = Color(0xFFDADAD6)
                borderStrong = Color(0xFF97979F); dim = Color(0xFF6F6F78)
                muted = Color(0xFF494951); text = Color(0xFF16161A)
                plate = Color(0x0D000000); plateStrong = Color(0x1A000000)
                sunken = Color(0xFFEFEFEB); routeIdle = Color(0xFFA6A6AE)
                problem = Color(0xFF9A6A00); critical = Color(0xFFB3424E)
            }
        }
    }

    private fun palette(
        bg: Long, s1: Long, s2: Long, s3: Long, s4: Long, border: Long, borderStrong: Long,
        dim: Long, muted: Long, text: Long, sunken: Long, idle: Long, problem: Long, critical: Long, lightPlates: Boolean,
    ) {
        this.bg = Color(bg); surface1 = Color(s1); surface2 = Color(s2); surface3 = Color(s3); surface4 = Color(s4)
        this.border = Color(border); this.borderStrong = Color(borderStrong)
        this.dim = Color(dim); this.muted = Color(muted); this.text = Color(text)
        this.sunken = Color(sunken); routeIdle = Color(idle)
        this.problem = Color(problem); this.critical = Color(critical)
        plate = if (lightPlates) Color(0x0D000000) else Color(0x12FFFFFF)
        plateStrong = if (lightPlates) Color(0x1A000000) else Color(0x24FFFFFF)
    }

    // Google Maps / Material 3 greys.
    private fun googlePalette(l: Look) = when (l) {
        Look.DARK -> palette(0xFF202124, 0xFF2D2E31, 0xFF35363A, 0xFF3C4043, 0xFF4A4E52, 0xFF3C4043, 0xFF80868B,
            0xFF9AA0A6, 0xFFBDC1C6, 0xFFE8EAED, 0xFF1A1B1E, 0xFF80868B, 0xFFFDD663, 0xFFF28B82, false)
        Look.OLED -> palette(0xFF000000, 0xFF1C1C1E, 0xFF242528, 0xFF2D2E31, 0xFF3C4043, 0xFF2D2E31, 0xFF80868B,
            0xFF9AA0A6, 0xFFBDC1C6, 0xFFE8EAED, 0xFF0E0E10, 0xFF80868B, 0xFFFDD663, 0xFFF28B82, false)
        Look.LIGHT -> palette(0xFFFFFFFF, 0xFFF1F3F4, 0xFFE8EAED, 0xFFDADCE0, 0xFFBDC1C6, 0xFFDADCE0, 0xFF9AA0A6,
            0xFF5F6368, 0xFF3C4043, 0xFF202124, 0xFFF8F9FA, 0xFF9AA0A6, 0xFFB06000, 0xFFD93025, true)
    }

    // Moovit's charcoal (dark) and grey-with-white-cards (light).
    private fun moovitPalette(l: Look) = when (l) {
        Look.DARK -> palette(0xFF17181C, 0xFF232529, 0xFF2B2E33, 0xFF34373D, 0xFF43474E, 0xFF2E3136, 0xFF6E737B,
            0xFF9A9EA6, 0xFFC5C8CE, 0xFFF2F3F5, 0xFF121316, 0xFF6E737B, 0xFFF5C24C, 0xFFF0706A, false)
        Look.OLED -> palette(0xFF000000, 0xFF18191C, 0xFF212327, 0xFF2B2E33, 0xFF3A3D43, 0xFF23252A, 0xFF6E737B,
            0xFF9A9EA6, 0xFFC5C8CE, 0xFFF2F3F5, 0xFF0B0B0D, 0xFF6E737B, 0xFFF5C24C, 0xFFF0706A, false)
        Look.LIGHT -> palette(0xFFEEF0F2, 0xFFFFFFFF, 0xFFE4E6E9, 0xFFD6D9DD, 0xFFC0C4CA, 0xFFDDE0E4, 0xFF9AA0A8,
            0xFF6B7079, 0xFF3E434A, 0xFF1C1E21, 0xFFF6F7F8, 0xFFA3A8B0, 0xFFA86A00, 0xFFC93C37, true)
    }
}

object Shown {
    var co2 by mutableStateOf(false)
}

val Display get() = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
    fontSize = 21.sp, color = K.text,
)
val DisplayItalic get() = Display.copy(fontWeight = FontWeight.Medium)
val Mono get() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = K.muted)

private fun scheme() = (if (K.light) lightColorScheme() else darkColorScheme()).copy(
    primary = K.text, onPrimary = K.bg,
    primaryContainer = K.surface3, onPrimaryContainer = K.text,
    inversePrimary = K.surface4,
    secondary = K.muted, onSecondary = K.bg,
    secondaryContainer = K.surface2, onSecondaryContainer = K.text,
    tertiary = K.muted, onTertiary = K.bg,
    tertiaryContainer = K.surface2, onTertiaryContainer = K.text,
    background = K.bg, onBackground = K.text,
    surface = K.bg, onSurface = K.text,
    surfaceVariant = K.surface3, onSurfaceVariant = K.muted,
    surfaceTint = K.surface3,
    inverseSurface = K.muted, inverseOnSurface = K.bg,
    surfaceContainerLowest = K.bg, surfaceContainerLow = K.sunken,
    surfaceContainer = K.surface1, surfaceContainerHigh = K.surface2,
    surfaceContainerHighest = K.surface3,
    outline = K.border, outlineVariant = K.border,
    error = K.critical, onError = K.bg,
    errorContainer = K.surface3, onErrorContainer = K.critical,
)

@Composable
fun KavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme(),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 14.sp, color = K.text),
            bodyMedium = TextStyle(fontSize = 13.sp, color = K.text),
            bodySmall = TextStyle(fontSize = 11.sp, color = K.dim),
        ),
        content = content,
    )
}
