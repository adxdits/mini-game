package com.example.mini_game.game.engine

import com.example.mini_game.game.model.GameResult
import com.example.mini_game.game.model.Platform
import com.example.mini_game.game.model.PlatformType
import com.example.mini_game.game.model.Pokeball
import kotlin.math.max
import kotlin.random.Random

/**
 * Core game engine handling all game logic, physics, and state.
 * Operates in world coordinates where Y increases downward.
 */
class GameEngine {

    // Constants
    companion object {
        const val GRAVITY = 1500f           // px/s² (was 1800 – easier)
        const val BOUNCE_VELOCITY = -1050f  // normal bounce
        const val BOOST_VELOCITY = -1400f   // boost platform
        const val MAX_VX = 600f
        const val SENSOR_SENSITIVITY = 500f
        const val SENSOR_SMOOTHING = 0.15f  // low-pass filter factor
        const val CAMERA_LERP = 4f          // camera smooth speed
        const val MOVING_SPEED = 120f
        const val GAME_DURATION = 30f     
        const val WIN_SCORE = 1000
        const val PLATFORM_SPACING = 120f
        const val CLAP_BOOST_VELOCITY = -1700f
        const val CLAP_HINT_AFTER_SECONDS = 10  // show hint after this many seconds without a clap
    }

    // Game state
    var pokeball = Pokeball()
        private set
    var platforms = mutableListOf<Platform>()
        private set
    var score = 0
        private set
    var timeRemaining = GAME_DURATION
        private set
    var cameraY = 0f
        private set
    var result = GameResult.PLAYING
        private set
    var screenWidth = 400f
        private set
    var screenHeight = 800f
        private set
    var ballRotation = 0f
        private set
    var secondsSinceClap = 0
        private set

    private var clapSpinTimer = 0f       // seconds remaining of fast spin
    private var lastClapElapsed = 0f     // elapsed time at last clap (or game start)

    private var highestY = 0f
    private var startTime = 0L
    private var lastFrameTime = 0L
    private var initialized = false
    private var smoothedTiltX = 0f
    private var targetCameraY = 0f

    fun init(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
        reset()
    }

    fun reset() {
        pokeball = Pokeball(
            x = screenWidth / 2f,
            y = screenHeight * 0.75f,
            vy = 0f
        )
        platforms.clear()
        score = 0
        timeRemaining = GAME_DURATION
        cameraY = 0f
        targetCameraY = 0f
        smoothedTiltX = 0f
        clapSpinTimer = 0f
        lastClapElapsed = 0f
        secondsSinceClap = 0
        ballRotation = 0f
        highestY = pokeball.y
        result = GameResult.PLAYING
        startTime = System.nanoTime()
        lastFrameTime = startTime
        initialized = true

        // Generate initial platforms filling the screen
        generateInitialPlatforms()
    }

    /**
     * Triggered when the user claps. Always gives a boost while playing.
     * Returns true if the clap was consumed.
     */
    fun consumeClap(): Boolean {
        if (result != GameResult.PLAYING) return false
        lastClapElapsed = GAME_DURATION - timeRemaining
        secondsSinceClap = 0
        pokeball.vy = CLAP_BOOST_VELOCITY
        clapSpinTimer = 0.8f
        return true
    }

