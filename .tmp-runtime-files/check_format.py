import struct

path = '/workspace/runpython/main-07596376-4fb3-4008-b3ca-1349ee0ce3ff/video/narration.wav'
with open(path, 'rb') as f:
    header = f.read(16)
    print(f"First 16 bytes: {header}")
    print(f"First 4 bytes (magic): {header[:4]}")
    
    if header[:4] == b'RIFF':
        size = struct.unpack('<I', header[4:8])[0]
        fmt = header[8:12]
        wave_fmt = header[12:16]
        print(f"RIFF size: {size}")
        print(f"Format: {fmt}")
        print(f"Sub-chunk: {wave_fmt}")
        # Read more for WAV header
        f.seek(0)
        data = f.read(44)
        channels = struct.unpack('<H', data[22:24])[0]
        rate = struct.unpack('<I', data[24:28])[0]
        bits = struct.unpack('<H', data[34:36])[0]
        print(f"WAV: channels={channels}, rate={rate}, bits={bits}")
    elif header[:3] == b'ID3' or header[:2] == b'\xff\xfb':
        print("This is an MP3 file!")
    elif header[:4] == b'\x1a\x45\xdf\xa3':
        print("This is a WebM/MKA file!")
    else:
        print(f"Unknown format. Hex: {header.hex()}")
    
    import os
    fsize = os.path.getsize(path)
    print(f"File size: {fsize} bytes")
