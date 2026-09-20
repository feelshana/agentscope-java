import subprocess, struct, wave

wav = '/workspace/runpython/main-07596376-4fb3-4008-b3ca-1349ee0ce3ff/video/narration.wav'

# Read WAV and analyze amplitude in 1-second windows
with wave.open(wav, 'rb') as w:
    rate = w.getframerate()
    channels = w.getnchannels()
    sampwidth = w.getsampwidth()
    nframes = w.getnframes()
    print(f"WAV: rate={rate}, ch={channels}, sampwidth={sampwidth}, frames={nframes}, duration={nframes/rate:.1f}s")
    
    # Read all frames
    data = w.readframes(nframes)
    
# Analyze amplitude per second
fmt = {1: 'b', 2: 'h', 4: 'i'}[sampwidth]
window = rate * channels  # samples per second
print("\nAmplitude per second (RMS):")
for i in range(0, nframes, window):
    chunk = data[i*sampwidth*channels:(i+window)*sampwidth*channels]
    if len(chunk) < sampwidth:
        break
    samples = struct.unpack(f'{len(chunk)//sampwidth}{fmt}', chunk)
    rms = (sum(s*s for s in samples) / len(samples)) ** 0.5
    # Find first second with near-zero RMS (trailing silence)
    sec = i // window
    if sec < 30 or rms < 10:  # only print first 30s and any silence
        print(f"  {sec:3d}s: RMS={rms:8.1f} {'<-- SILENCE' if rms < 10 else ''}")
        if rms < 10 and sec > 5:
            print(f"  ... trailing silence starts at {sec}s")
            break
