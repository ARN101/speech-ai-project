// DOM Elements
const tabBtns = document.querySelectorAll('.tab-btn');
const tabContents = {
    mic: document.getElementById('tab-mic-content'),
    upload: document.getElementById('tab-upload-content')
};

const btnRecord = document.getElementById('btn-record');
const btnStop = document.getElementById('btn-stop');
const recordingTimer = document.getElementById('recording-timer');
const waveCanvas = document.getElementById('wave-canvas');

const dropzone = document.getElementById('dropzone');
const fileInput = document.getElementById('file-input');

const playerContainer = document.getElementById('player-container');
const playerFilename = document.getElementById('player-filename');
const audioPreview = document.getElementById('audio-preview');
const btnClearAudio = document.getElementById('btn-clear-audio');
const btnDownloadAudio = document.getElementById('btn-download-audio');

const btnTranscribe = document.getElementById('btn-transcribe');
const transcribeBtnText = document.getElementById('transcribe-btn-text');
const spinnerIcon = document.getElementById('spinner-icon');
const transcribeIcon = document.getElementById('transcribe-icon');

const resultPanel = document.getElementById('result-panel');
const transcriptionText = document.getElementById('transcription-text');
const btnCopy = document.getElementById('btn-copy');
const metaWords = document.getElementById('meta-words');
const metaChars = document.getElementById('meta-chars');
const toastContainer = document.getElementById('toast-container');

// App State
let activeTab = 'mic';
let selectedAudioBlob = null;
let selectedAudioName = '';
let isRecording = false;

// Audio Recording State
let audioContext = null;
let micStream = null;
let processorNode = null;
let analyserNode = null;
let sourceNode = null;
let audioBuffers = [];
let recordingStartTime = 0;
let timerInterval = null;
let animationFrameId = null;

// Canvas setup
const canvasCtx = waveCanvas.getContext('2d');
resizeCanvas();
window.addEventListener('resize', resizeCanvas);

function resizeCanvas() {
    waveCanvas.width = waveCanvas.parentElement.clientWidth;
    waveCanvas.height = waveCanvas.parentElement.clientHeight;
}

// -------------------------------------------------------------
// Toast Notifications
// -------------------------------------------------------------
function showToast(message, type = 'success') {
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerHTML = `
        <span>${message}</span>
    `;
    toastContainer.appendChild(toast);
    
    // Auto-remove after 4 seconds
    setTimeout(() => {
        toast.style.animation = 'toast-in 0.3s ease reverse forwards';
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

// -------------------------------------------------------------
// Tab Switching Navigation
// -------------------------------------------------------------
tabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
        const tab = btn.dataset.tab;
        if (tab === activeTab) return;
        
        tabBtns.forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        
        if (tab === 'mic') {
            tabContents.mic.style.display = 'block';
            tabContents.upload.style.display = 'none';
        } else {
            tabContents.mic.style.display = 'none';
            tabContents.upload.style.display = 'block';
        }
        
        activeTab = tab;
        clearAudioState();
    });
});

// -------------------------------------------------------------
// Drag & Drop File Upload
// -------------------------------------------------------------
dropzone.addEventListener('click', () => fileInput.click());

fileInput.addEventListener('change', (e) => {
    if (e.target.files.length > 0) {
        handleSelectedFile(e.target.files[0]);
    }
});

dropzone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropzone.classList.add('dragover');
});

dropzone.addEventListener('dragleave', () => {
    dropzone.classList.remove('dragover');
});

dropzone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropzone.classList.remove('dragover');
    if (e.dataTransfer.files.length > 0) {
        handleSelectedFile(e.dataTransfer.files[0]);
    }
});

function handleSelectedFile(file) {
    // Validate file type
    if (!file.type.startsWith('audio/')) {
        showToast('অনুগ্রহ করে একটি সঠিক অডিও ফাইল আপলোড করুন।', 'error');
        return;
    }
    
    selectedAudioBlob = file;
    selectedAudioName = file.name;
    
    // Update UI
    playerFilename.textContent = `ফাইল: ${file.name} (${(file.size / (1024 * 1024)).toFixed(2)} MB)`;
    audioPreview.src = URL.createObjectURL(file);
    playerContainer.style.display = 'block';
    
    if (btnDownloadAudio) {
        btnDownloadAudio.style.display = 'none';
    }
    
    btnTranscribe.disabled = false;
    showToast('অডিও ফাইল সফলভাবে যুক্ত হয়েছে।');
}

