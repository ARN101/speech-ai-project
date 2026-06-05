package com.example.speechaiapp.asr

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

class BanglaSpeechModel(private val context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoderSession: OrtSession
    private val decoderSession: OrtSession
    private val vocab: List<String>

    init {
        // Load vocab.txt from assets
        vocab = loadVocab("vocab.txt")

        // Copy ONNX models to local files if they don't exist
        val encoderFile = copyAssetToFile("encoder_model.onnx")
        val decoderFile = copyAssetToFile("decoder_model.onnx")

        // Configure SessionOptions (4 threads, ALL_OPT graph optimization for mobile CPU speed)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        // Load sessions from file paths (uses native memory/mmap, avoids JVM OOM)
        encoderSession = env.createSession(encoderFile.absolutePath, options)
        decoderSession = env.createSession(decoderFile.absolutePath, options)
    }

    private fun loadVocab(fileName: String): List<String> {
        val list = mutableListOf<String>()
        context.assets.open(fileName).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    list.add(line)
                    line = reader.readLine()
                }
            }
        }
        return list
    }

    private fun copyAssetToFile(fileName: String): File {
        val outFile = File(context.filesDir, fileName)
        
        // Open the asset to check size
        context.assets.open(fileName).use { assetStream ->
            val assetSize = assetStream.available().toLong()
            
            // If the file already exists and has the correct size, skip copying
            if (outFile.exists() && outFile.length() == assetSize) {
                return outFile
            }
            
            // Otherwise, copy in chunks to avoid high memory consumption
            FileOutputStream(outFile).use { outStream ->
                val buffer = ByteArray(64 * 1024) // 64KB buffer
                var bytesRead = assetStream.read(buffer)
                while (bytesRead != -1) {
                    outStream.write(buffer, 0, bytesRead)
                    bytesRead = assetStream.read(buffer)
                }
            }
        }
        return outFile
    }

    fun transcribe(rawAudio: FloatArray): String {
        if (rawAudio.isEmpty()) return ""

        // 1. Preprocessing: Z-score normalization (zero-mean unit-variance)
        val mean = rawAudio.average().toFloat()
        var varianceSum = 0.0
        for (x in rawAudio) {
            val diff = x - mean
            varianceSum += diff * diff
        }
        val variance = (varianceSum / rawAudio.size).toFloat()
        val std = sqrt(variance + 1e-7f)

        val normalizedAudio = FloatArray(rawAudio.size) { i ->
            (rawAudio[i] - mean) / std
        }

        // 2. Run Encoder
        val batchSize = 1L
        val encSeqLen = normalizedAudio.size.toLong()
        val encInputShape = longArrayOf(batchSize, encSeqLen)
        
        val audioBuffer = FloatBuffer.wrap(normalizedAudio)
        val encInputTensor = OnnxTensor.createTensor(env, audioBuffer, encInputShape)
        
        val encOutputs = encoderSession.run(mapOf("input_values" to encInputTensor))
        val lastHiddenStateTensor = encOutputs.get(0) as OnnxTensor
        val lastHiddenStateShape = lastHiddenStateTensor.info.shape // Shape: [1, reduced_length, 416]
        
        val reducedLength = lastHiddenStateShape[1]
        val hiddenSize = lastHiddenStateShape[2] // 416
        
        val lastHiddenStateBuffer = lastHiddenStateTensor.floatBuffer
        
        // Copy hidden state buffer so we can keep using it
        val hiddenStateArray = FloatArray(lastHiddenStateBuffer.remaining())
        lastHiddenStateBuffer.get(hiddenStateArray)
        
        encOutputs.close()
        encInputTensor.close()

        // 3. Run Decoder Autoregressively
        val generatedTokens = mutableListOf<Long>(2L) // start token id = 2 (BOS / CLS)
        val maxNewTokens = 256
        val eosTokenId = 3L

        // Hoist the constant encoder hidden state tensor creation out of the loop
        // to avoid allocating and copying 80MB of JNI memory on every token generation step.
        val hiddenStateBuffer = FloatBuffer.wrap(hiddenStateArray)
        val encoderHiddenStatesTensor = OnnxTensor.createTensor(env, hiddenStateBuffer, lastHiddenStateShape)

        for (step in 0 until maxNewTokens) {
            val decSeqLen = generatedTokens.size.toLong()
            val decInputShape = longArrayOf(batchSize, decSeqLen)
            
            // Create inputs for decoder
            val inputIdsBuffer = LongBuffer.wrap(generatedTokens.toLongArray())
            val inputIdsTensor = OnnxTensor.createTensor(env, inputIdsBuffer, decInputShape)

            val decOutputs = decoderSession.run(mapOf(
                "input_ids" to inputIdsTensor,
                "encoder_hidden_states" to encoderHiddenStatesTensor
            ))
            
            val logitsTensor = decOutputs.get(0) as OnnxTensor
            val logitsBuffer = logitsTensor.floatBuffer
            val vocabSize = 32000 // vocabulary size
            
            // We only need the logits for the last generated token
            // Logits shape: [1, decSeqLen, 32000]
            val lastTokenOffset = ((decSeqLen - 1) * vocabSize).toInt()
            val lastTokenLogits = FloatArray(vocabSize)
            logitsBuffer.position(lastTokenOffset)
            logitsBuffer.get(lastTokenLogits)
            
            // Find the token ID with the highest probability (greedy search)
            var maxLogit = -Float.MAX_VALUE
            var nextTokenId = 0L
            for (v in 0 until vocabSize) {
                if (lastTokenLogits[v] > maxLogit) {
                    maxLogit = lastTokenLogits[v]
                    nextTokenId = v.toLong()
                }
            }
            
            decOutputs.close()
            inputIdsTensor.close()
            
            if (nextTokenId == eosTokenId) {
                break
            }
            
            generatedTokens.add(nextTokenId)
        }
        encoderHiddenStatesTensor.close()

        // 4. Tokenizer Decoding
        val decodedText = decodeTokens(generatedTokens.drop(1)) // drop start token
        val trimmedText = decodedText.trim()
        val cleanedText = if (trimmedText.startsWith("মিউজিক")) {
            trimmedText.substring("মিউজিক".length).trim()
        } else {
            trimmedText
        }
        return cleanedText
    }

    private fun decodeTokens(tokens: List<Long>): String {
        val result = StringBuilder()
        for (tokenId in tokens) {
            val id = tokenId.toInt()
            if (id < 0 || id >= vocab.size) continue
            val token = vocab[id]
            
            if (token == "[PAD]" || token == "[CLS]" || token == "[SEP]" || token == "[MASK]") {
                continue
            }
            
            if (token.startsWith("##")) {
                result.append(token.substring(2))
            } else {
                if (result.isNotEmpty()) {
                    result.append(" ")
                }
                result.append(token)
            }
        }
        return result.toString()
    }

    fun close() {
        encoderSession.close()
        decoderSession.close()
        env.close()
    }
}
