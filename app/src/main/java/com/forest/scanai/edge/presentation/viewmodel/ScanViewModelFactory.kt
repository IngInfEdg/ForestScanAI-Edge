package com.forest.scanai.edge.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.forest.scanai.edge.data.location.LocationProvider

class ScanViewModelFactory(
    private val locationProvider: LocationProvider,
    private val appVersionName: String,
    private val appVersionCode: Long,
    private val appVersionDisplay: String
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ScanViewModel::class.java)) {
            return ScanViewModel(
                locationProvider = locationProvider,
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
                appVersionDisplay = appVersionDisplay
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
