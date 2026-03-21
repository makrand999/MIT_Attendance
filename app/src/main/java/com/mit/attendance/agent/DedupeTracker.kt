package com.mit.attendance.agent


import android.util.Log

private const val TAG = "DedupeTracker"

// ─────────────────────────────────────────
//  DEDUPE TRACKER  — generic, field-agnostic
//
//  Replaces TopicTracker. Works for ANY dedupeField:
//  topics, chapter titles, endpoint names, table names, etc.
//
//  Injects the "already done" list into planPrompt via {done_items}.
//  WorkerAgent doesn't know what the values mean — just tracks them.
// ─────────────────────────────────────────
class DedupeTracker(
    initialValues: List<String> = emptyList(),
    private val enabled: Boolean = true          // false when dedupeField == null
) {
    private val done = initialValues.toMutableList()

    // ── INJECT into planPrompt ────────────────────────────────────
    /**
     * Replaces the {done_items} placeholder in [prompt] with the
     * list of already-completed dedupe values.
     * If the placeholder isn't present, appends the list at the end.
     */
    fun inject(prompt: String): String {
        if (!enabled) return prompt.replace("{done_items}", "none")

        val list = if (done.isEmpty()) "none yet"
        else done.takeLast(150).joinToString(", ")

        return if (prompt.contains("{done_items}")) {
            prompt.replace("{done_items}", list)
        } else {
            // Fallback: append a reminder block if prompt author forgot the placeholder
            "$prompt\n\nAlready completed (do NOT repeat): $list"
        }
    }

    // ── REGISTER a completed value ────────────────────────────────
    fun register(value: String) {
        if (enabled && value.isNotBlank()) {
            done.add(value)
            Log.d(TAG, "Registered: '$value' (total: ${done.size})")
        }
    }

    // ── REGISTER from a solved task ───────────────────────────────
    fun registerFromTask(task: TopLevelTask) {
        if (task.chosenDedupeValue.isNotBlank()) register(task.chosenDedupeValue)
    }

    // ── SNAPSHOT for checkpoint ───────────────────────────────────
    fun snapshot(): List<String> = done.toList()

    val count get() = done.size
}