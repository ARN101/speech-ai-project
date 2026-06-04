package com.example.speechaiapp.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun startRecording(onAudioData: (ShortArray) -> Unit) {
        if (isRecording) return

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioRecord. Check microphone permissions.")
        }

        audioRecord = recorder
        recorder.startRecording()
        isRecording = true

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val readSize = recorder.read(buffer, 0, buffer.size)
                if (readSize > 0) {
                    val data = ShortArray(readSize)
                    System.arraycopy(buffer, 0, data, 0, readSize)
                    onAudioData(data)
                }
            }
        }
    }

    fun stopRecording(): ShortArray {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                stop()
                release()
            }
        }
        audioRecord = null

        return ShortArray(0)
    }
}
