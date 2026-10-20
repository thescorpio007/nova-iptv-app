package com.nova.iptv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.iptv.ui.FocusBox
import com.nova.iptv.ui.NovaTheme
import kotlinx.coroutines.delay

/**
 * ↗ External player → "Play with…": only real video players (no downloaders such as 1DM unless you switched them on),
 * in your order (Settings › Players › External players). The highlighted one opens by itself after 10 s.
 * "Always use this one" puts it first and opens the 1st one directly from then on.
 */
class PlayWithActivity : ComponentActivity() {
    companion object {
        @Volatile var pending: PlayItem? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = pending ?: run { finish(); return }
        val apps = ExternalPlayer.installed(this)
        if (apps.isEmpty()) { finish(); return }
        setContent {
            NovaTheme {
                var focused by remember { mutableIntStateOf(0) }
                var left by remember { mutableIntStateOf(10) }
                var touched by remember { mutableStateOf(false) }
                val first = remember { FocusRequester() }
                fun go(i: Int, always: Boolean) {
                    val a = apps.getOrNull(i) ?: return
                    if (always) {
                        ExternalPlayer.makeFirst(this, a.packageName)
                        (applicationContext as com.nova.iptv.NovaApp).storage.extMode = "first"
                    }
                    pending = null
                    ExternalPlayer.open(this, item, a.packageName)
                    finish()
                }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                LaunchedEffect(touched) {
                    while (left > 0) { delay(1000); left-- }
                    go(focused, false)
                }
                Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(16.dp)
                            .background(Color(0xFF14213A), RoundedCornerShape(18.dp))
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("↗ Play with…", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(item.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(apps) { i, a ->
                                FocusBox(
                                    onClick = { go(i, false) },
                                    onFocused = { if (focused != i) { focused = i; left = 10; touched = !touched } },
                                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                                ) { f ->
                                    Row(
                                        Modifier.fillMaxWidth().background(if (f) Color(0xFF2F4B7C) else Color(0xFF1C2D4D), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                            Text(a.label.take(2).uppercase(), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                        Text(a.label, fontSize = 17.sp, modifier = Modifier.weight(1f))
                                        if (i == 0) Text("1st in your order", fontSize = 11.sp, color = Color(0xFF7FE8B0))
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.nova.iptv.ui.SmallButton("✓ Always use this one", { go(focused, true) })
                            com.nova.iptv.ui.SmallButton("Cancel", { pending = null; finish() })
                        }
                        Text(
                            "Opens ${apps.getOrNull(focused)?.label ?: ""} in $left s · order and hidden apps: Settings › Players › External players",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
