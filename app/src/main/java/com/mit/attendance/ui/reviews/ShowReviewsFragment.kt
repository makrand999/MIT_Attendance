package com.mit.attendance.ui.reviews

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mit.attendance.R
import com.mit.attendance.databinding.FragmentShowReviewsBinding
import com.mit.attendance.databinding.ItemReviewBinding
import com.mit.attendance.model.ReviewEntity

class ShowReviewsFragment : Fragment() {

    private var _binding: FragmentShowReviewsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ReviewsViewModel by viewModels { ReviewsViewModelFactory(requireContext()) }
    private lateinit var adapter: ReviewAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentShowReviewsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        adapter = ReviewAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#33FFFFFF"))
        binding.swipeRefresh.setColorSchemeColors(Color.WHITE)

        viewModel.reviews.observe(viewLifecycleOwner) {
            adapter.submitList(it)
        }

        viewModel.isRefreshing.observe(viewLifecycleOwner) {
            binding.swipeRefresh.isRefreshing = it
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ReviewAdapter : ListAdapter<ReviewEntity, ReviewAdapter.VH>(DiffCallback) {
        object DiffCallback : DiffUtil.ItemCallback<ReviewEntity>() {
            override fun areItemsTheSame(oldItem: ReviewEntity, newItem: ReviewEntity) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ReviewEntity, newItem: ReviewEntity) = oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemReviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        class VH(private val binding: ItemReviewBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(review: ReviewEntity) {
                binding.tvGender.visibility = View.GONE
                binding.tvComment.text = review.comment
                binding.ratingBar.rating = review.rating
                binding.tvTimestamp.text = review.timestamp

                val context = binding.root.context
                val isFemale = review.gender.equals("Female", ignoreCase = true)
                val cardView = binding.root as com.google.android.material.card.MaterialCardView
                
                if (isFemale) {
                    cardView.strokeColor = ContextCompat.getColor(context, R.color.review_female_stroke)
                    cardView.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
                } else {
                    cardView.strokeColor = ContextCompat.getColor(context, R.color.glass_card_stroke)
                    cardView.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
                }
            }
        }
    }
}
