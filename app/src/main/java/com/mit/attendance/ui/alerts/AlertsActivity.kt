package com.mit.attendance.ui.alerts

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.snackbar.Snackbar
import com.mit.attendance.R
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.data.repository.AlertRepository
import com.mit.attendance.databinding.ActivityAlertsBinding
import com.mit.attendance.databinding.DialogAddAlertBinding
import com.mit.attendance.databinding.ItemAlertBinding
import com.mit.attendance.model.AlertEntity
import com.mit.attendance.ui.theme.MedKitMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AlertsViewModel(
    private val repository: AlertRepository,
    private val attendanceRepo: AttendanceRepository
) : ViewModel() {
    val alerts = repository.getAlertsFlow()
    val trashAlerts = repository.getTrashAlertsFlow()
    val subjects = attendanceRepo.getSubjectsFlow()

    fun fetchAlerts() {
        viewModelScope.launch {
            repository.moveExpiredAlertsToTrash()
            repository.fetchAlerts()
        }
    }

    fun postAlert(type: String, subject: String, topic: String, description: String, endDate: String) {
        viewModelScope.launch {
            repository.postAlert(type, subject, topic, description, endDate)
        }
    }

    fun deleteAlert(alert: AlertEntity) {
        viewModelScope.launch {
            repository.deleteAlert(alert)
        }
    }

    fun trashAlert(alert: AlertEntity) {
        viewModelScope.launch {
            repository.moveAlertToTrash(alert)
        }
    }

    fun restoreAlert(alert: AlertEntity) {
        viewModelScope.launch {
            repository.restoreAlert(alert)
        }
    }
}

class AlertsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILTER_SUBJECT = "extra_filter_subject"
    }

    private lateinit var binding: ActivityAlertsBinding
    private var filterSubject: String? = null
    private var showTrash = false
    private var alertsJob: Job? = null

    private val viewModel: AlertsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val alertRepo = AlertRepository(applicationContext)
                val attendanceRepo = AttendanceRepository(applicationContext)
                return AlertsViewModel(alertRepo, attendanceRepo) as T
            }
        }
    }

    private val adapter = AlertsAdapter(
        onDelete = { alert ->
            if (showTrash) {
                viewModel.deleteAlert(alert)
            } else {
                viewModel.trashAlert(alert)
            }
        },
        onRestore = { viewModel.restoreAlert(it) },
        onLongClick = { alert -> showReminderDialog(alert) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filterSubject = intent.getStringExtra(EXTRA_FILTER_SUBJECT)
        if (filterSubject != null) {
            binding.toolbar.title = "Alerts: $filterSubject"
        }

        setupToolbar()
        setupRecyclerView()
        observeViewModel()
        binding.swipeRefresh.setColorSchemeResources(
            R.color.medkit_peach_deep,
            R.color.medkit_mint_deep,
            R.color.medkit_sky_deep
        )
        renderAlertSummary(emptyList())
        renderToolbarTitle()
        MedKitMotion.playEntrance(binding.toolbar, binding.cardAlertsSummary, binding.fabAddAlert)
        MedKitMotion.applyFloat(binding.fabAddAlert, distanceDp = 4f, durationMs = 2400L)
        MedKitMotion.applyRecyclerStagger(binding.rvAlerts)

        binding.fabAddAlert.setOnClickListener {
            showAddAlertDialog()
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.fetchAlerts()
        }
        viewModel.fetchAlerts()
    }

    private fun setupToolbar() {
        binding.toolbar.navigationIcon?.setTint(getColor(R.color.medkit_line))
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menu?.add(0, 1, 0, if (showTrash) "Show Active" else "Trash")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == 1) {
            showTrash = !showTrash
            invalidateOptionsMenu()
            renderToolbarTitle()
            observeCurrentList()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        binding.rvAlerts.layoutManager = LinearLayoutManager(this)
        binding.rvAlerts.adapter = adapter
    }

    private fun observeViewModel() {
        observeCurrentList()
    }

    private fun observeCurrentList() {
        alertsJob?.cancel()
        alertsJob = lifecycleScope.launch {
            val source = if (showTrash) viewModel.trashAlerts else viewModel.alerts
            source.collectLatest { alerts ->
                val filteredList = if (filterSubject != null) {
                    alerts.filter { it.subject.equals(filterSubject, ignoreCase = true) }
                } else {
                    alerts
                }
                adapter.setTrashMode(showTrash)
                adapter.submitList(filteredList.sortedByDescending { it.id })
                MedKitMotion.applyRecyclerStagger(binding.rvAlerts)
                renderAlertSummary(filteredList)
                binding.swipeRefresh.isRefreshing = false
                binding.tvEmpty.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun renderToolbarTitle() {
        binding.toolbar.title = when {
            filterSubject != null && showTrash -> "Trash: $filterSubject"
            filterSubject != null -> "Alerts: $filterSubject"
            showTrash -> "Alert Trash"
            else -> "Class Alerts"
        }
    }

    private fun renderAlertSummary(alerts: List<AlertEntity>) {
        val scopeLabel = filterSubject ?: "All classes"
        binding.tvAlertFilterValue.text = scopeLabel
        binding.tvAlertCountValue.text = alerts.size.toString()
        binding.tvSummaryTitle.text = when {
            showTrash && filterSubject == null -> "Alert trash"
            showTrash -> "${filterSubject} trash"
            filterSubject == null -> "Class alerts"
            else -> "${filterSubject} alerts"
        }
        binding.tvSummarySubtitle.text =
            if (alerts.isEmpty()) {
                if (showTrash) {
                    "Trash is empty right now."
                } else {
                    "No active alerts yet. Use the add button to post an update."
                }
            } else {
                if (showTrash) {
                    "Restore alerts from trash or delete them permanently."
                } else {
                    "Long-press any alert to schedule a reminder."
                }
            }
        binding.tvAlertHint.text =
            if (alerts.isEmpty()) {
                if (showTrash) "Expired alerts and trashed alerts will appear here." else "Nothing is listed in this scope right now."
            } else {
                if (showTrash) {
                    "Expired alerts move here automatically after their end date."
                } else {
                    "Tap a card to expand details, then long-press if you want a reminder."
                }
            }
        binding.fabAddAlert.visibility = if (showTrash) View.GONE else View.VISIBLE
    }

    private fun showReminderDialog(alert: AlertEntity) {
        val calendar = Calendar.getInstance()

        // Step 1: Pick Date first
        DatePickerDialog(
            this,
            { _, year, month, day ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)

                // Step 2: Then pick Time
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)

                        setReminder(alert, calendar.timeInMillis)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    false
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun setReminder(alert: AlertEntity, timeInMillis: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlertReminderReceiver::class.java).apply {
            putExtra("subject", alert.subject)
            putExtra("topic", alert.topic)
            putExtra("description", alert.description)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            alert.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeInMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, timeInMillis, pendingIntent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeInMillis, pendingIntent)
        }

        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        Toast.makeText(this, "Reminder set for ${sdf.format(Date(timeInMillis))}", Toast.LENGTH_SHORT).show()
    }

    private fun showAddAlertDialog() {
        val dialogBinding = DialogAddAlertBinding.inflate(layoutInflater)

        lifecycleScope.launch {
            val subjects = viewModel.subjects.first()
            val subjectList = subjects.map { it.name }.toMutableList()
            if (!subjectList.contains("Other")) {
                subjectList.add("Other")
            }

            val spinnerAdapter = ArrayAdapter(this@AlertsActivity, R.layout.item_spinner_subject, subjectList)
            val autoCompleteTextView = dialogBinding.spinnerSubject as AutoCompleteTextView
            autoCompleteTextView.setAdapter(spinnerAdapter)

            // Pre-select subject if we are in filtered mode
            filterSubject?.let { fs ->
                if (subjectList.any { it.equals(fs, ignoreCase = true) }) {
                    val actualName = subjectList.first { it.equals(fs, ignoreCase = true) }
                    autoCompleteTextView.setText(actualName, false)
                }
            }

            autoCompleteTextView.setDropDownBackgroundResource(R.drawable.bg_medkit_card_soft)

            // Apply blur for Android 12+ (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                autoCompleteTextView.post {
                    try {
                        val popupField = AutoCompleteTextView::class.java.getDeclaredField("mPopup")
                        popupField.isAccessible = true
                        val popup = popupField.get(autoCompleteTextView) as androidx.appcompat.widget.ListPopupWindow
                        val popupWindow = popup.listView?.parent as? View
                        popupWindow?.setRenderEffect(
                            RenderEffect.createBlurEffect(15f, 15f, Shader.TileMode.CLAMP)
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            autoCompleteTextView.setOnItemClickListener { _, _, position, _ ->
                if (subjectList[position] == "Other") {
                    dialogBinding.tilOtherSubject.visibility = View.VISIBLE
                } else {
                    dialogBinding.tilOtherSubject.visibility = View.GONE
                }
            }

            val calendar = Calendar.getInstance()
            val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, day ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                dialogBinding.etEndDate.setText(format.format(calendar.time))
            }

            dialogBinding.etEndDate.setOnClickListener {
                DatePickerDialog(this@AlertsActivity, dateSetListener, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
            }

            val dialog = AlertDialog.Builder(this@AlertsActivity, R.style.TransparentDialog)
                .setView(dialogBinding.root)
                .setPositiveButton("Post") { _, _ ->
                    val subject = if (dialogBinding.spinnerSubject.text.toString() == "Other") {
                        dialogBinding.etOtherSubject.text.toString()
                    } else {
                        dialogBinding.spinnerSubject.text.toString()
                    }
                    val topic = dialogBinding.etTopic.text.toString()
                    val description = dialogBinding.etDescription.text.toString()
                    val deadline = dialogBinding.etEndDate.text.toString()

                    if (subject.isNotEmpty() && topic.isNotEmpty() && deadline.isNotEmpty()) {
                        viewModel.postAlert("General", subject, topic, description, deadline)
                    } else {
                        Snackbar.make(binding.root, "Subject, topic, and deadline are required", Snackbar.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .create()
            dialog.show()
        }
    }
}

class AlertsAdapter(
    private val onDelete: (AlertEntity) -> Unit,
    private val onRestore: (AlertEntity) -> Unit,
    private val onLongClick: (AlertEntity) -> Unit
) : ListAdapter<AlertEntity, AlertsAdapter.ViewHolder>(DiffCallback()) {

    private var trashMode: Boolean = false

    fun setTrashMode(enabled: Boolean) {
        trashMode = enabled
    }

    class ViewHolder(
        private val binding: ItemAlertBinding,
        private val onDelete: (AlertEntity) -> Unit,
        private val onRestore: (AlertEntity) -> Unit,
        private val onLongClick: (AlertEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(alert: AlertEntity, trashMode: Boolean, position: Int) {
            binding.root.rotation = stuckCardRotation(position)
            binding.root.translationX = stuckCardOffsetX(position)
            binding.root.translationY = stuckCardOffsetY(position)
            binding.layoutDetails.visibility = View.GONE
            binding.tvSubject.text = alert.subject
            binding.tvTopic.text = alert.topic
            binding.tvDescription.text = alert.description
            binding.tvDate.text = buildDueText(alert.end_date)
            binding.tvDeadline.text = "Deadline: ${alert.end_date.ifBlank { "Not set" }}"
            binding.tvType.text = alert.type.ifBlank { "General" }
            binding.tvPostedBy.text = buildPostedByText(alert)
            binding.tvDescription.visibility = if (alert.description.isBlank()) View.GONE else View.VISIBLE
            binding.tvType.setBackgroundResource(typeBackground(alert.type))
            binding.btnRestore.visibility = if (trashMode) View.VISIBLE else View.GONE
            binding.btnDelete.contentDescription = if (trashMode) "Delete Permanently" else "Move To Trash"

            binding.btnDelete.setOnClickListener {
                onDelete(alert)
            }

            binding.btnRestore.setOnClickListener {
                onRestore(alert)
            }

            binding.root.setOnClickListener {
                val expanded = binding.layoutDetails.visibility == View.VISIBLE
                TransitionManager.beginDelayedTransition(binding.root, AutoTransition().apply { duration = 180 })
                binding.layoutDetails.visibility = if (expanded) View.GONE else View.VISIBLE
                binding.tvTapHint.text =
                    if (expanded) {
                        "Tap to expand details • Long press to set a reminder"
                    } else {
                        "Tap to collapse • Long press to set a reminder"
                    }
            }

            binding.root.setOnLongClickListener {
                onLongClick(alert)
                true
            }
        }

        private fun buildPostedByText(alert: AlertEntity): String {
            val source = alert.posted_by.ifBlank { "Unknown" }
            val postedAt = alert.posted_at.ifBlank { "recently" }
            return "Posted by $source • $postedAt"
        }

        private fun buildDueText(endDate: String): String {
            return if (endDate.isBlank()) "No deadline" else "Due $endDate"
        }

        private fun typeBackground(type: String): Int {
            return when (type.trim().lowercase(Locale.ROOT)) {
                "homework" -> R.drawable.bg_alert_type_homework
                "project" -> R.drawable.bg_alert_type_project
                "ta" -> R.drawable.bg_alert_type_ta
                "test" -> R.drawable.bg_alert_type_test
                else -> R.drawable.bg_alert_type
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onDelete, onRestore, onLongClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), trashMode, position)
    }

    class DiffCallback : DiffUtil.ItemCallback<AlertEntity>() {
        override fun areItemsTheSame(oldItem: AlertEntity, newItem: AlertEntity): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: AlertEntity, newItem: AlertEntity): Boolean {
            return oldItem == newItem
        }
    }
}

private fun stuckCardRotation(position: Int): Float = when (position % 6) {
    0 -> -1.2f
    1 -> 0.9f
    2 -> 0.55f
    3 -> -0.95f
    4 -> -0.45f
    else -> 1.15f
}

private fun stuckCardOffsetX(position: Int): Float = when (position % 4) {
    0 -> -3f
    1 -> 4f
    2 -> 2f
    else -> -4f
}

private fun stuckCardOffsetY(position: Int): Float = when (position % 4) {
    0 -> 0f
    1 -> 8f
    2 -> -5f
    else -> 6f
}
