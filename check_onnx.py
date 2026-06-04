import onnxruntime as ort

print("--- ENCODER MODEL ---")
sess_enc = ort.InferenceSession("onnx_model/encoder_model.onnx")
for i in sess_enc.get_inputs():
    print(f"Input: name={i.name}, shape={i.shape}, type={i.type}")
for o in sess_enc.get_outputs():
    print(f"Output: name={o.name}, shape={o.shape}, type={o.type}")

print("\n--- DECODER MODEL ---")
sess_dec = ort.InferenceSession("onnx_model/decoder_model.onnx")
for i in sess_dec.get_inputs():
    print(f"Input: name={i.name}, shape={i.shape}, type={i.type}")
for o in sess_dec.get_outputs():
    print(f"Output: name={o.name}, shape={o.shape}, type={o.type}")
