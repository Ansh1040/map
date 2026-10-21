package com.drivealert.app.ui.licenses

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.drivealert.app.R
import com.drivealert.app.ui.components.BackTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LICENSE_DIR = "licenses"

/**
 * Open-source licences and data attribution shipped with the app (required by
 * MapLibre's BSD-2-Clause licence for binary redistribution, and by the ODbL).
 * Texts live in assets/licenses and are shown in file-name order.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var blocks by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { blocks = loadBlocks(context) }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.open_source_licences), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
        ) {
            items(blocks) { block ->
                Text(
                    block,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

/** Splits every licence file into paragraphs so long texts render lazily. */
private suspend fun loadBlocks(context: Context): List<String> = withContext(Dispatchers.IO) {
    val files = context.assets.list(LICENSE_DIR).orEmpty().sorted()
    files.flatMap { name ->
        val text = context.assets.open("$LICENSE_DIR/$name").bufferedReader().use { it.readText() }
        listOf("──────── $name ────────") + text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
