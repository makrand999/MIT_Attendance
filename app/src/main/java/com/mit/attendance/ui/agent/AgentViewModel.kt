package com.mit.attendance.ui.agent

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mit.attendance.BuildConfig
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.storage.OutputDirectoryManager
import com.mit.attendance.agent.AgentRequestAuth
import com.mit.attendance.ui.subjects.NotesActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class AgentViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { LOADING, READY, ERROR }

    private val _phase = MutableStateFlow(Phase.LOADING)
    val phase = _phase.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects = _projects.asStateFlow()

    private val _selectedProject = MutableStateFlow<String?>(null)
    val selectedProject = _selectedProject.asStateFlow()

    private val _userEmail = MutableStateFlow<String?>(null)
    val userEmail = _userEmail.asStateFlow()

    private val _remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFiles = _remoteFiles.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath = _currentPath.asStateFlow()

    private val prefs = UserPreferences(app.applicationContext)
    private val client = OkHttpClient()
    private val serverHttpBaseUrl = BuildConfig.QWEN_WS_URL
        .replaceFirst("ws://", "http://")
        .replaceFirst("wss://", "https://")
    private suspend fun getUserEmail(): String? =
        prefs.getEmail()?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    fun init() {
        viewModelScope.launch {
            try {
                _phase.value = Phase.LOADING
                val email = getUserEmail() ?: error("No saved email found")
                val preferredProject = prefs.getAgentSelectedProject()
                _userEmail.value = email
                loadProjects(email, preferredProject)
                _phase.value = Phase.READY
                fetchRemoteFiles()
            } catch (e: Exception) {
                _error.value = "Failed to connect: ${e.message}"
                _phase.value = Phase.ERROR
            }
        }
    }

    fun fetchRemoteFiles() {
        val emailJob = viewModelScope.launch(Dispatchers.IO) {
            val email = getUserEmail() ?: return@launch
            val project = _selectedProject.value ?: return@launch
            val path = _currentPath.value

            try {
                val request = Request.Builder()
                    .url(
                        "$serverHttpBaseUrl/files?email=${Uri.encode(email)}" +
                            "&project=${Uri.encode(project)}" +
                            "&path=${Uri.encode(path)}" +
                            "&${AgentRequestAuth.buildQuery(email)}"
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw Exception("Failed to load files: ${response.code}")
                    }

                    val json = JSONObject(body)
                    _currentPath.value = json.optString("path", path)
                    val projectRoot = File(OutputDirectoryManager.getRootFolder(getApplication()), project)
                    _remoteFiles.value = parseRemoteFiles(
                        json.optJSONArray("files") ?: JSONArray(),
                        projectRoot
                    )
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load files."
            }
        }
        emailJob.invokeOnCompletion { }
    }

    fun handleFileClick(file: RemoteFile) {
        if (file.isDirectory) {
            _currentPath.value = file.path
            fetchRemoteFiles()
            return
        }

        if (file.isDownloaded) {
            openFile(getLocalProjectFile(file.path))
        } else {
            downloadFileOnly(file)
        }
    }

    fun navigateUp() {
        val parts = _currentPath.value.trim('/').split('/').filter { it.isNotBlank() }
        _currentPath.value = parts.dropLast(1).joinToString("/")
        fetchRemoteFiles()
    }

    fun loadProjects() {
        viewModelScope.launch {
            val email = getUserEmail() ?: return@launch
            try {
                _userEmail.value = email
                loadProjects(email)
                fetchRemoteFiles()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun selectProject(projectName: String) {
        if (projectName == _selectedProject.value) return

        viewModelScope.launch {
            try {
                _phase.value = Phase.LOADING
                applySelectedProject(projectName)
                _phase.value = Phase.READY
                fetchRemoteFiles()
            } catch (e: Exception) {
                _error.value = "Failed to switch project: ${e.message}"
                _phase.value = Phase.ERROR
            }
        }
    }

    fun createProject(name: String) {
        val emailJob = viewModelScope.launch {
            val email = getUserEmail() ?: return@launch
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                _error.value = "Project name cannot be empty."
                return@launch
            }

            try {
                val requestBody = JSONObject()
                    .put("email", email)
                    .put("project", trimmed)
                    .toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$serverHttpBaseUrl/projects?${AgentRequestAuth.buildQuery(email)}")
                    .post(requestBody)
                    .build()

                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw Exception("Failed to create project: ${response.code}")
                        }
                    }
                }

                loadProjects(email)
                _userEmail.value = email
                selectProject(trimmed)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
        emailJob.invokeOnCompletion { }
    }

    fun clearError() {
        _error.value = null
    }

    private suspend fun loadProjects(email: String, preferredProject: String? = null) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$serverHttpBaseUrl/projects?email=${Uri.encode(email)}&${AgentRequestAuth.buildQuery(email)}")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("Failed to load projects: ${response.code}")
            }

            val json = JSONObject(body)
            val projects = mutableListOf<ProjectSummary>()
            val array = json.optJSONArray("projects") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                projects.add(
                    ProjectSummary(
                        name = item.optString("name"),
                        fileCount = item.optInt("fileCount"),
                        updatedAt = item.optString("updatedAt").takeIf { it.isNotBlank() }
                    )
                )
            }

            _projects.value = projects

            val nextProject = preferredProject
                ?.takeIf { preferred -> projects.any { it.name == preferred } }
                ?: _selectedProject.value
                    ?.takeIf { selected -> projects.any { it.name == selected } }
                ?: projects.firstOrNull()?.name

            if (nextProject != null) {
                applySelectedProject(nextProject)
            }
        }
    }

    private suspend fun applySelectedProject(projectName: String) {
        _selectedProject.value = projectName
        _currentPath.value = ""
        prefs.setAgentSelectedProject(projectName)
    }

    private fun parseRemoteFiles(array: JSONArray, projectRoot: File): List<RemoteFile> {
        val list = mutableListOf<RemoteFile>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val relativePath = obj.getString("path")
            val isDirectory = obj.getBoolean("isDirectory")
            list.add(
                RemoteFile(
                    name = obj.getString("name"),
                    path = relativePath,
                    isDirectory = isDirectory,
                    sizeBytes = if (obj.has("sizeBytes")) obj.getLong("sizeBytes") else null,
                    updatedAt = obj.optString("updatedAt"),
                    isDownloaded = isDirectory || File(projectRoot, relativePath).exists()
                )
            )
        }
        return list
    }

    private suspend fun downloadFile(remoteFile: RemoteFile): File = withContext(Dispatchers.IO) {
        val email = getUserEmail() ?: throw IllegalStateException("No saved email found")
        val project = _selectedProject.value ?: throw IllegalStateException("No project selected")
        val url = "$serverHttpBaseUrl/files/download?email=${Uri.encode(email)}" +
            "&project=${Uri.encode(project)}" +
            "&path=${Uri.encode(remoteFile.path)}" +
            "&${AgentRequestAuth.buildQuery(email)}"

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Server returned ${response.code}")
            val body = response.body ?: throw Exception("Response body is null")

            val projectRoot = File(OutputDirectoryManager.getRootFolder(getApplication()), project)
            if (!projectRoot.exists()) {
                projectRoot.mkdirs()
            }
            val localFile = File(projectRoot, remoteFile.path)
            localFile.parentFile?.mkdirs()

            body.byteStream().use { input ->
                FileOutputStream(localFile).use { output ->
                    input.copyTo(output)
                }
            }
            localFile
        }
    }

    private fun downloadFileOnly(remoteFile: RemoteFile) {
        viewModelScope.launch {
            try {
                downloadFile(remoteFile)
                fetchRemoteFiles()
            } catch (e: Exception) {
                _error.value = "Download failed: ${e.message}"
            }
        }
    }

    private fun getLocalProjectFile(relativePath: String): File {
        val context = getApplication<Application>()
        val project = _selectedProject.value ?: throw IllegalStateException("No project selected")
        val projectRoot = File(OutputDirectoryManager.getRootFolder(context), project)
        return File(projectRoot, relativePath)
    }

    private fun openFile(file: File) {
        try {
            val context = getApplication<Application>()
            val project = _selectedProject.value ?: throw IllegalStateException("No project selected")
            val projectRoot = File(OutputDirectoryManager.getRootFolder(context), project)
            val relativePath = file.relativeTo(projectRoot).invariantSeparatorsPath

            val intent = Intent(context, NotesActivity::class.java).apply {
                putExtra(NotesActivity.EXTRA_TYPE, NotesActivity.TYPE_AGENT)
                putExtra(NotesActivity.EXTRA_AGENT_ROOT_SUBDIR, project)
                putExtra(NotesActivity.EXTRA_INITIAL_RELATIVE_PATH, relativePath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _error.value = "Could not open file: ${e.message}"
        }
    }

    data class RemoteFile(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val sizeBytes: Long?,
        val updatedAt: String?,
        val isDownloaded: Boolean
    )

    data class ProjectSummary(
        val name: String,
        val fileCount: Int,
        val updatedAt: String?
    )
}
