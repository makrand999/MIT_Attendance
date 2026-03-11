package com.mit.attendance.ui.reviews

import android.content.Context
import androidx.lifecycle.*
import com.mit.attendance.data.ReviewRepository
import com.mit.attendance.model.ReviewEntity
import com.mit.attendance.model.SingleLiveEvent
import kotlinx.coroutines.launch

class ReviewsViewModel(private val repo: ReviewRepository) : ViewModel() {

    val reviews = repo.getReviewsFlow().asLiveData()

    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    private val _sendResult = SingleLiveEvent<Result<String>>()
    val sendResult: LiveData<Result<String>> = _sendResult

    private val _error = SingleLiveEvent<String>()
    val error: LiveData<String> = _error

    init {
        refresh()
    }

    fun refresh() {
        if (_isRefreshing.value == true) return
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = repo.syncReviews()
            if (result.isFailure) {
                _error.call("Failed to fetch reviews: ${result.exceptionOrNull()?.message}")
            }
            _isRefreshing.value = false
        }
    }

    fun sendReview(targetEmail: String, rating: Float, comment: String) {
        viewModelScope.launch {
            val result = repo.sendReview(targetEmail, rating, comment)
            _sendResult.call(result)
        }
    }
}

class ReviewsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ReviewsViewModel(ReviewRepository(context)) as T
    }
}
