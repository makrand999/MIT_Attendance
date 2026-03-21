package com.mit.attendance.ui.reviews

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.mit.attendance.R
import com.mit.attendance.data.prefs.UserPreferences
import com.mit.attendance.databinding.ActivityReviewsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ReviewsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewsBinding
    private lateinit var prefs: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = UserPreferences(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val adapter = ReviewsPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = if (position == 0) "Show Reviews" else "Send Review"
        }.attach()

        observeSettings()
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            prefs.bgDetailUri.collectLatest { uriString ->
                if (uriString != null) {
                    binding.ivBackground.setImageURI(uriString.toUri())
                } else {
                    binding.ivBackground.setImageResource(R.drawable.bg_detail)
                }
            }
        }
    }

    private inner class ReviewsPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment {
            return if (position == 0) ShowReviewsFragment() else SendReviewFragment()
        }
    }
}
