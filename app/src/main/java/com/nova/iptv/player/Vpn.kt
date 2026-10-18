package com.nova.iptv.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings

/** Works with the VPN apps installed on the device (OpenVPN, WireGuard, …). */
object Vpn {

    /** Well-known VPN apps that can import a profile file. */
    val knownApps = listOf(
        "de.blinkt.openvpn" to "OpenVPN for Android",
        "net.openvpn.openvpn" to "OpenVPN Connect",
        "com.wireguard.android" to "WireGuard",
    )

    fun isActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (android.os.Build.VERSION.SDK_INT < 23) {
            @Suppress("DEPRECATION")
            return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        }
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun installedApps(context: Context): List<Pair<String, String>> =
        knownApps.filter { (pkg, _) ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }

    /** Opens the first installed VPN app, or Android's VPN settings. */
    fun openApp(context: Context) {
        val pkg = installedApps(context).firstOrNull()?.first
        val launch = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
        try {
            if (launch != null) {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Hands a .ovpn / .conf profile to the VPN app so it can import it. Returns false if no app can. */
    fun importProfile(context: Context, file: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(file, "application/x-openvpn-profile")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        for ((pkg, _) in installedApps(context)) {
            try {
                context.startActivity(Intent(intent).setPackage(pkg))
                return true
            } catch (e: ActivityNotFoundException) {
                // try the next app
            }
        }
        return try {
            context.startActivity(Intent.createChooser(intent.setType("*/*"), "Import VPN profile with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
