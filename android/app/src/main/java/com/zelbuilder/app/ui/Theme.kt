package com.zelbuilder.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

val ZelNavy = Color(0xFF080B1A)
val ZelDeep = Color(0xFF140B2E)
val ZelViolet = Color(0xFF7C4DFF)
val ZelCyan = Color(0xFF00E5FF)
val ZelPink = Color(0xFFFF4081)
val ZelGold = Color(0xFFFFD54F)
val ZelGreen = Color(0xFF69F0AE)

private val ZelColors = darkColorScheme(
    primary = ZelViolet,
    secondary = ZelCyan,
    tertiary = ZelGold,
    background = ZelNavy,
    surface = ZelDeep,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    error = ZelPink
)

@Composable
fun ZelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZelColors, typography = Typography(), content = content)
}

private fun DrawScope.orb(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.55f), Color.Transparent),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/** Dark gradient background with three slowly floating gradient orbs. */
@Composable
fun ZelBackground(content: @Composable BoxScope.() -> Unit) {
    val transition = rememberInfiniteTransition(label = "orbs")
    val a by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
        label = "orbA"
    )
    val b by transition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(13000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orbB"
    )
    val c by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(17000, easing = LinearEasing), RepeatMode.Reverse),
        label = "orbC"
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ZelDeep, ZelNavy)))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            orb(ZelViolet, Offset(size.width * (0.1f + 0.6f * a), size.height * (0.08f + 0.18f * b)), size.minDimension * 0.7f)
            orb(ZelCyan, Offset(size.width * (0.9f - 0.6f * b), size.height * (0.5f + 0.15f * c)), size.minDimension * 0.6f)
            orb(ZelPink, Offset(size.width * (0.2f + 0.5f * c), size.height * (0.92f - 0.15f * a)), size.minDimension * 0.55f)
        }
        content()
    }
}

/** Translucent glassmorphism card. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val base = modifier
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.05f))
            )
        )
        .border(
            1.dp,
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.05f))),
            shape
        )
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Column(clickable.padding(16.dp), content = content)
}

/** Gradient button with a spring press animation. */
@Composable
fun ZelButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: List<Color> = listOf(ZelViolet, ZelCyan),
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "btnScale"
    )
    val fill = if (enabled) colors else listOf(Color.Gray.copy(alpha = 0.35f), Color.Gray.copy(alpha = 0.35f))
    Box(
        modifier
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(fill))
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ZelField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions.Default,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = ZelCyan,
            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
            focusedLabelColor = ZelCyan,
            unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
            cursorColor = ZelCyan
        )
    )
}
