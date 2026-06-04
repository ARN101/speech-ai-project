# Bangla Speech AI Project Details

This document provides a comprehensive overview of the model, tokenizer, and architecture utilized by the Bangla Speech AI application (both for the PC Web Server and the native Android app).

---

## 1. Model Details

The application uses **Sanjid's Fine-tuned Moonshine Model (`Sanji27/fountain`)** for 100% of its speech recognition logic.
* **Architecture**: Based on Useful Sensors' Moonshine model (an extremely lightweight and efficient sequence-to-sequence transformer model optimized for on-device execution).
* **Fine-Tuning**: Fine-tuned on Bangla speech datasets to achieve high transcription accuracy.
* **On-Device Export**: For mobile execution, the model weights were compiled into optimized ONNX Runtime binaries, split into two modules:
  * **Encoder Model**: `encoder_model.onnx` (~81 MB) — Processes the normalized audio waveform and generates hidden state representations.
  * **Decoder Model**: `decoder_model.onnx` (~218 MB) — Autoregressively generates token logits from input token IDs and the encoder's hidden states.

---

## 2. Tokenizer Details

The model utilizes **Sanjid's Custom Bangla WordPiece Tokenizer** to map logits back into readable Bangla text.
* **Vocabulary Size**: 32,000 tokens (custom-built for the Bangla language).
* **Implementation**: The vocabulary is saved inside the app assets as `vocab.txt`.
* **Sub-word Decoding**: The model generates sequence token IDs. The application decodes these IDs using the vocabulary, handling WordPiece continuation tokens (tokens starting with `##` are appended directly to the preceding word without spaces, while normal tokens are separated by spaces).
* **Special Tokens**: Standard sequence control tokens are implemented:
  * `[PAD]` (ID: 0) — Padding
  * `[UNK]` (ID: 1) — Unknown token
  * `[CLS]` / `BOS` (ID: 2) — Beginning of Sentence
  * `[SEP]` / `EOS` (ID: 3) — End of Sentence
  * `[MASK]` (ID: 4) — Masking token

---

## 3. Project Architecture and Code Layout

The project is structured to support both local web testing on PC and native, zero-dependency offline transcription on Android devices.

### A. Android Mobile Application (`SpeechAIApp`)
Located in [SpeechAIApp/](file:///d:/my-speech-model/SpeechAIApp):
* **ASR Core Engine**:
  * [BanglaSpeechModel.kt](file:///d:/my-speech-model/SpeechAIApp/app/src/main/java/com/example/speechaiapp/asr/BanglaSpeechModel.kt): Performs Z-score audio normalization, executes the ONNX encoder, runs the ONNX decoder in an autoregressive loop (greedy argmax search), and decodes the output token IDs using the Bangla vocabulary.
  * [AudioRecorder.kt](file:///d:/my-speech-model/SpeechAIApp/app/src/main/java/com/example/speechaiapp/asr/AudioRecorder.kt): Captures raw microphone audio at **16kHz mono 16-bit PCM** (the exact input format required by the model).
* **UI & View Model**:
  * [MainScreen.kt](file:///d:/my-speech-model/SpeechAIApp/app/src/main/java/com/example/speechaiapp/ui/main/MainScreen.kt): Styled slate-neon Jetpack Compose interface with micro-animations, ripple wave feedback, and clip-to-clipboard functionality.
  * [MainScreenViewModel.kt](file:///d:/my-speech-model/SpeechAIApp/app/src/main/java/com/example/speechaiapp/ui/main/MainScreenViewModel.kt): Coordinates recording states and manages the background worker threads.
* **Manifest & Assets**:
  * [AndroidManifest.xml](file:///d:/my-speech-model/SpeechAIApp/app/src/main/AndroidManifest.xml): Declares audio recording permissions and requests `largeHeap="true"` to prevent JVM out-of-memory states.
  * `assets/`: Contains `encoder_model.onnx`, `decoder_model.onnx`, and `vocab.txt`.

### B. Local Web Server (PC / Testing)
Located in the root folder [my-speech-model/](file:///d:/my-speech-model):
* **Backend Web Server**:
  * [app.py](file:///d:/my-speech-model/app.py): FastAPI server that loads the `Sanji27/fountain` model using PyTorch, resamples uploads to 16kHz, handles microphone recordings, and serves the frontend.
* **Frontend Web Application**:
  * [static/index.html](file:///d:/my-speech-model/static/index.html): HTML skeleton for the drag-and-drop file upload, audio players, and recorder interface.
  * [static/app.js](file:///d:/my-speech-model/static/app.js): Audio recording downsampling logic (downsamples browser audio to 16kHz float PCM) and handles network requests to the local server.
  * [static/style.css](file:///d:/my-speech-model/static/style.css): Premium dark-mode glassmorphic stylesheets.

---

## 4. Stability and Optimization Features

To run this large model on mobile devices with limited resources, the app implements the following features:
1. **Asset Copying & Native Memory Mapping (mmap)**: Instead of loading model files directly into Java heap (which causes JVM `OutOfMemoryError`), the models are copied to internal files storage once and loaded via their paths. This allows ONNX Runtime's native C++ engine to load them directly, utilizing native virtual memory.
2. **Large Heap Request**: `largeHeap="true"` is declared to give the Java virtual machine extra headroom on the device.
3. **No Spectrogram Overhead**: Moonshine takes raw audio waveforms directly, avoiding the CPU-heavy Mel-spectrogram processing step on the phone.
