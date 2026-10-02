package ch.digitana.dienstplan.ui.settings

import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.digitana.dienstplan.R
import ch.digitana.dienstplan.ui.components.SectionHeader

/** Eine verwendete Komponente und ihre Lizenz. */
private data class Library(val name: String, val holder: String, val license: String)

/** Was in der App steckt (Bibliotheken der App und der MLS-Schicht). */
private val LIBRARIES = listOf(
    Library("Inter (Schrift)", "The Inter Project Authors", "SIL Open Font License 1.1"),
    Library("Material Icons", "Google LLC", "Apache License 2.0"),
    Library("AndroidX, Jetpack Compose, CameraX, WorkManager", "The Android Open Source Project", "Apache License 2.0"),
    Library("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains s.r.o.", "Apache License 2.0"),
    Library("OkHttp, Okio", "Square, Inc.", "Apache License 2.0"),
    Library("Google Tink", "Google LLC", "Apache License 2.0"),
    Library("ZXing (QR-Codes)", "ZXing authors", "Apache License 2.0"),
    Library("JNA", "JNA authors", "Apache License 2.0 oder LGPL 2.1"),
    Library("Marmot Development Kit (MDK)", "Marmot Protocol contributors", "MIT License"),
    Library("OpenMLS", "OpenMLS Authors", "MIT License"),
    Library("rust-nostr", "Yuki Kishimoto und Beitragende", "MIT License"),
    Library("RustCrypto (ChaCha20-Poly1305)", "RustCrypto Developers", "Apache License 2.0 oder MIT License"),
    Library("UniFFI", "Mozilla Foundation", "Mozilla Public License 2.0"),
    Library("SQLCipher", "Zetetic LLC", "BSD-Lizenz"),
    Library("OpenSSL", "The OpenSSL Project Authors", "Apache License 2.0"),
    Library("Feiertagsregeln (abgeglichen mit date-holidays)", "commenthol und Beitragende", "CC BY 3.0"),
)

/** Volltexte der häufigsten Lizenzen. */
private val TEXTS = listOf(
    "SIL Open Font License 1.1" to R.raw.license_ofl,
    "Apache License 2.0" to R.raw.license_apache,
    "MIT License" to R.raw.license_mit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_licenses)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(LIBRARIES, key = { it.name }) { library ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(library.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "© ${library.holder} · ${library.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.licenses_texts)) }
            items(TEXTS, key = { it.first }) { (name, resource) -> LicenseText(name, resource) }
        }
    }
}

/** Aufklappbarer Lizenztext. */
@Composable
private fun LicenseText(name: String, @RawRes resource: Int) {
    var open by rememberSaveable(name) { mutableStateOf(false) }
    val context = LocalContext.current
    val text = remember(resource, open) {
        if (open) context.resources.openRawResource(resource).bufferedReader(Charsets.UTF_8).use { it.readText() } else ""
    }
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(
                painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = stringResource(if (open) R.string.action_collapse else R.string.action_expand),
            )
        }
        AnimatedVisibility(visible = open) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}
