package com.mit.attendance.ui.certifications

object CertificationProxyParser {
    fun parse(raw: String): List<String> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { isValid(it) }
            .distinct()
            .toList()
    }

    fun isValid(entry: String): Boolean {
        val parts = entry.split(":")
        if (parts.size != 4) return false

        val host = parts[0].trim()
        val port = parts[1].toIntOrNull()
        val username = parts[2].trim()
        val password = parts[3].trim()

        return host.isNotEmpty() &&
            port != null &&
            port in 1..65535 &&
            username.isNotEmpty() &&
            password.isNotEmpty()
    }
}
