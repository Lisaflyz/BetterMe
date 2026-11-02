package com.betterme.app

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private const val TAG = "DayRecordViewModel"

class DayRecordViewModel : ViewModel() {

    fun initFetchData() {
        val exceptionHandler = CoroutineExceptionHandler { _, e ->
           Log.e(TAG, "initFetch failed: $e")
        }

        viewModelScope.launch(exceptionHandler) { // SupervisorJob + MainCoroutineDispatcher.immediate；结构化并发

            val job1 = async {  }
            val job2 = async {  }

        }

    }

}