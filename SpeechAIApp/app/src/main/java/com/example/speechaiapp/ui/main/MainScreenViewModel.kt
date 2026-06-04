package com.example.speechaiapp.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.speechaiapp.asr.AudioRecorder
import com.example.speechaiapp.asr.BanglaSpeechModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ASRStatus {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    ERROR
}

data class ASRUiState(
    val status: ASRStatus = ASRStatus.IDLE,
    val transcription: String = "",
    val errorMessage: String? = null
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ASRUiState())
    val uiState: StateFlow<ASRUiState> = _uiState.asStateFlow()

    private var speechModel: BanglaSpeechModel? = null
    private val audioRecorder = AudioRecorder()
    private val recordedAudioData = ArrayList<Short>()

    init {
        // Load model in a background thread to prevent blocking main UI thread on startup
        viewModelScope.launch(Dispatchers.IO) {
            try {
                speechModel = BanglaSpeechModel(getApplication())
            } catch (e: Exception) {
                _uiState.value = ASRUiState(
                    status = ASRStatus.ERROR,
                    errorMessage = "Failed to load speech model: ${e.message}"
                )
            }
        }
    }

    fun startRecording() {
        if (speechModel == null) {
            _uiState.value = ASRUiState(
                status = ASRStatus.ERROR,
                errorMessage = "Model is still loading. Please wait."
            )
            return
        }

        recordedAudioData.clear()
        _uiState.value = ASRUiState(status = ASRStatus.LISTENING)

        try {
            audioRecorder.startRecording { data ->
                synchronized(recordedAudioData) {
                    for (sample in data) {
                        recordedAudioData.add(sample)
                    }
                }
            }
        } catch (e: Exception) {
            _uiState.value = ASRUiState(
                status = ASRStatus.ERROR,
                errorMessage = "Mic error: ${e.message}"
            )
        }
    }

    fun stopRecording() {
        audioRecorder.stopRecording()
        
        val shorts = synchronized(recordedAudioData) {
            recordedAudioData.toShortArray()
        }

        if (shorts.isEmpty()) {
            _uiState.value = ASRUiState(status = ASRStatus.IDLE)
            return
        }

        _uiState.value = ASRUiState(status = ASRStatus.TRANSCRIBING)

        viewModelScope.launch {
            try {
                val transcriptionResult = withContext(Dispatchers.Default) {
                    // Convert short PCM to normalized float PCM [-1.0, 1.0]
                    val floatAudio = FloatArray(shorts.size) { i ->
                        shorts[i].toFloat() / 32768.0f
                    }
                    
                    speechModel?.transcribe(floatAudio) ?: "Model not loaded"
                }

                _uiState.value = ASRUiState(
                    status = ASRStatus.IDLE,
                    transcription = transcriptionResult
                )
            } catch (e: Exception) {
                _uiState.value = ASRUiState(
                    status = ASRStatus.ERROR,
                    errorMessage = "Transcription failed: ${e.message}"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stopRecording()
        viewModelScope.launch(Dispatchers.IO) {
            speechModel?.close()
        }
    }
}