// -------------------------------------------------------------
// Audio Clearing Utility
// -------------------------------------------------------------
btnClearAudio.addEventListener('click', clearAudioState);

function clearAudioState() {
    if (isRecording) {
        stopRecording();
    }
    
    // Revoke previous URL if any
    if (audioPreview.src && audioPreview.src.startsWith('blob:')) {
        URL.revokeObjectURL(audioPreview.src);
    }
    
    selectedAudioBlob = null;
    selectedAudioName = '';
    audioPreview.src = '';
    playerContainer.style.display = 'none';
    btnTranscribe.disabled = true;
    
    if (btnDownloadAudio) {
        btnDownloadAudio.style.display = 'none';
        btnDownloadAudio.href = '#';
    }
    
    // Reset outputs
    transcriptionText.textContent = '';
    btnCopy.disabled = true;
    metaWords.textContent = 'শব্দ: ০';
    metaChars.textContent = 'অক্ষর: ০';
    
    fileInput.value = '';
}

// -------------------------------------------------------------
// Microphone Recording Logic
// -------------------------------------------------------------
btnRecord.addEventListener('click', startRecording);
btnStop.addEventListener('click', stopRecording);

async function startRecording() {
    if (isRecording) return;
    
    // Clear previous audio
    clearAudioState();
    
    audioBuffers = [];
    isRecording = true;
    
    btnRecord.disabled = true;
    btnStop.disabled = false;
    tabContents.mic.classList.add('recording');
    
    try {
        // Request microphone access
        micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
        
        // Setup Audio Context (using default browser sample rate for maximum device compatibility)
        audioContext = new (window.AudioContext || window.webkitAudioContext)();
        
        sourceNode = audioContext.createMediaStreamSource(micStream);
        
        // Setup Analyser for visualizer
        analyserNode = audioContext.createAnalyser();
        analyserNode.fftSize = 256;
        sourceNode.connect(analyserNode);
        
        // Setup ScriptProcessor Node (mono channel)
        processorNode = audioContext.createScriptProcessor(4096, 1, 1);
        processorNode.onaudioprocess = (e) => {
            if (!isRecording) return;
            const channelData = e.inputBuffer.getChannelData(0);
            // Copy data to buffer list
            audioBuffers.push(new Float32Array(channelData));
        };
        
        sourceNode.connect(processorNode);
        processorNode.connect(audioContext.destination);
        
        // Start Timer
        recordingStartTime = Date.now();
        updateTimer();
        timerInterval = setInterval(updateTimer, 1000);
        
        // Start Visualizer
        drawWaveform();
        
        showToast('রেকর্ডিং শুরু হয়েছে...');
    } catch (err) {
        console.error(err);
        showToast('মাইক্রোফোন অ্যাক্সেস করতে সমস্যা হয়েছে।', 'error');
        resetMicUI();
    }
}

