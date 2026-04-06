package com.forest.scanai.presentation

import androidx.lifecycle.ViewModel
import com.forest.scanai.domain.model.ScanSessionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScanReviewViewModel : ViewModel() {
    private val _scanResult = MutableStateFlow<ScanSessionResult?>(null)
    val scanResult = _scanResult.asStateFlow()

    private val _viewMode = MutableStateFlow(ReviewMode.POINTS)
    val viewMode = _viewMode.asStateFlow()

    fun setScanResult(result: ScanSessionResult) {
        _scanResult.value = result
    }

    fun setViewMode(mode: ReviewMode) {
        _viewMode.value = mode
    }

    enum class ReviewMode {
        POINTS, COVERAGE, MESH
    }
}
