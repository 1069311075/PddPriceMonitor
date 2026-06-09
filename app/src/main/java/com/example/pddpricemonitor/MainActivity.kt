package com.example.pddpricemonitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pddpricemonitor.capture.MonitorDebugState
import com.example.pddpricemonitor.capture.PddForegroundState
import com.example.pddpricemonitor.capture.ScreenCaptureService
import com.example.pddpricemonitor.data.ProductPrice
import com.example.pddpricemonitor.data.ProductRepository
import com.example.pddpricemonitor.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = ProductRepository((application as PddMonitorApp).database.productPriceDao())

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PriceMonitorApp(
                        viewModel = viewModel(factory = MainViewModel.Factory(repository))
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceMonitorApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val projectionManager = remember {
        context.getSystemService(MediaProjectionManager::class.java)
    }
    val products by viewModel.products.collectAsState()
    val debugInfo by MonitorDebugState.info.collectAsState()
    var captureStarted by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredProducts = remember(products, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            products
        } else {
            val normalizedQuery = normalizeSearchText(query)
            products.filter { item ->
                item.title.contains(query, ignoreCase = true) ||
                    item.normalizedTitle.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val serviceIntent = ScreenCaptureService.startIntent(context, result.resultCode, data)
            ContextCompat.startForegroundService(context, serviceIntent)
            captureStarted = true
        }
    }

    LaunchedEffect(Unit) {
        if (Settings.canDrawOverlays(context)) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Header(
            captureStarted = captureStarted,
            onStartCapture = {
                if (Settings.canDrawOverlays(context)) {
                    projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                } else {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            },
            onClearHistory = { viewModel.clearHistory() },
            onOpenPdd = {
                openPdd(context)
            },
            debugMessage = debugInfo.message,
            debugTextLength = debugInfo.lastOcrTextLength,
            debugParsedProducts = debugInfo.lastParsedProducts,
            debugSavedProducts = debugInfo.lastSavedProducts,
            debugUpdatedAt = debugInfo.updatedAt
        )

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Price history (${filteredProducts.size}/${products.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search product") },
            placeholder = { Text("Type brand, model, or keyword") },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    TextButton(onClick = { searchQuery = "" }) {
                        Text("Clear")
                    }
                }
            }
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (filteredProducts.isEmpty()) {
                item {
                    Text(
                        text = if (searchQuery.isBlank()) "No saved products yet" else "No matching products",
                        modifier = Modifier.padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(filteredProducts, key = { it.id }) { item ->
                ProductRow(item)
                Divider()
            }
        }
    }
}

@Composable
private fun Header(
    captureStarted: Boolean,
    onStartCapture: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenPdd: () -> Unit,
    debugMessage: String,
    debugTextLength: Int,
    debugParsedProducts: Int,
    debugSavedProducts: Int,
    debugUpdatedAt: Long
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "PDD Price Monitor",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = when {
                !captureStarted -> "Waiting for screen capture permission"
                else -> "Floating ball is ready"
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartCapture) {
                Text("Start floating ball")
            }
            TextButton(onClick = onOpenPdd) {
                Text("Open PDD")
            }
            TextButton(onClick = onClearHistory) {
                Text("Clear")
            }
        }
        Text(
            text = "Last: $debugMessage | OCR chars: $debugTextLength | parsed: $debugParsedProducts | saved: $debugSavedProducts",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (debugUpdatedAt > 0L) {
            Text(
                text = "Debug updated: ${formatTime(debugUpdatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProductRow(item: ProductPrice) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Updated: ${formatTime(item.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = formatPrice(item.priceCents),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
    }
}

private fun formatPrice(cents: Long): String =
    "CNY ${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

private fun formatTime(timeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timeMillis))

private fun normalizeSearchText(text: String): String =
    text.lowercase()
        .replace(Regex("[^\\p{IsHan}a-z0-9]+"), "")
        .trim()

private fun openPdd(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(PddForegroundState.PDD_PACKAGE_NAME)
    if (launchIntent != null) {
        context.startActivity(launchIntent)
    } else {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${PddForegroundState.PDD_PACKAGE_NAME}"))
        )
    }
}
