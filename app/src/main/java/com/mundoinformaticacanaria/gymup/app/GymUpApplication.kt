package com.mundoinformaticacanaria.gymup.app

import android.app.Application

class GymUpApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
