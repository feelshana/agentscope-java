import subprocess, json

wav = '/workspace/runpython/main-07596376-4fb3-4008-b3ca-1349ee0ce3ff/video/narration.wav'

# Analyze audio loudness in 5-second windows
out = subprocess.check_output([
    'ffmpeg', '-i', wav, '-af',
    'silencedetect=noise=-30dB:d=1', 
    '-f', 'null', '-'
], stderr=subprocess.STDOUT).decode()

# Print silence detection results
for line in out.split('\n'):
    if 'silence' in line.lower():
        print(line.strip())

# Also check overall stats
out2 = subprocess.check_output([
    'ffmpeg', '-i', wav, '-af', 'volumedetect', '-f', 'null', '-'
], stderr=subprocess.STDOUT).decode()
for line in out2.split('\n'):
    if 'volume' in line.lower() or 'mean' in line.lower() or 'max' in line.lower():
        print(line.strip())
