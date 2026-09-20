import subprocess, json

mp4 = '/workspace/runpython/main-07596376-4fb3-4008-b3ca-1349ee0ce3ff/video/report.mp4'

# Check video stream details
out = subprocess.check_output([
    'ffprobe', '-v', 'error',
    '-show_entries', 'stream=codec_name,profile,level,codec_tag_string,pix_fmt,width,height,r_frame_rate,avg_frame_rate,nb_frames,duration,bit_rate',
    '-show_entries', 'format=duration,size,bit_rate',
    '-of', 'json', mp4
])
print(json.dumps(json.loads(out), indent=2))

# Check moov atom position (faststart)
print("\n=== MP4 atom structure ===")
with open(mp4, 'rb') as f:
    pos = 0
    while pos < 1000:
        f.seek(pos)
        data = f.read(8)
        if len(data) < 8:
            break
        import struct
        size = struct.unpack('>I', data[:4])[0]
        atom = data[4:8].decode('ascii', errors='replace')
        print(f"  offset={pos}: atom='{atom}' size={size}")
        if atom == 'moov':
            print(f"  --> moov at offset {pos} (faststart={'YES' if pos < 1000 else 'NO'})")
            break
        if size == 0:
            break
        pos += size
