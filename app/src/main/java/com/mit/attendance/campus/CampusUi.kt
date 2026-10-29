package com.mit.attendance.campus

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.ViewGroup
import android.view.Gravity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import com.google.android.material.button.MaterialButton
import com.mit.attendance.R

/** Shared presentation for the native Akatsuki screens. */
internal object CampusUi {
    fun dp(activity: AppCompatActivity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun AppCompatActivity.px(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun column(activity: AppCompatActivity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.px(16), activity.px(8), activity.px(16), activity.px(24))
    }
    fun screen(activity: AppCompatActivity, title: String, content: LinearLayout,
               actions: List<Pair<String, () -> Unit>> = emptyList()): Toolbar {
        val toolbar = Toolbar(activity).apply {
            setBackgroundResource(R.drawable.bg_medkit_card)
            setTitleTextAppearance(context, R.style.TextAppearance_MITAttendance_ToolbarTitle)
            setPadding(activity.px(12), 0, activity.px(8), activity.px(4))
        }
        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_medkit_screen)
            addView(toolbar, LinearLayout.LayoutParams(-1, activity.px(72)).apply {
                setMargins(activity.px(16), activity.px(8), activity.px(16), activity.px(8))
            })
            addView(ScrollView(activity).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        activity.setContentView(shell)
        activity.setSupportActionBar(toolbar)
        activity.supportActionBar?.apply { this.title = title; setDisplayHomeAsUpEnabled(true) }
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.navigationIcon?.mutate()?.setTint(ContextCompat.getColor(activity, R.color.medkit_ink))
        toolbar.overflowIcon?.mutate()?.setTint(ContextCompat.getColor(activity, R.color.medkit_ink))
        toolbar.setNavigationOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
        actions.forEachIndexed { index, action ->
            toolbar.menu.add(0, index, index, action.first).setOnMenuItemClickListener { action.second(); true }
        }
        return toolbar
    }
    fun text(activity: AppCompatActivity, value: String, heading: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = if (heading) 20f else 14f
        setTextColor(ContextCompat.getColor(activity, if (heading) R.color.medkit_ink else R.color.medkit_ink_2))
        typeface = ResourcesCompat.getFont(activity, R.font.nunito)?.let { Typeface.create(it, if (heading) Typeface.BOLD else Typeface.NORMAL) }
        setPadding(0, activity.px(4), 0, activity.px(8))
    }
    fun status(view: TextView, value: String) {
        view.text = value
        view.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
        view.maxLines = 3; view.ellipsize = TextUtils.TruncateAt.END
        view.setOnClickListener {
            if (value.isNotBlank()) dialog(view.context as AppCompatActivity).setTitle("Details").setMessage(value).setPositiveButton("Done", null).show()
        }
        view.isClickable = value.length > 120
        view.isFocusable = view.isClickable
    }
    private class CardActions {
        var primaryAdded = false
        var more: View? = null
        val secondary = mutableListOf<Pair<String, () -> Unit>>()
    }
    fun action(activity: AppCompatActivity, parent: LinearLayout, label: String, action: () -> Unit) {
        val group = parent.tag as? CardActions
        if (group == null || !group.primaryAdded) {
            group?.primaryAdded = true
            button(activity, parent, label, action)
        } else {
            group.secondary.add(label to action)
            if (group.more == null) group.more = button(activity, parent, "More") {
                PopupMenu(activity, group.more).apply {
                    group.secondary.forEachIndexed { index, item -> menu.add(0, index, index, item.first).setOnMenuItemClickListener { item.second(); true } }
                    show()
                }
            }
        }
    }
    fun card(activity: AppCompatActivity, parent: LinearLayout, value: String? = null): LinearLayout = column(activity).apply {
        tag = CardActions()
        setBackgroundResource(R.drawable.bg_medkit_card)
        setPadding(activity.px(20), activity.px(16), activity.px(20), activity.px(16))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.px(12) })
        val lines = value?.lines()?.filter { it.isNotBlank() }.orEmpty()
        lines.firstOrNull()?.let { addView(text(activity, it, true)) }
        if (lines.size > 1) paragraph(activity, this, lines.drop(1).joinToString("\n"))
    }
    fun button(activity: AppCompatActivity, parent: LinearLayout, label: String, action: () -> Unit): MaterialButton = MaterialButton(activity).apply {
        text = label; isAllCaps = false; textSize = 14f
        typeface = ResourcesCompat.getFont(activity, R.font.nunito)
        setTextColor(ContextCompat.getColor(activity, R.color.medkit_ink))
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.medkit_butter))
        strokeColor = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.medkit_line))
        strokeWidth = activity.px(2); cornerRadius = activity.px(24)
        minHeight = activity.px(48)
        parent.addView(this)
        setOnClickListener { action() }
    }
    fun paragraph(activity: AppCompatActivity, parent: LinearLayout, value: String) {
        if (value.isBlank()) return
        val body = text(activity, value).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END }
        parent.addView(body)
        val expand = text(activity, "Read more").apply {
            visibility = View.GONE
            minHeight = activity.px(48)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(activity, R.color.medkit_ink))
            isClickable = true; isFocusable = true
            setOnClickListener {
                val expanded = body.maxLines == Int.MAX_VALUE
                body.maxLines = if (expanded) 4 else Int.MAX_VALUE
                text = if (expanded) "Read more" else "Show less"
            }
        }
        parent.addView(expand)
        body.doOnLayout { if (body.layout?.let { it.lineCount > 4 || (it.lineCount > 0 && it.getEllipsisCount(it.lineCount - 1) > 0) } == true) expand.visibility = View.VISIBLE }
    }
    fun dialog(activity: AppCompatActivity) = MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_MITAttendance_CampusDialog)
        .setBackground(ContextCompat.getDrawable(activity, R.drawable.bg_medkit_card))

    fun scroll(activity: AppCompatActivity, content: View) = ScrollView(activity).apply { addView(content) }

    fun input(activity: AppCompatActivity, parent: LinearLayout, label: String, value: String = "", multiline: Boolean = false): android.widget.EditText {
        val wrapper = TextInputLayout(activity).apply {
            hint = label
            typeface = ResourcesCompat.getFont(activity, R.font.nunito)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = ContextCompat.getColor(activity, R.color.medkit_cream_2)
            boxStrokeColor = ContextCompat.getColor(activity, R.color.medkit_ink)
            boxStrokeWidth = activity.px(2)
            setBoxCornerRadii(activity.px(18).toFloat(), activity.px(18).toFloat(), activity.px(18).toFloat(), activity.px(18).toFloat())
            defaultHintTextColor = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.medkit_ink_2))
        }
        val field = TextInputEditText(wrapper.context).apply {
            textSize = 14f; typeface = ResourcesCompat.getFont(activity, R.font.nunito)
            setTextColor(ContextCompat.getColor(activity, R.color.medkit_ink))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            minLines = if (multiline) 3 else 1; maxLines = if (multiline) 6 else 1
            gravity = Gravity.TOP or Gravity.START
            minHeight = activity.px(56)
            setPadding(activity.px(16), activity.px(16), activity.px(16), activity.px(16))
            setText(value)
        }
        wrapper.addView(field, LinearLayout.LayoutParams(-1, -2))
        parent.addView(wrapper, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.px(12) })
        return field
    }
    fun selector(activity: AppCompatActivity, parent: LinearLayout, label: String? = null): Spinner {
        label?.let { parent.addView(text(activity, it)) }
        return androidx.appcompat.widget.AppCompatSpinner(activity).apply {
            minimumHeight = activity.px(52)
            setBackgroundResource(R.drawable.bg_medkit_input)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.px(12) })
        }
    }
    fun choices(activity: AppCompatActivity, values: List<String>): ArrayAdapter<String> = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, values) {
        private fun decorate(view: View) = (view as TextView).apply {
            typeface = ResourcesCompat.getFont(activity, R.font.nunito)
            setTextColor(ContextCompat.getColor(activity, R.color.medkit_ink))
            textSize = 14f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            minHeight = activity.px(48); gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.px(16), activity.px(10), activity.px(16), activity.px(10))
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = decorate(super.getView(position, convertView, parent)).apply {
            val arrow = ContextCompat.getDrawable(activity, R.drawable.ic_expand_more)?.mutate()
            arrow?.setTint(ContextCompat.getColor(activity, R.color.medkit_ink))
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null)
            compoundDrawablePadding = activity.px(8)
        }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = decorate(super.getDropDownView(position, convertView, parent)).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        }
    }
    fun date(value: String): String = runCatching {
        java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    }.getOrElse { runCatching { java.time.LocalDate.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("d MMM")) }.getOrDefault(value) }
    fun label(value: String?) = value?.lowercase()?.replace('_', ' ')?.replaceFirstChar(Char::uppercaseChar).orEmpty()
}