    /**
     * Main update called every frame.
     * @param tiltX accelerometer X value (negative = tilt right on most devices)
     */
    fun update(tiltX: Float) {
        if (!initialized || result != GameResult.PLAYING) return

        val now = System.nanoTime()
        val dt = ((now - lastFrameTime) / 1_000_000_000f).coerceAtMost(0.05f) // cap at 50ms
        lastFrameTime = now

        // Timer
        timeRemaining = (GAME_DURATION - (now - startTime) / 1_000_000_000f).coerceAtLeast(0f)
        if (timeRemaining <= 0f) {
            result = GameResult.DEFEAT
            return
        }

        // Track inactivity since last clap (used to surface a hint)
        val elapsed = GAME_DURATION - timeRemaining
        secondsSinceClap = (elapsed - lastClapElapsed).toInt().coerceAtLeast(0)

        // Ball rotation: spin fast during clap boost, gentle spin otherwise based on velocity
        if (clapSpinTimer > 0f) {
            clapSpinTimer -= dt
            ballRotation += 1500f * dt   // fast spin
        } else {
            ballRotation += pokeball.vx * dt * 0.5f
        }

        // Physics - apply gravity
        pokeball.vy += GRAVITY * dt

        // Smooth sensor input with low-pass filter
        smoothedTiltX += (tiltX - smoothedTiltX) * SENSOR_SMOOTHING

        // Dead zone: ignore tiny tilts (hand tremor)
        val effectiveTilt = if (kotlin.math.abs(smoothedTiltX) < 0.3f) 0f else smoothedTiltX

        // Horizontal movement from smoothed sensor
        pokeball.vx = (-effectiveTilt * SENSOR_SENSITIVITY).coerceIn(-MAX_VX, MAX_VX)

        // Move pokeball
        pokeball.x += pokeball.vx * dt
        pokeball.y += pokeball.vy * dt

        // Wrap horizontal
        if (pokeball.x < -pokeball.radius) pokeball.x = screenWidth + pokeball.radius
        if (pokeball.x > screenWidth + pokeball.radius) pokeball.x = -pokeball.radius

        // Collision detection (only when falling)
        if (pokeball.vy > 0) {
            for (platform in platforms) {
                if (!platform.isActive) continue
                if (checkCollision(pokeball, platform)) {
                    handleBounce(platform)
                    break
                }
            }
        }

        // Update moving platforms
        for (platform in platforms) {
            if (platform.type == PlatformType.MOVING) {
                if (platform.movingRight) {
                    platform.x += MOVING_SPEED * dt
                    if (platform.x + platform.width > screenWidth) platform.movingRight = false
                } else {
                    platform.x -= MOVING_SPEED * dt
                    if (platform.x < 0) platform.movingRight = true
                }
            }
        }

        // Camera follows the ball upward with smooth lerp
        val desiredCameraY = pokeball.y - screenHeight * 0.4f
        if (desiredCameraY < targetCameraY) {
            targetCameraY = desiredCameraY
        }
        cameraY += (targetCameraY - cameraY) * (CAMERA_LERP * dt).coerceAtMost(1f)

        // Score based on height climbed
        if (pokeball.y < highestY) {
            highestY = pokeball.y
            score = ((screenHeight * 0.7f - highestY) / 5f).toInt().coerceAtLeast(0)
        }

        // Check win
        if (score >= WIN_SCORE) {
            result = GameResult.VICTORY
            return
        }

        // Check loss (fell below camera view)
        if (pokeball.y > cameraY + screenHeight + 100f) {
            result = GameResult.DEFEAT
            return
        }

        // Generate new platforms above and remove ones below
        managePlatforms()
    }

    private fun checkCollision(ball: Pokeball, platform: Platform): Boolean {
        val ballBottom = ball.y + ball.radius
        val ballPrevBottom = ballBottom - ball.vy * 0.016f // approximate previous position
        return ballBottom >= platform.y &&
                ballPrevBottom <= platform.y + platform.height &&
                ball.x + ball.radius > platform.x &&
                ball.x - ball.radius < platform.x + platform.width
    }

    private fun handleBounce(platform: Platform) {
        // Platforms catch the ball but do NOT auto-launch.
        // Only claps/taps propel the ball upward.
        pokeball.vy = 0f
        if (platform.type == PlatformType.BREAKABLE) {
            platform.isActive = false
        }
        pokeball.y = platform.y - pokeball.radius
    }

    private fun generateInitialPlatforms() {
        // Wide floor platform directly under the ball so first bounce is guaranteed
        val floorY = screenHeight * 0.8f
        platforms.add(Platform(
            x = 0f,
            y = floorY,
            width = screenWidth,
            height = 20f,
            type = PlatformType.NORMAL
        ))

        // Guaranteed platforms at fixed pixel gaps – each reachable with BOUNCE_VELOCITY
        // max reach = v² / (2g) = 1000² / (2*1800) ≈ 278px, use 220px steps to be safe
        val stepY = 220f
        for (i in 1..4) {
            val x = screenWidth * 0.1f + Random.nextFloat() * (screenWidth * 0.6f)
            platforms.add(Platform(
                x = x,
                y = floorY - stepY * i,
                width = 140f,
                type = PlatformType.NORMAL
            ))
        }

        // Rest of initial platforms
        var y = floorY - stepY * 5f
        while (y > -screenHeight) {
            val x = Random.nextFloat() * (screenWidth - 90f)
            platforms.add(Platform(
                x = x,
                y = y,
                type = randomPlatformType()
            ))
            y -= PLATFORM_SPACING + Random.nextFloat() * 60f
        }
    }

    private fun managePlatforms() {
        // Remove platforms far below camera
        platforms.removeAll { it.y > cameraY + screenHeight + 200f }

        // Generate above if needed
        val topPlatformY = platforms.minOfOrNull { it.y } ?: cameraY
        var y = topPlatformY - PLATFORM_SPACING - Random.nextFloat() * 60f
        while (y > cameraY - screenHeight) {
            val x = Random.nextFloat() * (screenWidth - 90f)
            platforms.add(Platform(
                x = x,
                y = y,
                type = randomPlatformType()
            ))
            y -= PLATFORM_SPACING + Random.nextFloat() * 60f
        }
    }

    private fun randomPlatformType(): PlatformType {
        val r = Random.nextFloat()
        return when {
            r < 0.55f -> PlatformType.NORMAL
            r < 0.75f -> PlatformType.BOOST
            r < 0.88f -> PlatformType.MOVING
            else -> PlatformType.BREAKABLE
        }
    }
}
