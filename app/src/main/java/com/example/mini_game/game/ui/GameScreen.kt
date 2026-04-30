package com.example.mini_game.game.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mini_game.game.engine.GameEngine
import com.example.mini_game.game.model.GameResult
import com.example.mini_game.game.model.PlatformType
import com.example.mini_game.game.sensor.AccelerometerManager
import com.example.mini_game.game.sensor.ClapDetector
import kotlinx.coroutines.delay

private data class Star(val x: Float, val y: Float, val size: Float, val alpha: Float)
private val PlatformCorner = CornerRadius(6f, 6f)

private val ColorNormal = Color(0xFF4CAF50)
private val ColorBoost = Color(0xFFFFD700)
private val ColorBreakable = Color(0xFFE53935)
private val ColorMoving = Color(0xFF42A5F5)
private val ColorBallTop = Color(0xFFFF1744)
private val ColorBallBand = Color(0xFF333333)

@Composable
fun GameScreen(
    accelerometerManager: AccelerometerManager,
    clapDetector: ClapDetector,
    onGameEnd: (isVictory: Boolean) -> Unit = {}
) {
    val engine = remember { GameEngine() }

    // Single tick state - drives only the drawBehind invalidation
    val frameTick = remember { mutableIntStateOf(0) }

    // HUD state - only updates when integer values actually change
    val scoreState = remember { mutableIntStateOf(0) }
    val timeState = remember { mutableIntStateOf(20) }
    val resultState = remember { mutableStateOf(GameResult.PLAYING) }
    val showClapHintState = remember { mutableStateOf(false) }

    val stars = remember {
        val rng = java.util.Random(42L)
        List(40) {
            Star(
                x = rng.nextFloat(),
                y = rng.nextFloat() * 4f,
                size = rng.nextFloat() * 2f + 1f,
                alpha = 0.3f + rng.nextFloat() * 0.4f
            )
        }
    }

    val winnerAlpha = remember { Animatable(0f) }
    val pokeballLiftOff = remember { Animatable(0f) }
    var canvasReady by remember { mutableStateOf(false) }

    LaunchedEffect(canvasReady) {
        if (!canvasReady) return@LaunchedEffect
        accelerometerManager.start()
        clapDetector.onClap = { engine.consumeClap() }
        clapDetector.start()
        engine.reset()
        scoreState.intValue = 0
        timeState.intValue = 20
        resultState.value = GameResult.PLAYING

        while (engine.result == GameResult.PLAYING) {
            withFrameNanos { _ ->
                engine.update(accelerometerManager.tiltX)

                if (engine.score != scoreState.intValue) {
                    scoreState.intValue = engine.score
                }
                val newTime = engine.timeRemaining.toInt()
                if (newTime != timeState.intValue) {
                    timeState.intValue = newTime
                }
                val shouldShowHint = engine.secondsSinceClap >= GameEngine.CLAP_HINT_AFTER_SECONDS
                if (shouldShowHint != showClapHintState.value) {
                    showClapHintState.value = shouldShowHint
                }
                frameTick.intValue++
            }
        }

        resultState.value = engine.result
        accelerometerManager.stop()
        clapDetector.stop()
        clapDetector.onClap = null

        if (engine.result == GameResult.VICTORY) {
            winnerAlpha.animateTo(1f, tween(400))
            pokeballLiftOff.animateTo(1f, tween(1200))
            delay(1500)
            onGameEnd(true)
        } else {
            delay(1500)
            onGameEnd(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1B2A))
    ) {
        // Drawing surface - drawBehind isolates invalidation to the draw layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (!canvasReady) {
                        engine.init(size.width.toFloat(), size.height.toFloat())
                        canvasReady = true
                    }
                }
                .drawBehind {
                    // Subscribe to invalidation
                    frameTick.intValue
                    if (!canvasReady) return@drawBehind

                    val camY = engine.cameraY
                    val w = size.width
                    val h = size.height

                    // Stars (parallax)
                    for (i in stars.indices) {
                        val s = stars[i]
                        val sy = ((s.y * h + camY * 0.2f) % h + h) % h
                        drawCircle(
                            color = Color.White.copy(alpha = s.alpha),
                            radius = s.size,
                            center = Offset(s.x * w, sy)
                        )
                    }

                    // Platforms
                    val platforms = engine.platforms
                    for (i in platforms.indices) {
                        val p = platforms[i]
                        if (!p.isActive) continue
                        val screenY = p.y - camY
                        if (screenY < -50f || screenY > h + 50f) continue

                        val color = when (p.type) {
                            PlatformType.NORMAL -> ColorNormal
                            PlatformType.BOOST -> ColorBoost
                            PlatformType.BREAKABLE -> ColorBreakable
                            PlatformType.MOVING -> ColorMoving
                        }
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(p.x, screenY),
                            size = Size(p.width, p.height),
                            cornerRadius = PlatformCorner
                        )
                    }

                    // Pokéball with rotation
                    val ball = engine.pokeball
                    val ballR = ball.radius
                    val ballScreenY = ball.y - camY -
                        (if (resultState.value == GameResult.VICTORY)
                            pokeballLiftOff.value * h * 0.5f else 0f)

                    rotate(degrees = engine.ballRotation, pivot = Offset(ball.x, ballScreenY)) {
                        drawCircle(ColorBallTop, ballR, Offset(ball.x, ballScreenY))
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(ball.x - ballR, ballScreenY),
                            size = Size(ballR * 2f, ballR)
                        )
                        drawRect(
                            color = ColorBallBand,
                            topLeft = Offset(ball.x - ballR, ballScreenY - 2.5f),
                            size = Size(ballR * 2f, 5f)
                        )
                        drawCircle(ColorBallBand, ballR * 0.28f, Offset(ball.x, ballScreenY))
                        drawCircle(Color.White, ballR * 0.16f, Offset(ball.x, ballScreenY))
                    }
                }
        )

        HudOverlay(scoreState, timeState)
        ClapHint(showClapHintState)
        ResultOverlays(resultState, winnerAlpha, scoreState)
    }
}

@Composable
private fun HudOverlay(scoreState: MutableIntState, timeState: MutableIntState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 20.dp, end = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Score: ${scoreState.intValue}",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        val time = timeState.intValue
        Text(
            text = "${time}s",
            color = if (time < 5) Color(0xFFFF5252) else Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Goal: ${GameEngine.WIN_SCORE}",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
        )
    }
}

@Composable
private fun ClapHint(visibleState: MutableState<Boolean>) {
    if (!visibleState.value) return
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Clap to jump",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 130.dp)
        )
    }
}

@Composable
private fun ResultOverlays(
    resultState: MutableState<GameResult>,
    winnerAlpha: Animatable<Float, *>,
    scoreState: MutableIntState
) {
    when (resultState.value) {
        GameResult.VICTORY -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = winnerAlpha.value * 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "WINNER!",
                        color = Color(0xFFFFD700).copy(alpha = winnerAlpha.value),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Card Captured!",
                        color = Color.White.copy(alpha = winnerAlpha.value),
                        fontSize = 24.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
        GameResult.DEFEAT -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "GAME OVER",
                        color = Color(0xFFFF5252),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Score: ${scoreState.intValue} / 1000",
                        color = Color.White,
                        fontSize = 22.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
        GameResult.PLAYING -> {}
    }
}
