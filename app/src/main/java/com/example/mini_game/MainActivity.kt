package com.example.mini_game

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.mini_game.game.sensor.AccelerometerManager
import com.example.mini_game.game.sensor.ClapDetector
import com.example.mini_game.game.ui.GameScreen
import com.example.mini_game.ui.theme.MinigameTheme

class MainActivity : ComponentActivity() {
    private lateinit var accelerometerManager: AccelerometerManager
    private lateinit var clapDetector: ClapDetector

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* clap will simply be unavailable if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        accelerometerManager = AccelerometerManager(this)
        clapDetector = ClapDetector(this)

        if (!clapDetector.hasPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MinigameTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    GameScreen(
                        accelerometerManager = accelerometerManager,
                        clapDetector = clapDetector,
                        onGameEnd = { isVictory ->
                            if (isVictory) setResult(RESULT_OK) else setResult(RESULT_CANCELED)
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        accelerometerManager.stop()
        clapDetector.stop()
    }
}