function stopRecording() {
    if (!isRecording) return;
    isRecording = false;
    
    // Stop recording timer
    if (timerInterval) {
        clearInterval(timerInterval);
        timerInterval = null;
    }
    
    // Stop mic stream safely
    if (micStream) {
        try {
            micStream.getTracks().forEach(track => track.stop());
        } catch (e) {
            console.error("Error stopping mic tracks:", e);
        }
        micStream = null;
    }
    
    // Disconnect Web Audio nodes safely
    try {
        if (processorNode) {
            processorNode.disconnect();
            processorNode = null;
        }
    } catch (e) { console.error("Error disconnecting processorNode:", e); }
    
    try {
        if (sourceNode) {
            sourceNode.disconnect();
            sourceNode = null;
        }
    } catch (e) { console.error("Error disconnecting sourceNode:", e); }
    
    try {
        if (audioContext) {
            audioContext.close().catch(err => console.log("AudioContext close error:", err));
            audioContext = null;
        }
    } catch (e) {
        console.error("Error closing audioContext:", e);
    }
    
    if (animationFrameId) {
        cancelAnimationFrame(animationFrameId);
        animationFrameId = null;
    }
    
    // Process recorded audio buffers safely
    try {
        if (audioBuffers.length > 0) {
            // Flatten buffers
            let totalLength = 0;
            for (let i = 0; i < audioBuffers.length; i++) {
                totalLength += audioBuffers[i].length;
            }
            const flattened = new Float32Array(totalLength);
            let offset = 0;
            for (let i = 0; i < audioBuffers.length; i++) {
                flattened.set(audioBuffers[i], offset);
                offset += audioBuffers[i].length;
            }
            
            // Query original sample rate (default to 44100 if context is missing)
            const origSampleRate = audioContext ? audioContext.sampleRate : 44100;
            
            // Downsample Float32 samples from browser rate to 16000Hz manually
            const downsampled = downsampleBuffer(flattened, origSampleRate, 16000);
            
            // Encode Float32 samples to 16-bit PCM WAV
            selectedAudioBlob = encodeWAV(downsampled, 16000);
            selectedAudioName = `mic_recording_${Date.now()}.wav`;
            
            // Show preview & enable download
            const audioUrl = URL.createObjectURL(selectedAudioBlob);
            audioPreview.src = audioUrl;
            playerFilename.textContent = 'মাইক্রোফোন রেকর্ড অডিও (Recorded Audio)';
            playerContainer.style.display = 'block';
            
            if (btnDownloadAudio) {
                btnDownloadAudio.href = audioUrl;
                btnDownloadAudio.download = selectedAudioName;
                btnDownloadAudio.style.display = 'inline-flex';
            }
            
            btnTranscribe.disabled = false;
            showToast('রেকর্ডিং সম্পন্ন হয়েছে।');
        } else {
            showToast('কোন অডিও ডাটা রেকর্ড করা যায়নি।', 'error');
        }
    } catch (e) {
        console.error("Audio processing/encoding failed:", e);
        showToast('অডিও ফাইল প্রসেস করতে ব্যর্থ হয়েছে।', 'error');
    }
    
    resetMicUI();
}

function resetMicUI() {
    isRecording = false;
    btnRecord.disabled = false;
    btnStop.disabled = true;
    tabContents.mic.classList.remove('recording');
    recordingTimer.textContent = '00:00';
    
    // Draw horizontal line
    drawFlatLine();
}

function updateTimer() {
    const elapsed = Math.floor((Date.now() - recordingStartTime) / 1000);
    const m = String(Math.floor(elapsed / 60)).padStart(2, '0');
    const s = String(elapsed % 60).padStart(2, '0');
    recordingTimer.textContent = `${m}:${s}`;
}

// -------------------------------------------------------------
// Waveform Visualizer Drawing
// -------------------------------------------------------------
function drawFlatLine() {
    canvasCtx.fillStyle = '#0f111a';
    canvasCtx.fillRect(0, 0, waveCanvas.width, waveCanvas.height);
    
    canvasCtx.lineWidth = 2;
    canvasCtx.strokeStyle = 'rgba(139, 92, 246, 0.4)';
    canvasCtx.beginPath();
    canvasCtx.moveTo(0, waveCanvas.height / 2);
    canvasCtx.lineTo(waveCanvas.width, waveCanvas.height / 2);
    canvasCtx.stroke();
}

// Draw initial flat line
drawFlatLine();

