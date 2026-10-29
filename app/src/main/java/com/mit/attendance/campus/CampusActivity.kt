package com.mit.attendance.campus

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class CampusScreen(val session: CampusSession? = null, val busy: Boolean = false,
    val message: String = "", val validated: Boolean = false)

internal class CampusViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CampusRepository(application)
    private val mutableState = MutableStateFlow(CampusScreen())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    init { refresh() }
    fun refresh() = runRequest { repository.refresh() }
    private fun runRequest(request: suspend () -> CampusSession) {
        if (mutableState.value.busy) return
        job = viewModelScope.launch {
            mutableState.value = CampusScreen(repository.cached(), busy = true)
            try { val session=request(); mutableState.value = CampusScreen(session, validated = true) }
            catch (error: Exception) {
                mutableState.value = CampusScreen(repository.cached(), message = campusError(error))
            }
        }
    }
    fun signOut() {
        job?.cancel()
        repository.store.signOut()
        mutableState.value = CampusScreen(message = "Signed out of Akatsuki.")
    }
}

class CampusActivity : AppCompatActivity() {
    private val model: CampusViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.apply { title = "Akatsuki"; setDisplayHomeAsUpEnabled(true) }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
        fun label(value: String) = TextView(this).apply { text = value; setPadding(0, padding / 2, 0, padding / 2); column.addView(this) }
        label("Akatsuki uses the ERP login already saved in this app. Find opportunities, apply, manage your listings and message other students.")
        val profile = label("")
        val status = label("")
        val refresh = Button(this).apply { text = "Connect / refresh Akatsuki"; column.addView(this) }
        val signOut = Button(this).apply { text = "Sign out of Akatsuki"; column.addView(this) }
        Button(this).apply { text = "Opportunities, applications and chat"; column.addView(this); setOnClickListener { startActivity(Intent(this@CampusActivity, CampusOpportunitiesActivity::class.java)) } }
        label("The browser workspace is also available with a separate browser sign-in.")
        Button(this).apply {
            text = "Open Akatsuki in browser"; column.addView(this)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CampusHttp.WEB_WORKSPACE))) }
        }
        setContentView(ScrollView(this).apply { addView(column) })
        refresh.setOnClickListener { model.refresh() }
        signOut.setOnClickListener { model.signOut() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    val cached = state.session
                    profile.text = cached?.profile?.let {
                        "${it.name}\n${it.roll_number} · ${it.department}\n" +
                            if (state.validated) "Verified Akatsuki profile" else "Cached profile"
                    } ?: "Connect using your saved ERP login to see your Akatsuki profile."
                    status.text = if (state.busy) "Connecting to Akatsuki…" else state.message
                    refresh.isEnabled = !state.busy
                    signOut.isEnabled = cached != null || state.busy
                }
            }
        }
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
