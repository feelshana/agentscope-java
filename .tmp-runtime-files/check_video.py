from moviepy import VideoFileClip
v = VideoFileClip('/workspace/runpython/main-07596376-4fb3-4008-b3ca-1349ee0ce3ff/video/report.mp4')
print(f'duration={v.duration}s, size={v.size}, fps={v.fps}')
v.close()
