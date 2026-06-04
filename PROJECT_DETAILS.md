# Bangla Speech AI Project Documentation

This document provides an in-depth technical breakdown of the Bangla Speech-to-Text (ASR) system. It outlines the core architecture, data pipelines, model weights, custom tokenizer logic, and optimizations implemented for both the local PC Web Server and the native, zero-dependency offline Android Mobile Application.

---

## 1. Core Mission & Model Weights

The primary objective of this project is to perform **100% local, offline, and private Bangla speech-to-text transcription** using Sanjid's fine-tuned model weights.

### The Model: `Sanji27/fountain`
* **Base Architecture**: Useful Sensors' **Moonshine** model (base variant). Moonshine is a sequence-to-sequence transformer designed specifically for fast, compute-efficient on-device speech-to-text inference. Unlike models like Whisper, it operates directly on raw audio waveforms, saving the overhead of computing Mel-spectrograms.
* **Sanjid's Fine-tuning**: Fine-tuned on high-quality Bangla audio datasets to translate spoken Bangla acoustic signals into exact Bangla text.
* **Export to ONNX (Open Neural Network Exchange)**:
  To run the model on Android without a Python runtime or PyTorch overhead, the model was compiled into optimized ONNX binaries:
  1. [encoder_model.onnx](file:///d:/my-speech-model/SpeechAIApp/app/src/main/assets/encoder_model.onnx) (~81 MB): Processes the raw normalized audio waveform and computes representations.
  2. [decoder_model.onnx](file:///d:/my-speech-model/SpeechAIApp/app/src/main/assets/decoder_model.onnx) (~218 MB): Takes predicted token sequences and encoder hidden states to generate token logits.

---

## 2. Tokenizer Architecture (`vocab.txt`)

Sanjid's model utilizes a custom-built **Bangla WordPiece Tokenizer** containing **32,000 sub-word tokens** configured in [vocab.txt](file:///d:/my-speech-model/SpeechAIApp/app/src/main/assets/vocab.txt).

### Special Token Mappings
* `[PAD]` (ID: `0`): Padding token to fill sequence lengths.
* `[UNK]` (ID: `1`): Unknown token (shown when audio maps to unrecognizable sounds).
* `[CLS]` / `BOS` (ID: `2`): Beginning of Sentence (tells the decoder to start writing).
* `[SEP]` / `EOS` (ID: `3`): End of Sentence (tells the decoder to stop writing).
* `[MASK]` (ID: `4`): Used during pre-training.

### WordPiece Sub-word Stitching Logic
The tokenizer splits words into sub-word tokens to handle rare words and keep the vocabulary size at 32k.
* If a token starts with `##`, it is a continuation of the previous word.
* **Example**: The Bangla word **"ধন্যবাদ"** might be tokenized into `ধন` and `##্যবাদ`.
* **Decoding Rule**: When stitching tokens, standard tokens are separated by spaces. If a token begins with `##`, the `##` prefix is removed, and the remaining letters are appended directly to the preceding word without spaces.

---

## 3. Detailed Data Flow & Working Visualization

Here is the step-by-step visual workflow of the transcription pipeline:

```mermaid
flowchart TD
    subgraph 1. Audio Acquisition
        A[Microphone Speech] -->|Analog Signal| B(Audio Capture Engine)
        B -->|Raw 16kHz 16-bit PCM Mono| C[ShortArray / PCM Buffer]
    end

    subgraph 2. Preprocessing
        C -->|Divide by 32768.0| D[FloatArray [-1.0, 1.0]]
        D -->|Compute Mean & Std Dev| E[Z-Score Normalization: (x - mean)/std]
    end

    subgraph 3. ONNX Inference
        E -->|Shape: [1, Audio_Length]| F[encoder_model.onnx]
        F -->|Hidden States: [1, Reduced_Seq_Len, 416]| G[decoder_model.onnx]
        
        %% Autoregressive Loop
        H[Generated Token List] -->|Starts with [CLS] ID: 2| G
        G -->|Logits Output: [1, Token_Seq_Len, 32000]| I[Greedy Search: argmax]
        I -->|Select Token ID with Max Probability| J{Is Token ID == [SEP] ID: 3?}
        J -->|No| K[Add ID to Generated Token List]
        K --> G
        J -->|Yes / Max Tokens Reached| L[Final Token ID Array]
    end

    subgraph 4. Text Decoding
        L -->|Drop BOS Token| M[vocab.txt Mapping]
        M -->|Sub-word Stitching: remove '##'| N[Final Bangla Text Output]
    end

    style A fill:#0F172A,stroke:#06B6D4,stroke-width:2px,color:#fff
    style C fill:#1E293B,stroke:#8B5CF6,stroke-width:2px,color:#fff
    style F fill:#0284C7,stroke:#fff,stroke-width:1px,color:#fff
    style G fill:#0284C7,stroke:#fff,stroke-width:1px,color:#fff
    style N fill:#15803D,stroke:#4ADE80,stroke-width:2px,color:#fff
```

### Detailed Signature Inputs and Outputs
* **Encoder**:
  * **Input name**: `input_values` (Type: `float32`, Shape: `[1, Audio_Length]`).
  * **Output name**: `last_hidden_state` (Type: `float32`, Shape: `[1, Reduced_Seq_Len, 416]`).
* **Decoder**:
  * **Input name**: `input_ids` (Type: `int64`, Shape: `[1, Token_Seq_Len]`).
  * **Input name**: `encoder_hidden_states` (Type: `float32`, Shape: `[1, Reduced_Seq_Len, 416]`).
  * **Output name**: `logits` (Type: `float32`, Shape: `[1, Token_Seq_Len, 32000]`).

---

## 4. Native Android App Architecture (`SpeechAIApp`)

The Android application is written in **Kotlin** using **Jetpack Compose** for a modern, hardware-accelerated user interface.

### A. The Core Modules

* **Audio Capturer ([AudioRecorder.kt](file:///d:/my-speech-model/SpeechAIApp/app/src/main/java/com/example/speechaiapp/asr/AudioRecorder.kt))**:
  Runs asynchronously on `Dispatchers.IO`. It polls the hardware microphone using Android's native `AudioRecord` API at 16,000Hz, Mono, and 16-bit PCM, accumulating audio shorts in a dynamic array.
  
* **ASR Engine ([BanglaSpeechModel.kt](file:///d:/my-speech-model/SpeechAIApp/app/src/main/java/com/example/speechaiapp/asr/BanglaSpeechModel.kt))**:
  Initializes ONNX Runtime sessions (`encoderSession`, `decoderSession`) and performs the mathematical pre-processing, autoregressive greedy decoding loops, and token-to-text translations.

* **UI View Model ([MainScreenViewModel.kt](file:///d:/my-speech-model/SpeechAIApp/app/src/main/java/com/example/speechaiapp/ui/main/MainScreenViewModel.kt))**:
  Coordinates states between user interactions (clicking the microphone button) and calls the background threads, converting hardware short buffers to floating-point normalized waveforms.

* **Compose UI layout ([MainScreen.kt](file:///d:/my-speech-model/SpeechAIApp/app/src/main/java/com/example/speechaiapp/ui/main/MainScreen.kt))**:
  Renders the slate-neon theme, permission prompts, state feedback, and clipboard utility.

### B. Critical Offline Stability Optimizations
Running large models on Android devices requires strategic system optimization:
1. **JVM Heap Bypass via Native Files**: Loading the 218 MB and 81 MB models directly into Java byte arrays triggers `OutOfMemoryError` on launch. The app streams the model assets into local private storage once and passes the absolute file path to ONNX Runtime. This enables direct native C++ file mapping (`mmap`), reducing JVM memory overhead to zero.
2. **Large Heap Request**: `android:largeHeap="true"` is declared in the [AndroidManifest.xml](file:///d:/my-speech-model/SpeechAIApp/app/src/main/AndroidManifest.xml), instructing the operating system to allocate additional virtual memory capacity (up to 512MB) for stable execution.

---

## 5. Local PC Web Application

A lightweight PC counterpart is provided in the root folder for testing and server deployments:
* **Backend ([app.py](file:///d:/my-speech-model/app.py))**: A FastAPI Python server. It uses `transformers` and `torch` to load `Sanji27/fountain` directly, downloads weights dynamically, accepts uploaded audio, downsamples audio using `librosa`/`soundfile` to 16kHz, and handles requests.
* **Frontend UI ([index.html](file:///d:/my-speech-model/static/index.html), [app.js](file:///d:/my-speech-model/static/app.js))**: Uses browser `AudioContext` to capture microphone inputs, downsamples it via a Box-filter, and sends it to the `/transcribe` API endpoint.
