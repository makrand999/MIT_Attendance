package com.mit.attendance.ui.subjects

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.databinding.ActivityCustomizeBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CustomizeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomizeBinding
    private lateinit var prefs: UserPreferences

    private var targetBg: String? = null // "detail" or "nav"

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            
            // Persist permission for the URI
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            lifecycleScope.launch {
                when (targetBg) {
                    "detail" -> {
                        prefs.saveBgDetailUri(uri.toString())
                    }
                    "nav" -> {
                        prefs.saveBgNavUri(uri.toString())
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomizeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = UserPreferences(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = ""

        binding.btnBack.setOnClickListener { finish() }

        setupUI()
        observeSettings()
    }

    private fun setupUI() {
        binding.btnUploadDetail.setOnClickListener {
            targetBg = "detail"
            openImagePicker()
        }

        binding.btnUploadNav.setOnClickListener {
            targetBg = "nav"
            openImagePicker()
        }

        binding.btnResetDetail.setOnClickListener {
            lifecycleScope.launch {
                prefs.saveBgDetailUri(null)
            }
        }

        binding.btnResetNav.setOnClickListener {
            lifecycleScope.launch {
                prefs.saveBgNavUri(null)
            }
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pickImageLauncher.launch(intent)
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            prefs.bgDetailUri.collectLatest { uriString ->
                if (uriString != null) {
                    binding.ivPreviewDetail.setImageURI(uriString.toUri())
                    binding.ivBackground.setImageURI(uriString.toUri())
                } else {
                    binding.ivPreviewDetail.setImageResource(R.drawable.bg_detail)
                    binding.ivBackground.setImageResource(R.drawable.bg_detail)
                }
            }
        }

        lifecycleScope.launch {
            prefs.bgNavUri.collectLatest { uriString ->
                if (uriString != null) {
                    binding.ivPreviewNav.setImageURI(uriString.toUri())
                } else {
                    binding.ivPreviewNav.setImageResource(R.drawable.bg_nav)
                }
            }
        }
    }
}
