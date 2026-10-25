package com.nova.iptv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** "Open Nova when the box starts": Android announces the end of start-up, Nova opens itself (if allowed). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON" && a != "com.htc.intent.action.QUICKBOOT_POWERON") return
        val storage = (context.applicationContext as com.nova.iptv.NovaApp).storage
        if (!storage.startOnBoot) return
        // Android 10+ lets an app open itself in the background only with "Display over other apps".
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            android.util.Log.w("NovaTest", "BOOT start skipped: 'Display over other apps' not allowed")
            return
        }
        android.util.Log.i("NovaTest", "BOOT opening Nova")
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_FROM_BOOT, true)
        )
    }
}
