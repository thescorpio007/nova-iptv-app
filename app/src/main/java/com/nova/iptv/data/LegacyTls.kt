package com.nova.iptv.data

import android.content.Context
import android.os.Build
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android 5–7.0 doesn't know some of today's certificate authorities (Let's Encrypt's ISRG Root X1 / X2 …), so
 * HTTPS servers using them fail there. On those versions Nova also trusts an up-to-date list of authorities it
 * carries itself (res/raw/extra_roots.pem, the Mozilla list). Newer Android versions are left exactly as they are.
 */
object LegacyTls {
    fun apply(context: Context, builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (Build.VERSION.SDK_INT >= 25) return builder
        return runCatching {
            val system = trustManager(null)
            val extra = trustManager(loadBundled(context))
            val combined = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (e: CertificateException) {
                        extra.checkServerTrusted(chain, authType)
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + extra.acceptedIssuers
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(combined), null) }
            builder.sslSocketFactory(ssl.socketFactory, combined)
        }.getOrDefault(builder)
    }

    private fun loadBundled(context: Context): KeyStore {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val cf = CertificateFactory.getInstance("X.509")
        context.resources.openRawResource(com.nova.iptv.R.raw.extra_roots).use { input ->
            cf.generateCertificates(input.buffered()).forEachIndexed { i, c -> ks.setCertificateEntry("nova$i", c) }
        }
        return ks
    }

    private fun trustManager(ks: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
