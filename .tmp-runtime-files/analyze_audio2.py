import subprocess, struct, os

path = '/workspace/runpython/main-07596376-4fb3-4008-b3ca-1349ee0ce3ff/video/narration.wav'
fsize = os.path.getsize(path)

# Use ffmpeg silencedetect to find silence periods
out = subprocess.check_output([
    'ffmpeg', '-i', path, '-af',
    'silencedetect=noise=-25dB:d=2',
    '-f', 'null', '-'
], stderr=subprocess.STDOUT).decode()

print("=== Silence detection (>= 2s at -25dB) ===")
for line in out.split('\n'):
    if 'silence' in line.lower():
        print(line.strip())

# Also check where speech ends by analyzing in 5s windows
print("\n=== Amplitude analysis (5s windows, first 60s + last 30s) ===")
out2 = subprocess.check_output([
    'ffmpeg', '-i', path, '-af',
    'astats=metadata=1:reset=80000,ametadata=print:key=lavfi.astats.Overall.RMS_level',
    '-f', 'null', '-'
], stderr=subprocess.STDOUT).decode()

# Simpler: just decode and check RMS per second
import wave, io

# Decode MP3 to raw PCM first
pcm = subprocess.check_output([
    'ffmpeg', '-i', path, '-f', 's16le', '-acodec', 'pcm_s16le',
    '-ar', '16000', '-ac', '1', '-'
])

total_samples = len(pcm) // 2
rate = 16000
duration = total_samples / rate
print(f"Decoded PCM: {len(pcm)} bytes, {total_samples} samples, {duration:.1f}s")

samples = struct.unpack(f'{total_samples}h', pcm)

# Analyze per-second RMS
print("\nPer-second RMS (first 40s):")
for sec in range(min(40, int(duration))):
    start = sec * rate
    end = min(start + rate, total_samples)
    chunk = samples[start:end]
    rms = (sum(s*s for s in chunk) / len(chunk)) ** 0.5
    marker = ' <-- LOW' if rms < 50 else ''
    print(f"  {sec:3d}s: RMS={rms:8.1f}{marker}")

print(f"\nPer-second RMS (last 30s, from {max(0,int(duration)-30)}s):")
for sec in range(max(0, int(duration)-30), int(duration)):
    start = sec * rate
    end = min(start + rate, total_samples)
    chunk = samples[start:end]
    rms = (sum(s*s for s in chunk) / len(chunk)) ** 0.5
    marker = ' <-- LOW' if rms < 50 else ''
    print(f"  {sec:3d}s: RMS={rms:8.1f}{marker}")