function drawWaveform() {
    if (!isRecording) return;
    
    animationFrameId = requestAnimationFrame(drawWaveform);
    
    const bufferLength = analyserNode.frequencyBinCount;
    const dataArray = new Uint8Array(bufferLength);
    analyserNode.getByteTimeDomainData(dataArray);
    
    canvasCtx.fillStyle = '#0f111a';
    canvasCtx.fillRect(0, 0, waveCanvas.width, waveCanvas.height);
    
    // Draw neon oscilloscope wave
    canvasCtx.lineWidth = 3;
    
    // Dynamic gradient
    const gradient = canvasCtx.createLinearGradient(0, 0, waveCanvas.width, 0);
    gradient.addColorStop(0, '#ec4899');
    gradient.addColorStop(0.5, '#8b5cf6');
    gradient.addColorStop(1, '#3b82f6');
    
    canvasCtx.strokeStyle = gradient;
    canvasCtx.shadowBlur = 8;
    canvasCtx.shadowColor = 'rgba(139, 92, 246, 0.6)';
    
    canvasCtx.beginPath();
    
    const sliceWidth = waveCanvas.width / bufferLength;
    let x = 0;
    
    for (let i = 0; i < bufferLength; i++) {
        const v = dataArray[i] / 128.0;
        const y = v * waveCanvas.height / 2;
        
        if (i === 0) {
            canvasCtx.moveTo(x, y);
        } else {
            canvasCtx.lineTo(x, y);
        }
        
        x += sliceWidth;
    }
    
    canvasCtx.lineTo(waveCanvas.width, waveCanvas.height / 2);
    canvasCtx.stroke();
    
    // Reset shadow
    canvasCtx.shadowBlur = 0;
}

// -------------------------------------------------------------
// Audio Resampling / Downsampling Utility
// -------------------------------------------------------------
function downsampleBuffer(buffer, inputSampleRate, outputSampleRate) {
    if (inputSampleRate === outputSampleRate) {
        return buffer;
    }
    if (inputSampleRate < outputSampleRate) {
        console.warn("Input sample rate is lower than target. Skipping downsampling.");
        return buffer;
    }
    const sampleRateRatio = inputSampleRate / outputSampleRate;
    const newLength = Math.round(buffer.length / sampleRateRatio);
    const result = new Float32Array(newLength);
    let offsetResult = 0;
    let offsetBuffer = 0;
    while (offsetResult < result.length) {
        const nextOffsetBuffer = Math.round((offsetResult + 1) * sampleRateRatio);
        let accum = 0;
        let count = 0;
        for (let i = offsetBuffer; i < nextOffsetBuffer && i < buffer.length; i++) {
            accum += buffer[i];
            count++;
        }
        result[offsetResult] = count > 0 ? accum / count : 0;
        offsetResult++;
        offsetBuffer = nextOffsetBuffer;
    }
    return result;
}

// -------------------------------------------------------------
// WAV Encoding Utility
// -------------------------------------------------------------
function encodeWAV(samples, sampleRate) {
    // Convert float samples to 16-bit PCM ArrayBuffer
    const buffer = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(buffer);
    
    /* RIFF identifier */
    writeString(view, 0, 'RIFF');
    /* file length */
    view.setUint32(4, 36 + samples.length * 2, true);
    /* RIFF type */
    writeString(view, 8, 'WAVE');
    /* format chunk identifier */
    writeString(view, 12, 'fmt ');
    /* format chunk length */
    view.setUint32(16, 16, true);
    /* sample format (raw PCM) */
    view.setUint16(20, 1, true);
    /* channel count (mono) */
    view.setUint16(22, 1, true);
    /* sample rate */
    view.setUint32(24, sampleRate, true);
    /* byte rate (sample rate * block align) */
    view.setUint32(28, sampleRate * 2, true);
    /* block align (channel count * bytes per sample) */
    view.setUint16(32, 2, true);
    /* bits per sample (16-bit) */
    view.setUint16(34, 16, true);
    /* data chunk identifier */
    writeString(view, 36, 'data');
    /* chunk length */
    view.setUint32(40, samples.length * 2, true);
    
    // Write PCM samples
    floatTo16BitPCM(view, 44, samples);
    
    return new Blob([view], { type: 'audio/wav' });
}

function floatTo16BitPCM(output, offset, input) {
    for (let i = 0; i < input.length; i++, offset += 2) {
        let s = Math.max(-1, Math.min(1, input[i]));
        output.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
    }
}

function writeString(view, offset, string) {
    for (let i = 0; i < string.length; i++) {
        view.setUint8(offset + i, string.charCodeAt(i));
    }
}

