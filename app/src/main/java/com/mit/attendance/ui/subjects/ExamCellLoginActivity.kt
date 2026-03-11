package com.mit.attendance.ui.subjects

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.databinding.ActivityExamCellLoginBinding
import kotlinx.coroutines.launch

class ExamCellLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamCellLoginBinding
    private lateinit var prefs: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamCellLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = UserPreferences(applicationContext)

        binding.btnLogin.setOnClickListener {
            val userId = binding.etUserId.text.toString().trim()
            if (userId.isEmpty()) {
                Toast.makeText(this, "Please enter User ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                prefs.saveExamCellUserId(userId)
                startActivity(Intent(this@ExamCellLoginActivity, ExamCellWebViewActivity::class.java))
                finish()
            }
        }
    }
}
