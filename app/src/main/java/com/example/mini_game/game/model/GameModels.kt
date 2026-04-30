package com.example.mini_game.game.model

// Pokéball model
data class Pokeball(
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val radius: Float = 24f
)

// Platform types
enum class PlatformType {
    NORMAL,
    BOOST,
    BREAKABLE,
    MOVING
}

// Platform model
data class Platform(
    var x: Float,
    var y: Float,
    val width: Float = 90f,
    val height: Float = 16f,
    val type: PlatformType = PlatformType.NORMAL,
    var isActive: Boolean = true,
    var movingRight: Boolean = true
)

// Game result for integration
enum class GameResult {
    VICTORY,
    DEFEAT,
    PLAYING
}
