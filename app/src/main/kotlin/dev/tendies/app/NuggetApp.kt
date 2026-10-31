package dev.tendies.app

import android.app.Application
import android.content.Context
import dev.tendies.app.data.CatalogRepository
import dev.tendies.app.data.Library
import dev.tendies.app.data.Prefs

/** Process-wide singletons shared by the UI and the live wallpaper service. */
class NuggetApp : Application() {
    lateinit var library: Library
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var catalog: CatalogRepository
        private set

    override fun onCreate() {
        super.onCreate()
        library = Library(this)
        prefs = Prefs(this)
        catalog = CatalogRepository(this, library)
    }
}

val Context.nuggetApp: NuggetApp get() = applicationContext as NuggetApp
