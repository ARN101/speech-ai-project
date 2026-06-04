import os
import tempfile
import io
import torch
import soundfile as sf
import librosa
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.staticfiles import StaticFiles
from fastapi.responses import JSONResponse, FileResponse
from transformers import AutoProcessor, MoonshineForConditionalGeneration, Wav2Vec2Processor, AutoFeatureExtractor, AutoTokenizer

app = FastAPI(title="ASR Model Runner for Bangla Moonshine")

# Define paths
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "static")

# Ensure static directory exists
os.makedirs(STATIC_DIR, exist_ok=True)

# Load ASR model and processor
MODEL_ID = "Sanji27/fountain"
PROCESSOR_ID = "UsefulSensors/moonshine-base"
print(f"Loading processor and model '{MODEL_ID}'... (this might take a few moments on the first run)")

try:
    feature_extractor = AutoFeatureExtractor.from_pretrained(PROCESSOR_ID)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
    processor = Wav2Vec2Processor(feature_extractor=feature_extractor, tokenizer=tokenizer)
    
    model = MoonshineForConditionalGeneration.from_pretrained(MODEL_ID)
    
    # Check GPU availability
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model.to(device)
    print(f"Model successfully loaded on device: {device}")
except Exception as e:
    print(f"Error loading model: {e}")
    model = None
    processor = None

@app.post("/api/transcribe")
async def transcribe(file: UploadFile = File(...)):
    if model is None or processor is None:
        raise HTTPException(status_code=500, detail="ASR Model is not loaded on the server.")
    
    # Validate file type
    filename = file.filename.lower()
    if not (filename.endswith('.wav') or filename.endswith('.mp3') or filename.endswith('.m4a') or filename.endswith('.webm') or filename.endswith('.ogg')):
        raise HTTPException(status_code=400, detail="Unsupported audio format. Please upload wav, mp3, m4a, webm, or ogg.")
    
    # Read the file into memory
    content = await file.read()
    
    # Save a debug copy in the workspace
    debug_path = os.path.join(BASE_DIR, "last_uploaded.wav")
    try:
        with open(debug_path, "wb") as f:
            f.write(content)
        print(f"DEBUG: Saved copy of uploaded file to {debug_path}")
    except Exception as debug_err:
        print(f"DEBUG: Failed to save copy of uploaded file: {debug_err}")
    
    # We save to a temporary file because soundfile/librosa might require a file path
    suffix = os.path.splitext(filename)[1]
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
        temp_file.write(content)
        temp_path = temp_file.name
        
    try:
        audio = None
        sr = 16000
        
        # Try loading with librosa (supports multiple formats if system has backend)
        try:
            audio, sr = librosa.load(temp_path, sr=16000, mono=True)
        except Exception as librosa_err:
            print(f"Librosa loading failed: {librosa_err}. Trying soundfile fallback...")
            # Fallback to soundfile directly (natively supports WAV with zero external tools)
            try:
                audio, sr = sf.read(temp_path)
                # Convert multi-channel to mono
                if len(audio.shape) > 1:
                    audio = audio.mean(axis=1)
                # Resample manually to 16000Hz using librosa
                if sr != 16000:
                    audio = librosa.resample(audio, orig_sr=sr, target_sr=16000)
            except Exception as sf_err:
                print(f"Soundfile loading failed: {sf_err}")
                raise RuntimeError(
                    "অডিও ফাইলটি লোড করা যায়নি। আপনার কম্পিউটারে FFmpeg ইনস্টল না থাকলে শুধুমাত্র WAV ফরম্যাট সমর্থিত। "
                    "অনুগ্রহ করে একটি সঠিক WAV ফাইল আপলোড করার চেষ্টা করুন।"
                )
        
        # Calculate RMS before normalization to detect silent/very quiet files
        original_rms = 0.0
        if audio is not None and len(audio) > 0:
            import numpy as np
            original_rms = np.sqrt(np.mean(audio**2))
            
        # Peak normalization: scale audio to [-1.0, 1.0] for model robustness
        if audio is not None and len(audio) > 0:
            import numpy as np
            max_val = np.max(np.abs(audio))
            if max_val > 1e-5:
                audio = audio / max_val
                
        # Diagnostic prints
        print(f"DEBUG: Loaded file name: {filename}")
        if audio is not None:
            import numpy as np
            print(f"DEBUG: Audio shape: {audio.shape}, Sample rate: {sr}")
            print(f"DEBUG: Original RMS energy: {original_rms:.6f}")
            if original_rms < 0.01:
                print("DEBUG WARNING: Audio is extremely quiet. Model output may collapse or be empty.")
            print(f"DEBUG: Audio range (normalized): min={np.min(audio):.6f}, max={np.max(audio):.6f}, mean={np.mean(audio):.6f}")
            print(f"DEBUG: First 10 samples: {audio[:10]}")
        else:
            print("DEBUG: Audio array is None!")
            
        # Prepare inputs
        inputs = processor(audio, return_tensors="pt", sampling_rate=16000)
        
        # Move all input tensors to the same device as the model (including input_values and attention_mask)
        inputs = {k: v.to(model.device) for k, v in inputs.items()}
        
        # Perform inference with custom token IDs matching the fine-tuned Bangla BERT tokenizer vocab
        with torch.no_grad():
            generated_ids = model.generate(
                **inputs,
                max_new_tokens=256,
                decoder_start_token_id=2,
                bos_token_id=2,
                eos_token_id=3,
                pad_token_id=0
            )
            
        # Diagnostic prints for tokens
        print(f"DEBUG: Generated token IDs shape: {generated_ids.shape}")
        print(f"DEBUG: Generated token IDs: {generated_ids[0].tolist()}")
            
        # Decode transcription
        transcription = processor.batch_decode(generated_ids, skip_special_tokens=True)[0]
        try:
            print(f"DEBUG: Successfully transcribed audio. Result: {transcription}")
        except Exception:
            # Fallback if Windows console doesn't support the characters
            try:
                print(f"DEBUG: Successfully transcribed audio (fallback encoding): {transcription.encode('utf-8', errors='replace')}")
            except Exception:
                print("DEBUG: Successfully transcribed audio, but output could not be printed to console due to encoding restrictions.")
        
        # Return UTF-8 response directly
        response_data = {
            "success": True,
            "transcription": transcription,
            "rms": float(original_rms),
            "warning": "low_volume" if original_rms < 0.01 else None
        }
        return JSONResponse(
            content=response_data,
            headers={"Content-Type": "application/json; charset=utf-8"}
        )
        
    except Exception as e:
        print(f"Transcription error: {e}")
        return JSONResponse(
            status_code=500,
            content={"success": False, "error": str(e)},
            headers={"Content-Type": "application/json; charset=utf-8"}
        )
    finally:
        # Clean up temp file
        if os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except Exception as cleanup_err:
                print(f"Failed to remove temp file {temp_path}: {cleanup_err}")

# Serve frontend static files
# Place index.html at root path
@app.get("/")
async def read_index():
    index_path = os.path.join(STATIC_DIR, "index.html")
    if os.path.exists(index_path):
        return FileResponse(index_path)
    return {"message": "Server is running. Please create static/index.html to view the UI."}

# Mount the static files directory for styles, scripts, etc.
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
