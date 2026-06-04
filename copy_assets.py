import os
import shutil

dest_dir = r"d:\my-speech-model\SpeechAIApp\app\src\main\assets"
os.makedirs(dest_dir, exist_ok=True)

shutil.copy(r"onnx_model\vocab.txt", os.path.join(dest_dir, "vocab.txt"))
shutil.copy(r"onnx_model\encoder_model.onnx", os.path.join(dest_dir, "encoder_model.onnx"))
shutil.copy(r"onnx_model\decoder_model.onnx", os.path.join(dest_dir, "decoder_model.onnx"))

print("Copied model and vocab files to assets!")
