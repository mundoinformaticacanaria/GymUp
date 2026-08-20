package com.mundoinformaticacanaria.gymup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mundoinformaticacanaria.gymup.app.GymUpApp
import com.mundoinformaticacanaria.gymup.app.GymUpApplication

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as GymUpApplication).container
        setContent { GymUpApp(container = container) }
    }
}