// -------------------------------------------------------------
// Send Audio to API for ASR Transcription
// -------------------------------------------------------------
btnTranscribe.addEventListener('click', async () => {
    if (!selectedAudioBlob) return;
    
    // Set UI to loading state
    setLoadingState(true);
    
    const formData = new FormData();
    formData.append('file', selectedAudioBlob, selectedAudioName);
    
    try {
        const response = await fetch('/api/transcribe', {
            method: 'POST',
            body: formData
        });
        
        const result = await response.json();
        
        if (result.success) {
            const text = result.transcription.trim();
            
            if (!text) {
                if (result.warning === 'low_volume') {
                    transcriptionText.innerHTML = `<span style="color: #f59e0b; font-style: italic;">অডিওটির ভলিউম অত্যন্ত কম ছিল (Original RMS: ${result.rms.toFixed(5)})। কোনো বাংলা কথা সনাক্ত করা যায়নি। অনুগ্রহ করে আরও স্পষ্টভাবে ও জোরে রেকর্ড করা অডিও আপলোড করার চেষ্টা করুন।</span>`;
                    showToast('অডিওর সাউন্ড অনেক কম ছিল!', 'error');
                } else {
                    transcriptionText.innerHTML = `<span style="color: #9ca3af; font-style: italic;">কোনো বাংলা কথা সনাক্ত করা যায়নি। অনুগ্রহ করে অডিওটি পরীক্ষা করে আবার চেষ্টা করুন।</span>`;
                    showToast('কোনো বাংলা কথা সনাক্ত করা যায়নি।', 'error');
                }
                btnCopy.disabled = true;
            } else {
                transcriptionText.textContent = result.transcription;
                
                // Calculate word/character counts
                const wordsCount = text.split(/\s+/).length;
                const charsCount = text.length;
                
                metaWords.textContent = `শব্দ: ${wordsCount}`;
                metaChars.textContent = `অক্ষর: ${charsCount}`;
                
                btnCopy.disabled = false;
                showToast('অনুবাদ সফলভাবে সম্পন্ন হয়েছে!');
            }
            
            if (text && result.warning === 'low_volume') {
                showToast('সতর্কতা: অডিওর ভলিউম অনেক কম ছিল।', 'error');
            }
            
            // Smooth scroll to output
            resultPanel.scrollIntoView({ behavior: 'smooth' });
        } else {
            console.error(result.error);
            showToast(`ত্রুটি: ${result.error}`, 'error');
            transcriptionText.textContent = `অনুবাদ ব্যর্থ হয়েছে। ত্রুটি: ${result.error}`;
        }
    } catch (err) {
        console.error(err);
        showToast('সার্ভারের সাথে সংযোগ স্থাপন করা সম্ভব হয়নি।', 'error');
        transcriptionText.textContent = 'সার্ভারের সাথে যোগাযোগ করতে ত্রুটি হয়েছে। অনুগ্রহ করে সার্ভার চালু আছে কি না পরীক্ষা করুন।';
    } finally {
        setLoadingState(false);
    }
});

function setLoadingState(loading) {
    if (loading) {
        btnTranscribe.disabled = true;
        spinnerIcon.style.display = 'inline-block';
        transcribeIcon.style.display = 'none';
        transcribeBtnText.textContent = 'অনুবাদ হচ্ছে (Transcribing)...';
        
        // Reset output box during loading
        transcriptionText.textContent = '';
        btnCopy.disabled = true;
        metaWords.textContent = 'শব্দ: ০';
        metaChars.textContent = 'অক্ষর: ০';
    } else {
        btnTranscribe.disabled = false;
        spinnerIcon.style.display = 'none';
        transcribeIcon.style.display = 'inline-block';
        transcribeBtnText.textContent = 'অনুবাদ শুরু করুন (Transcribe)';
    }
}

// -------------------------------------------------------------
// Copy to Clipboard Logic
// -------------------------------------------------------------
btnCopy.addEventListener('click', () => {
    const text = transcriptionText.textContent;
    if (!text) return;
    
    navigator.clipboard.writeText(text).then(() => {
        showToast('টেক্সট সফলভাবে ক্লিপবোর্ডে কপি করা হয়েছে!');
    }).catch(err => {
        console.error('Could not copy text: ', err);
        showToast('কপি করতে ব্যর্থ হয়েছে।', 'error');
    });
});
