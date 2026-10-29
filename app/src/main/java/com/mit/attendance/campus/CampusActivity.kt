package com.mit.attendance.campus

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
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
        val column = CampusUi.column(this)
        val profile = CampusUi.card(this, column)
        val name = CampusUi.text(this, "Akatsuki", heading = true).also { profile.addView(it) }
        val detail = CampusUi.text(this, "").also { profile.addView(it) }
        val status = CampusUi.text(this, "").also { column.addView(it) }
        val connect=CampusUi.button(this,column,"Connect") { model.refresh() }
        CampusUi.button(this, column, "Opportunities") { startActivity(Intent(this, CampusOpportunitiesActivity::class.java)) }
        CampusUi.button(this, column, "Class alerts") { startActivity(Intent(this, CampusAlertsActivity::class.java)) }
        CampusUi.screen(this, "Akatsuki", column, listOf(
            "Refresh" to { model.refresh(); Unit },
            "Open in browser" to { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CampusHttp.WEB_WORKSPACE))) },
            "Sign out" to { model.signOut() }
        ))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    val cached = state.session
                    name.text = cached?.profile?.name ?: "Connect to Akatsuki"
                    detail.text = cached?.profile?.let { listOf(it.roll_number, it.department).filter { value -> value.isNotBlank() }.joinToString(" · ") }
                        ?: "ERP account"
                    connect.visibility=if(cached==null&&!state.busy)android.view.View.VISIBLE else android.view.View.GONE
                    CampusUi.status(status, if (state.busy) "Connecting…" else state.message)

                }
            }
        }
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
