package com.mit.attendance.ui.reviews

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.mit.attendance.databinding.FragmentSendReviewBinding

class SendReviewFragment : Fragment() {

    private var _binding: FragmentSendReviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ReviewsViewModel by viewModels { ReviewsViewModelFactory(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSendReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSend.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val rating = binding.ratingBar.rating
            val comment = binding.etComment.text.toString().trim()

            if (email.isEmpty()) {
                binding.tilEmail.error = "Required"
                return@setOnClickListener
            }
            binding.tilEmail.error = null

            if (comment.isEmpty()) {
                binding.tilComment.error = "Required"
                return@setOnClickListener
            }
            binding.tilComment.error = null

            binding.btnSend.isEnabled = false
            viewModel.sendReview(email, rating, comment)
        }

        viewModel.sendResult.observe(viewLifecycleOwner) { result ->
            binding.btnSend.isEnabled = true
            if (result.isSuccess) {
                Toast.makeText(requireContext(), "Review sent successfully!", Toast.LENGTH_SHORT).show()
                binding.etEmail.text?.clear()
                binding.etComment.text?.clear()
                binding.ratingBar.rating = 0f
            } else {
                Toast.makeText(requireContext(), "Failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
