package io.nekohasekai.sfa

import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager as AndroidPackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.content.getSystemService
import io.clashbox.BuildConfig
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

// Integration seam for the sing-box service layer: replaces the SFA app class
// with the same static surface the service layer uses, backed by this app.
open class Application : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        application = this
    }

    private val setupAccess = Mutex()
    private var setupCompleted = false

    suspend fun setupLibbox() = setupAccess.withLock {
        if (setupCompleted) return@withLock
        val workingDir = File(filesDir, "working").apply { mkdirs() }
        Libbox.setup(
            SetupOptions().also {
                it.basePath = filesDir.path
                it.workingPath = workingDir.path
                it.tempPath = cacheDir.path
                it.logMaxLines = 3000
                it.debug = BuildConfig.DEBUG
                it.crashReportSource = "Application"
                it.appVersion = BuildConfig.VERSION_CODE.toString()
                it.appMarketingVersion = BuildConfig.VERSION_NAME
            },
        )
        setupCompleted = true
    }

    companion object {
        lateinit var application: Application

        val notification: NotificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val notificationManager: NotificationManager get() = notification
        val connectivity: ConnectivityManager by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager: AndroidPackageManager by lazy { application.packageManager }
        val powerManager: PowerManager by lazy { application.getSystemService<PowerManager>()!! }
        val wifiManager: WifiManager by lazy { application.getSystemService<WifiManager>()!! }
        val clipboard: ClipboardManager by lazy { application.getSystemService<ClipboardManager>()!! }

        fun getSystemService(context: Context) = Unit
    }
}
