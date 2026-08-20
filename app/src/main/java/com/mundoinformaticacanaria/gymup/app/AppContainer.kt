package com.mundoinformaticacanaria.gymup.app

import android.content.Context
import com.mundoinformaticacanaria.gymup.data.preferences.UserPreferencesRepository

interface AppContainer {
    val userPreferencesRepository: UserPreferencesRepository
}

class DefaultAppContainer(context: Context) : AppContainer {
    override val userPreferencesRepository = UserPreferencesRepository(context.applicationContext)
}
