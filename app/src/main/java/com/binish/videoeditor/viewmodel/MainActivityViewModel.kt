package com.binish.videoeditor.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainActivityViewModel: ViewModel() {
    private var _heightWidth = MutableStateFlow(Pair(0.toDouble(),0.toDouble()))
    val heightWidth = _heightWidth.asStateFlow()

    fun updateHeightWidth(height: Int, width: Int) = _heightWidth.update {
        val aspectRatio = width.toDouble() / height
        Pair((height / aspectRatio), (width / aspectRatio))
    }
}