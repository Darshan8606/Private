package com.reelcounter

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }
}

private fun isServiceEnabled(ctx: Context): Boolean {
    val cn = ComponentName(ctx, ReelAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any {
        it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
    }
}

private fun openAccessibilitySettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(CounterStore.isEnabled(ctx)) }
    var serviceOn by remember { mutableStateOf(isServiceEnabled(ctx)) }
    var showDialog by remember { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                serviceOn = isServiceEnabled(ctx)
                enabled = CounterStore.isEnabled(ctx)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Reel Counter", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(40.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Enable Counter", style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = { on ->
                enabled = on
                CounterStore.setEnabled(ctx, on)
                if (on && !serviceOn) showDialog = true
            })
        }
        if (enabled && !serviceOn) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Setup needed: tap here and turn on \"Reel Counter\" in Accessibility settings.",
                modifier = Modifier.clickable { openAccessibilitySettings(ctx) },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("One-time setup") },
            text = {
                Text(
                    "Android needs your permission to let Reel Counter see when you swipe " +
                        "between Reels.\n\n" +
                        "1. Tap \"Open Settings\".\n" +
                        "2. Open \"Installed apps\" / \"Downloaded apps\" and pick Reel Counter.\n" +
                        "3. Turn on \"Use Reel Counter\" and confirm.\n\n" +
                        "If Android says \"Restricted setting\", open Settings > Apps > Reel Counter, " +
                        "tap the \u22EE menu (top right) and choose \"Allow restricted settings\", then try again."
                )
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false; openAccessibilitySettings(ctx) }) {
                    Text("Open Settings")
                }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Not now") } }
        )
    }
}
