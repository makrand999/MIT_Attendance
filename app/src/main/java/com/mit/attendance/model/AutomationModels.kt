package com.mit.attendance.model

import com.google.gson.annotations.SerializedName

data class AutomationScript(
    val steps: List<AutomationStep>
)

data class AutomationStep(
    val name: String,
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: String? = null,
    @SerializedName("expected_status") val expectedStatus: Int = 200,
    @SerializedName("pre_compute_js") val preComputeJs: String? = null,
    @SerializedName("post_compute_js") val postComputeJs: String? = null
)
