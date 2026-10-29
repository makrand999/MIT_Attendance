package com.mit.attendance.ui.subjects

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mit.attendance.storage.OutputDirectoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Foreground entry point for notification taps and the downloaded APK browser. */
class UpdateInstallActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_APK_PATH = "apk_path"

        @Suppress("DEPRECATION")
        fun validateApk(context: Context, file: File, expectedVersion: Long? = null) {
            val root = OutputDirectoryManager.getUpdatesFolder(context).canonicalFile
            require(file.canonicalPath.startsWith(root.path + File.separator) && file.isFile) {
                "Update file is unavailable"
            }
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                else PackageManager.GET_SIGNATURES
            val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
                ?: error("Downloaded file is not a valid APK")
            val installed = pm.getPackageInfo(context.packageName, flags)
            require(archive.packageName == context.packageName) { "APK belongs to a different app" }
            val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
            val current = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
            require(version > current && (expectedVersion == null || version == expectedVersion)) {
                "APK version does not match this update"
            }
            require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) {
                "This update requires a newer Android version"
            }
            // Require the installed signer(s), allowing a documented signing-key rotation lineage.
            fun signers(info: PackageInfo, history: Boolean): Set<String> =
                if (Build.VERSION.SDK_INT >= 28) {
                    val signing = checkNotNull(info.signingInfo) { "APK has no signing certificate" }
                    (if (history && !signing.hasMultipleSigners()) signing.signingCertificateHistory
                        else signing.apkContentsSigners).map { it.toCharsString() }.toSet()
                } else info.signatures.orEmpty().map { it.toCharsString() }.toSet()
            val currentSigners = signers(installed, false)
            val newSigners = signers(archive, false)
            val compatible = if (currentSigners.size == 1 && newSigners.size == 1)
                signers(archive, true).containsAll(currentSigners) else newSigners == currentSigners
            require(currentSigners.isNotEmpty() && compatible) { "APK signing certificate does not match" }
        }
    }

    private var waitingForResult = false
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingForResult = false
        if (packageManager.canRequestPackageInstalls()) install() else {
            Toast.makeText(this, "Allow app installs to install the downloaded update", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    private val installerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingForResult = false
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingForResult = savedInstanceState?.getBoolean("waitingForResult") ?: false
        if (waitingForResult) return // Activity Result API restores the pending result.
        if (!packageManager.canRequestPackageInstalls()) {
            waitingForResult = true
            permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
        } else install()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("waitingForResult", waitingForResult)
        super.onSaveInstanceState(outState)
    }

    private fun install() {
        lifecycleScope.launch {
            try {
                val file = File(checkNotNull(intent.getStringExtra(EXTRA_APK_PATH)))
                withContext(Dispatchers.IO) { validateApk(this@UpdateInstallActivity, file) }
                val uri = FileProvider.getUriForFile(this@UpdateInstallActivity, "$packageName.provider", file)
                waitingForResult = true
                installerLauncher.launch(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@UpdateInstallActivity, "Cannot install update: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}
