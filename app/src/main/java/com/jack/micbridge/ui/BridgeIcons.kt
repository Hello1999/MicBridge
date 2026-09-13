package com.jack.micbridge.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small, consistent outline icons; no font, network resource, or additional icon dependency. */
enum class BridgeSymbol { MIC, MIC_OFF, CONTROL, LINK, SETTINGS, SHIELD, CHECK, INFO, BACK, NEXT, COPY, CLOCK, WIFI, POWER, WARNING }

private val Symbols = BridgeSymbol.entries.associateWith { symbol ->
    ImageVector.Builder(symbol.name, 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            when (symbol) {
                BridgeSymbol.MIC, BridgeSymbol.MIC_OFF -> {
                    moveTo(9f, 11f); lineTo(9f, 6f); curveTo(9f, 2f, 15f, 2f, 15f, 6f); lineTo(15f, 11f); curveTo(15f, 15f, 9f, 15f, 9f, 11f)
                    moveTo(5f, 10f); lineTo(5f, 11f); curveTo(5f, 20f, 19f, 20f, 19f, 11f); lineTo(19f, 10f)
                    moveTo(12f, 18f); lineTo(12f, 22f); moveTo(8f, 22f); lineTo(16f, 22f)
                    if (symbol == BridgeSymbol.MIC_OFF) { moveTo(3f, 3f); lineTo(21f, 21f) }
                }
                BridgeSymbol.CONTROL -> {
                    moveTo(6f, 3f); lineTo(6f, 7f); moveTo(6f, 11f); lineTo(6f, 21f)
                    moveTo(12f, 3f); lineTo(12f, 13f); moveTo(12f, 17f); lineTo(12f, 21f)
                    moveTo(18f, 3f); lineTo(18f, 7f); moveTo(18f, 11f); lineTo(18f, 21f)
                    moveTo(3f, 9f); lineTo(9f, 9f); moveTo(9f, 15f); lineTo(15f, 15f); moveTo(15f, 9f); lineTo(21f, 9f)
                }
                BridgeSymbol.LINK -> {
                    moveTo(10f, 14f); lineTo(14f, 10f); moveTo(14f, 15f); lineTo(17f, 12f); curveTo(24f, 5f, 19f, 0f, 12f, 7f); lineTo(9f, 10f)
                    moveTo(10f, 9f); lineTo(7f, 12f); curveTo(0f, 19f, 5f, 24f, 12f, 17f); lineTo(15f, 14f)
                }
                BridgeSymbol.SETTINGS -> {
                    moveTo(12f, 3f); lineTo(20f, 7.5f); lineTo(20f, 16.5f); lineTo(12f, 21f); lineTo(4f, 16.5f); lineTo(4f, 7.5f); close()
                    moveTo(16f, 12f); curveTo(16f, 17.3f, 8f, 17.3f, 8f, 12f); curveTo(8f, 6.7f, 16f, 6.7f, 16f, 12f); close()
                }
                BridgeSymbol.SHIELD -> {
                    moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 12f); curveTo(20f, 17f, 15f, 20f, 12f, 22f); curveTo(9f, 20f, 4f, 17f, 4f, 12f); lineTo(4f, 6f); close()
                    moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 10f)
                }
                BridgeSymbol.CHECK -> { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 7f) }
                BridgeSymbol.INFO -> {
                    moveTo(21f, 12f); curveTo(21f, 24f, 3f, 24f, 3f, 12f); curveTo(3f, 0f, 21f, 0f, 21f, 12f); close()
                    moveTo(12f, 11f); lineTo(12f, 17f); moveTo(12f, 7f); lineTo(12.01f, 7f)
                }
                BridgeSymbol.BACK -> { moveTo(14f, 5f); lineTo(7f, 12f); lineTo(14f, 19f) }
                BridgeSymbol.NEXT -> { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
                BridgeSymbol.COPY -> {
                    moveTo(9f, 8f); lineTo(20f, 8f); lineTo(20f, 21f); lineTo(9f, 21f); close()
                    moveTo(5f, 16f); lineTo(4f, 16f); lineTo(4f, 3f); lineTo(15f, 3f); lineTo(15f, 4f)
                }
                BridgeSymbol.CLOCK -> {
                    moveTo(21f, 12f); curveTo(21f, 24f, 3f, 24f, 3f, 12f); curveTo(3f, 0f, 21f, 0f, 21f, 12f); close()
                    moveTo(12f, 6f); lineTo(12f, 12f); lineTo(16f, 14f)
                }
                BridgeSymbol.WIFI -> {
                    moveTo(3f, 8f); curveTo(8f, 3f, 16f, 3f, 21f, 8f)
                    moveTo(6f, 12f); curveTo(10f, 8f, 14f, 8f, 18f, 12f)
                    moveTo(9f, 16f); curveTo(11f, 14f, 13f, 14f, 15f, 16f)
                    moveTo(12f, 20f); lineTo(12.01f, 20f)
                }
                BridgeSymbol.POWER -> {
                    moveTo(12f, 2f); lineTo(12f, 12f); moveTo(6f, 5f); curveTo(-3f, 13f, 7f, 26f, 17f, 19f); curveTo(22f, 15f, 22f, 9f, 18f, 5f)
                }
                BridgeSymbol.WARNING -> {
                    moveTo(12f, 3f); lineTo(22f, 21f); lineTo(2f, 21f); close()
                    moveTo(12f, 9f); lineTo(12f, 14f); moveTo(12f, 18f); lineTo(12.01f, 18f)
                }
            }
        }
    }.build()
}

@Composable
fun BridgeIcon(symbol: BridgeSymbol, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(Symbols.getValue(symbol), contentDescription = null, modifier = modifier, tint = tint)
}
