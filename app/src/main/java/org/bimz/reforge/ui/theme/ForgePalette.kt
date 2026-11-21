package org.bimz.reforge.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class ForgeColorTokens(
    val bg: Color,
    val surface: Color,
    val elevated: Color,
    val border: Color,
    val cyan: Color,
    val emerald: Color,
    val amber: Color,
    val rose: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val dockBg: Color,
    val dockSlotBg: Color,
    val dockIcon: Color,
    val dockIconSelected: Color
)

val DarkForgeTokens = ForgeColorTokens(
    bg = Color(0xFF06090E),
    surface = Color(0xFF0D121A),
    elevated = Color(0xFF141B26),
    border = Color(0xFF1E293B),
    cyan = Color(0xFF22D3EE),
    emerald = Color(0xFF10B981),
    amber = Color(0xFFF59E0B),
    rose = Color(0xFFF43F5E),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    dockBg = Color(0xFF151C26),
    dockSlotBg = Color(0xFF2E3E52),
    dockIcon = Color(0xFF94A3B8),
    dockIconSelected = Color(0xFF7DD3FC)
)

val LightForgeTokens = ForgeColorTokens(
    bg = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    elevated = Color(0xFFF1F5F9),
    border = Color(0xFFCBD5E1),
    cyan = Color(0xFF0369A1),
    emerald = Color(0xFF15803D),
    amber = Color(0xFFB45309),
    rose = Color(0xFFBE123C),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF334155),
    textMuted = Color(0xFF64748B),
    dockBg = Color(0xFFFFFFFF),
    dockSlotBg = Color(0xFFE0F2FE),
    dockIcon = Color(0xFF475569),
    dockIconSelected = Color(0xFF0369A1)
)

val LocalForgeColors = staticCompositionLocalOf { DarkForgeTokens }

val ForgeBg: Color
    @Composable @ReadOnlyComposable get() = LocalForgeColors.current.bg

val ForgeSurface: Color
    @Composable @ReadOnlyComposable get() = LocalForgeColors.current.surface

val ForgeElevated: Color
    @Composable @ReadOnlyComposable get() = LocalForgeColors.current.elevated

val ForgeBorder: Color
    @Composable @ReadOnlyComposable get() = LocalForgeColors.current.border

val ForgeCyan: Color
    @Composable @ReadOnlyComposable get() = LocalForgeColors.current.cyan

val ForgeEmerald: Color
    @Composable @ReadOnlyComposable get() = LocalForgeColors.current.emerald

val ForgeAmber: Color
    @Composable @ReadOnlyComposable get() = LocalForgeColors.current.amber

val ForgeRose: Color
    @Composable @ReadOnlyComposable get() = LocalForgeColors.current.rose

val ForgeTextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalForgeColors.current.textPrimary

val ForgeTextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalForgeColors.current.textSecondary

val ForgeTextMuted: Color
    @Composable @ReadOnlyComposable get() = LocalForgeColors.current.textMuted
