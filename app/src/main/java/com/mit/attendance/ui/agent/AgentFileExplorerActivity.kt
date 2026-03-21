package com.mit.attendance.ui.agent

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mit.attendance.R
import com.mit.attendance.databinding.ActivityAgentFileExplorerBinding
import com.mit.attendance.databinding.ItemFileExplorerBinding
import com.mit.attendance.storage.OutputDirectoryManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AgentFileExplorerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAgentFileExplorerBinding
    private lateinit var explorerAdapter: FileExplorerAdapter
    private var currentDirectory: File? = null
    private var rootDirectory: File? = null
    private var selectedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentFileExplorerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rootDirectory = OutputDirectoryManager.getRootFolder(this)
        currentDirectory = rootDirectory

        setupUI()
        browseFiles(currentDirectory)
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            if (selectedFile != null) {
                closeFile()
            } else if (currentDirectory != rootDirectory) {
                navigateUp()
            } else {
                finish()
            }
        }

        explorerAdapter = FileExplorerAdapter(
            onFileClick = { file -> openFile(file) },
            onShareClick = { file -> shareFile(file) }
        )
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = explorerAdapter

        binding.btnShareFile.setOnClickListener {
            selectedFile?.let { shareFile(it) }
        }
    }

    private fun browseFiles(directory: File?) {
        val target = directory ?: rootDirectory!!
        currentDirectory = target
        binding.toolbar.title = if (target == rootDirectory) "Files" else target.name
        val files = target.listFiles()?.toList()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
        explorerAdapter.submitList(files)
        
        binding.rvFiles.isVisible = true
        binding.scrollFileContent.isVisible = false
        binding.btnShareFile.isVisible = false
        selectedFile = null
    }

    private fun openFile(file: File) {
        if (file.isDirectory) {
            browseFiles(file)
        } else {
            selectedFile = file
            val content = try { file.readText() } catch (e: Exception) { "Error reading file: ${e.message}" }
            binding.rvFiles.isVisible = false
            binding.scrollFileContent.isVisible = true
            binding.tvFileContent.text = content
            binding.toolbar.title = file.name
            binding.btnShareFile.isVisible = true
        }
    }

    private fun closeFile() {
        selectedFile = null
        browseFiles(currentDirectory)
    }

    private fun navigateUp() {
        val parent = currentDirectory?.parentFile
        if (parent != null && parent.absolutePath.startsWith(rootDirectory!!.absolutePath)) {
            browseFiles(parent)
        } else {
            browseFiles(rootDirectory)
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share File"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (selectedFile != null) {
            closeFile()
        } else if (currentDirectory != rootDirectory) {
            navigateUp()
        } else {
            super.onBackPressed()
        }
    }

    inner class FileExplorerAdapter(
        private val onFileClick: (File) -> Unit,
        private val onShareClick: (File) -> Unit
    ) : RecyclerView.Adapter<FileExplorerAdapter.VH>() {
        private var items = listOf<File>()
        private val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.US)

        fun submitList(newItems: List<File>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemFileExplorerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = items[position]
            holder.binding.tvFileName.text = file.name
            
            val info = if (file.isDirectory) "Folder" 
            else "${file.length() / 1024} KB • ${sdf.format(Date(file.lastModified()))}"
            holder.binding.tvFileInfo.text = info
            
            holder.binding.ivFileIcon.setImageResource(
                if (file.isDirectory) R.drawable.ic_back else R.drawable.ic_file
            )
            if (file.isDirectory) holder.binding.ivFileIcon.rotation = 180f else holder.binding.ivFileIcon.rotation = 0f

            holder.binding.root.setOnClickListener { onFileClick(file) }
            holder.binding.btnShare.isVisible = !file.isDirectory
            holder.binding.btnShare.setOnClickListener { onShareClick(file) }
        }

        override fun getItemCount() = items.size
        inner class VH(val binding: ItemFileExplorerBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
