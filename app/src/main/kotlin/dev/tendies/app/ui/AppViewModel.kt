package dev.tendies.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tendies.app.NuggetApp
import dev.tendies.app.data.LibraryRecord
import dev.tendies.core.catalog.CatalogEntry
import dev.tendies.core.catalog.Category
import dev.tendies.core.pkg.BatterTemplate
import dev.tendies.core.pkg.Compat
import dev.tendies.core.pkg.CompatChecker
import dev.tendies.core.pkg.TendiesArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Failed(val message: String) : CatalogState
    data class Ready(val entries: List<CatalogEntry>) : CatalogState
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val nugget = application as NuggetApp

    val library = MutableStateFlow(nugget.library.list())
    val compat = MutableStateFlow<Map<String, Compat>>(emptyMap())
    val category = MutableStateFlow(Category.CUSTOM)
    val query = MutableStateFlow("")
    val catalogState = MutableStateFlow<CatalogState>(CatalogState.Loading)
    /** library-relative catalog path -> download progress 0..1 */
    val downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val message = MutableStateFlow<String?>(null)

    init {
        loadCatalog()
        analyse()
    }

    fun setQuery(q: String) { query.value = q }

    fun selectCategory(c: Category) {
        category.value = c
        loadCatalog()
    }

    fun loadCatalog() {
        viewModelScope.launch {
            catalogState.value = CatalogState.Loading
            catalogState.value = try {
                CatalogState.Ready(nugget.catalog.entries(category.value))
            } catch (e: Exception) {
                CatalogState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun refreshLibrary() {
        library.value = nugget.library.list()
        analyse()
    }

    fun download(entry: CatalogEntry) {
        if (downloads.value.containsKey(entry.path)) return
        viewModelScope.launch {
            downloads.value = downloads.value + (entry.path to 0f)
            try {
                nugget.catalog.download(entry) { p -> downloads.value = downloads.value + (entry.path to p) }
                refreshLibrary()
            } catch (e: Exception) {
                message.value = "Download failed: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                downloads.value = downloads.value - entry.path
            }
        }
    }

    fun importUri(uri: Uri, name: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(uri)!!.use { nugget.library.importStream(it, name) }
                }
                refreshLibrary()
                message.value = "Imported $name"
            } catch (e: Exception) {
                message.value = "Import failed: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun remove(id: String) {
        if (nugget.prefs.activeId == id) nugget.prefs.activeId = null
        nugget.library.remove(id)
        refreshLibrary()
    }

    /** Opens each installed archive once to describe what this port can do with it. */
    private fun analyse() {
        viewModelScope.launch(Dispatchers.Default) {
            val known = compat.value.toMutableMap()
            for (r in nugget.library.list()) {
                if (known.containsKey(r.id)) continue
                known[r.id] = describe(r)
                compat.value = known.toMap()
            }
        }
    }

    private fun describe(r: LibraryRecord): Compat = try {
        val f = nugget.library.fileOf(r)
        val archive = if (r.isBatter) { val t = BatterTemplate.open(f); t.apply(t.defaults()) } else TendiesArchive.open(f)
        archive.use { CompatChecker.check(it) }
    } catch (e: Exception) {
        Compat(Compat.Level.NONE, "Unreadable archive: " + (e.message ?: e.javaClass.simpleName))
    }
}
