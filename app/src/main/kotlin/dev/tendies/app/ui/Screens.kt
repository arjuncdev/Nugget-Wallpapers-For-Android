package dev.tendies.app.ui

import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import dev.tendies.app.data.LibraryRecord
import dev.tendies.core.catalog.Catalog
import dev.tendies.core.catalog.CatalogEntry
import dev.tendies.core.catalog.Category
import dev.tendies.core.pkg.Compat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val msg by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(msg) {
        msg?.let { snack.showSnackbar(it); vm.message.value = null }
    }

    val open = detailId
    if (open != null) {
        BackHandler { detailId = null; vm.refreshLibrary() }
        DetailScreen(vm, open) { detailId = null; vm.refreshLibrary() }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                listOf("Browse", "Library", "About").forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("☰", "▣", "ⓘ")[i]) }, label = { Text(label) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> BrowseScreen(vm) { id -> detailId = id }
                1 -> LibraryScreen(vm) { id -> detailId = id }
                else -> AboutScreen()
            }
        }
    }
}

@Composable
private fun rememberGifLoader(): ImageLoader {
    val ctx = LocalContext.current
    return remember {
        ImageLoader.Builder(ctx)
            .components { if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
}

// ------------------------------------------------------------------------------------------------ browse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(vm: AppViewModel, openDetail: (String) -> Unit) {
    val category by vm.category.collectAsStateWithLifecycle()
    val state by vm.catalogState.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val installed by vm.library.collectAsStateWithLifecycle()
    val progress by vm.downloads.collectAsStateWithLifecycle()
    val loader = rememberGifLoader()
    val cats = Category.values().toList()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScrollableTabRow(selectedTabIndex = cats.indexOf(category), edgePadding = 8.dp) {
            cats.forEach { c -> Tab(selected = c == category, onClick = { vm.selectCategory(c) }, text = { Text(c.label) }) }
        }
        OutlinedTextField(
            value = query, onValueChange = { vm.setQuery(it) }, singleLine = true,
            label = { Text("Search by name or author") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        )
        when (val s = state) {
            is CatalogState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is CatalogState.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Couldn't load the catalog: " + s.message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.loadCatalog() }) { Text("Retry") }
            }
            is CatalogState.Ready -> {
                val q = query.trim()
                val list = s.entries.filter { q.isEmpty() || it.name.contains(q, true) || (it.authors ?: "").contains(q, true) }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(list, key = { it.path }) { e ->
                        val rec = installed.firstOrNull { it.source == e.path }
                        EntryCard(e, rec, progress[e.path], loader, onOpen = { rec?.let { openDetail(it.id) } }, onDownload = { vm.download(e) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: CatalogEntry, rec: LibraryRecord?, progress: Float?, loader: ImageLoader,
    onOpen: () -> Unit, onDownload: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            AsyncImage(
                model = Catalog.rawUrl(entry.previewPath), imageLoader = loader, contentDescription = entry.name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(0.55f)
            )
            Column(Modifier.padding(10.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.authors ?: " ", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                when {
                    !entry.category.renderable -> Text("Preview only: iOS passcode keypad skin, no Android equivalent", style = MaterialTheme.typography.labelSmall)
                    rec != null -> OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Open") }
                    progress != null -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Download") }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------- library

@Composable
fun LibraryScreen(vm: AppViewModel, openDetail: (String) -> Unit) {
    val ctx = LocalContext.current
    val records by vm.library.collectAsStateWithLifecycle()
    val compat by vm.compat.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var name = uri.lastPathSegment ?: "wallpaper.tendies"
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) name = c.getString(col) ?: name
            }
            vm.importUri(uri, name)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import file") }
        }
        Spacer(Modifier.height(8.dp))
        if (records.isEmpty()) {
            Text("Nothing here yet. Download a wallpaper from Browse, or import a .tendies / .batter file.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records, key = { it.id }) { r ->
                    Card(onClick = { openDetail(r.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                (if (r.isBatter) "Template (.batter)" else ".tendies") + (r.author?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall
                            )
                            CompatLine(compat[r.id])
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompatLine(c: Compat?) {
    val text = if (c == null) "Checking…" else when (c.level) {
        Compat.Level.FULL -> "✓ " + c.summary
        Compat.Level.STILL, Compat.Level.VIDEO -> "◐ " + c.summary
        Compat.Level.NONE -> "✕ " + c.summary
    }
    Text(text, style = MaterialTheme.typography.labelMedium)
}

// -------------------------------------------------------------------------------------------------- about

@Composable
fun AboutScreen() {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Nugget Wallpapers for Android", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Plays the PosterBoard wallpapers from github.com/SerStars/Nugget-Wallpapers. A .tendies file is a ZIP of Apple " +
                "PosterBoard descriptors whose animated content is Core Animation Markup Language (CAML) layer packages; this app " +
                "contains its own CAML parser and animation engine and renders it through a custom WallpaperService.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text("Supported", style = MaterialTheme.typography.titleMedium)
        Text(
            "• Layered animations (background / floating / foreground), springs, keyframes, shape morphing, image sequences\n" +
                "• Lock / Home / Sleep states and their transitions, light and dark appearance\n" +
                "• Particle emitters, replicators, gradients, masks, blend modes, colour filters, 3D transform layers\n" +
                "• Device-tilt parallax groups, batter templates (toggles, sliders, image slots)\n" +
                "• Static (Still) wallpapers, applied as an image to home / lock / both",
            style = MaterialTheme.typography.bodyMedium
        )
        Text("Not supported", style = MaterialTheme.typography.titleMedium)
        Text(
            "• Apple procedural posters (Mercury, Bokeh, Stripe) and Photos depth-effect posters\n" +
                "• Backdrop blur layers, mesh warps, SDF / material layers, text layers\n" +
                "• Foreground layers cannot sit above the Android clock (Android draws system UI over wallpapers)\n" +
                "• Passcode keypad themes (.passthm) have no Android equivalent",
            style = MaterialTheme.typography.bodyMedium
        )
        Text("Android notes", style = MaterialTheme.typography.titleMedium)
        Text(
            "Live wallpapers can't be applied silently. The app opens the system preview where you confirm; on Android 14+ devices " +
                "that support it you can choose lock screen, home screen or both there. Rendering pauses when the screen is off or the " +
                "wallpaper is hidden, and frames are capped at your chosen FPS.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text("Licence", style = MaterialTheme.typography.titleMedium)
        Text(
            "Code: GPL-3.0-or-later, matching the upstream repository. Wallpapers belong to their authors; Apple's own artwork " +
                "is not bundled and is downloaded on demand.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
