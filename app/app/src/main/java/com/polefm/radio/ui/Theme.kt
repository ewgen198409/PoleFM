package com.polefm.radio.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

object TvColors {
    val Background = Color(0xFF0B0E13)
    val Surface = Color(0xFF151B24)
    val Glass = Color(0x12FFFFFF)
    val GlassStrong = Color(0x1CFFFFFF)
    val Stroke = Color(0x33FFFFFF)
    val Accent = Color(0xFF5BA3E0)
    val AccentDeep = Color(0xFF3D8BCD)
    val Text = Color(0xFFDCE7F2)
    val TextDim = Color(0xFF93A5B8)
    val TextFaint = Color(0xFF607487)
    val Green = Color(0xFF3FB950)
    val Yellow = Color(0xFFD29922)
    val Red = Color(0xFFF85149)
}

@Composable
fun PoleFmTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = TvColors.Accent,
        onPrimary = Color(0xFF08121D),
        secondary = TvColors.Green,
        onSecondary = Color.Black,
        surface = TvColors.Surface,
        onSurface = TvColors.Text,
        background = TvColors.Background,
        onBackground = TvColors.Text,
        error = TvColors.Red,
        onError = Color.White,
        surfaceVariant = TvColors.Surface,
        onSurfaceVariant = TvColors.TextDim
    )
    val typography = Typography(
        displayLarge = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Bold),
        headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
        headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium),
        titleSmall = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 19.sp),
        bodyMedium = TextStyle(fontSize = 17.sp),
        bodySmall = TextStyle(fontSize = 15.sp),
        labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 16.sp),
        labelSmall = TextStyle(fontSize = 14.sp)
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp)
    )
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
