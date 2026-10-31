package dev.tendies.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private var pendingView by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingView = intent
        setContent {
            NuggetTheme {
                val vm: AppViewModel = viewModel()
                ViewIntentImporter(vm)
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingView = intent
    }

    /** Handles "Open with Nugget Wallpapers" for .tendies/.batter files. */
    @Composable
    private fun ViewIntentImporter(vm: AppViewModel) {
        val i = pendingView
        LaunchedEffect(i) {
            val uri = i?.data
            if (i?.action == Intent.ACTION_VIEW && uri != null) {
                var name = uri.lastPathSegment ?: "wallpaper.tendies"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0 && c.moveToFirst()) name = c.getString(col) ?: name
                }
                vm.importUri(uri, name)
                pendingView = null
            }
        }
    }
}
