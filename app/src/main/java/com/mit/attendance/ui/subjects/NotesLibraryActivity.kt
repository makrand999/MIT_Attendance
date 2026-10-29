package com.mit.attendance.ui.subjects

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mit.attendance.auth.GitHubAccountStore
import com.mit.attendance.auth.NotesConnection
import com.mit.attendance.data.AttendanceRepository
import com.mit.attendance.storage.OutputDirectoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Account setup and one public class source per subject; private copies are explicit. */
class NotesLibraryActivity : AppCompatActivity() {
    private lateinit var store: GitHubAccountStore
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var token: EditText
    private lateinit var subject: AutoCompleteTextView
    private lateinit var source: EditText
    private lateinit var copies: LinearLayout
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "My subject notes"
        store = GitHubAccountStore(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        label("My subject notes", 24f)
        label("Connect your GitHub account, then create a private copy of each subject’s public class notes.")
        status = label("")
        token = EditText(this).apply {
            hint = "GitHub personal access token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setSingleLine()
        }
        content.addView(token)
        button("Create a token on GitHub") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://github.com/settings/tokens/new?scopes=repo&description=MIT%20Attendance")))
        }
        label("For a classic PAT, select repo and an expiry date. Class alert publishers also need gist permission. This grants access to your repositories. Tokens stay encrypted on this phone; they are never backed up or saved in repository URLs.")
        button("Connect / replace token") {
            val entered = token.text.toString().trim()
            if (entered.isBlank()) { message("Paste your GitHub token first."); return@button }
            operation("Validating GitHub account…", { python("validate_account", entered) }) { result ->
                store.save(result.getString("username"), entered)
                token.text.clear()
                renderConnections()
            }
        }
        button("Disconnect GitHub") {
            MaterialAlertDialogBuilder(this).setTitle("Disconnect GitHub?")
                .setMessage("Removes the token from this phone. Local notes and private repositories are kept. Reconnect the same account to use them again.")
                .setPositiveButton("Disconnect") { _, _ -> store.disconnect(); token.text.clear(); renderConnections() }
                .setNegativeButton("Cancel", null).show()
        }
        label("Connect a subject", 20f)
        subject = AutoCompleteTextView(this).apply { hint = "Subject"; threshold = 0; setSingleLine() }
        content.addView(subject)
        source = EditText(this).apply {
            hint = "https://github.com/class/subject-notes"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        content.addView(source)
        button("Preview private copy") { preview() }
        label("My connected subjects", 20f)
        copies = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(copies)
        button("Browse existing local notes") {
            startActivity(Intent(this, NotesActivity::class.java)
                .putExtra(NotesActivity.EXTRA_TYPE, NotesActivity.TYPE_LOCAL_NOTES))
        }
        label("Existing notes remain on the phone. Only files inside a connected subject folder are synced. Reviews are unchanged.")
        lifecycleScope.launch {
            val subjects = AttendanceRepository(this@NotesLibraryActivity).getSubjectsFlow().first().map { it.name }
            subject.setAdapter(ArrayAdapter(this@NotesLibraryActivity, android.R.layout.simple_dropdown_item_1line, subjects))
            subject.setOnClickListener { subject.showDropDown() }
        }
        renderConnections()
    }

    private fun preview() {
        val account = store.account() ?: run { message("Connect your GitHub account first."); return }
        val selectedSubject = subject.text.toString().trim()
        val url = source.text.toString().trim()
        if (selectedSubject.isBlank() || url.isBlank()) { message("Enter the subject and public notes URL."); return }
        operation("Checking class repository…", { python("inspect_source", account.token, url) }) { preview ->
            MaterialAlertDialogBuilder(this).setTitle("Create private notes copy?")
                .setMessage("Subject: $selectedSubject\nClass source: ${preview.getString("source")}\nPrivate destination: ${account.username}/${preview.getString("private_repo")}\n\nThe app will create or reconnect this private repository. Personal changes stay there. Class updates can be merged later.")
                .setPositiveButton("Create / reconnect") { _, _ ->
                    operation("Creating your private notes copy…", {
                        python("connect", OutputDirectoryManager.getNotesFolder(this).absolutePath,
                            account.token, account.username, url)
                    }) { result ->
                        val metadata = result.getJSONObject("metadata")
                        val connection = NotesConnection(selectedSubject, result.getString("folder"),
                            metadata.getString("source"), metadata.getString("private_repo"))
                        store.connect(account.username, connection)
                        renderConnections()
                        open(connection)
                    }
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun renderConnections() {
        copies.removeAllViews()
        val account = store.account()
        status.text = account?.let { "Connected as ${it.username}" } ?: "GitHub is not connected"
        if (account == null) return
        store.connections(account.username).forEach { connection ->
            copies.addView(Button(this).apply {
                text = "${connection.subject}\n${connection.privateRepo} (private)"
                isAllCaps = false
                setOnClickListener { if (!busy) open(connection) }
            })
        }
        if (copies.childCount == 0) copies.addView(TextView(this).apply { text = "No subjects connected yet." })
    }

    private fun open(connection: NotesConnection) {
        val account = store.requireAccount()
        val folder = File(connection.folder).canonicalFile
        val root = File(OutputDirectoryManager.getNotesFolder(this), account.username.lowercase(java.util.Locale.ROOT)).canonicalFile
        if (!folder.path.startsWith(root.path + File.separator)) { message("This notes folder belongs to another account."); return }
        startActivity(Intent(this, NotesActivity::class.java)
            .putExtra(NotesActivity.EXTRA_TYPE, NotesActivity.TYPE_NOTES)
            .putExtra(NotesActivity.EXTRA_NOTES_FOLDER, folder.path)
            .putExtra(NotesActivity.EXTRA_NOTES_SUBJECT, connection.subject))
    }

    private fun python(function: String, vararg args: Any): JSONObject {
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        val result = JSONObject(Python.getInstance().getModule("notes_git").callAttr(function, *args).toString())
        check(result.optBoolean("ok")) { result.optString("message", "GitHub operation failed") }
        return result
    }

    private fun operation(title: String, action: () -> JSONObject, complete: (JSONObject) -> Unit) {
        if (busy) return
        busy = true
        val dialog = MaterialAlertDialogBuilder(this).setMessage(title).setCancelable(false).create()
        dialog.show()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action() }
                busy = false
                complete(result)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { message(error.message ?: "Could not complete GitHub setup")
            } finally { busy = false; dialog.dismiss() }
        }
    }

    private fun label(text: String, size: Float = 15f): TextView = TextView(this).apply {
        this.text = text; textSize = size; setPadding(0, 16, 0, 12); content.addView(this)
    }
    private fun button(text: String, action: () -> Unit) {
        content.addView(Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { if (!busy) action() } })
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}
