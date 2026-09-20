import React from 'react';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { getToken } from '../api/auth';

/** Rewrite /workspace/… src to the workspace binary API URL (with auth token). */
function rewriteVideoSrc(src: string): string {
  if (src.startsWith('/workspace/')) {
    const base = `/api/agents/${ACTIVE_AGENT_ID}/workspace/file/binary?path=${encodeURIComponent(src)}`;
    const token = getToken();
    return token ? `${base}&token=${encodeURIComponent(token)}` : base;
  }
  return src;
}

interface VideoPlayerBlockProps {
  /** Sandbox path to the video file (e.g. /workspace/runpython/xxx/video/report.mp4). */
  src: string;
  /** Optional title displayed above the player. */
  title?: string;
}

const S: Record<string, React.CSSProperties> = {
  wrap: {
    margin: '12px 0',
    borderRadius: 10,
    overflow: 'hidden',
    border: '1px solid var(--da-border)',
    background: '#000',
  },
  title: {
    padding: '8px 12px',
    fontSize: '0.85rem',
    color: 'var(--da-text-muted)',
    background: 'var(--da-surface-sunken)',
    display: 'flex',
    alignItems: 'center',
    gap: 6,
  },
  video: {
    width: '100%',
    maxHeight: 480,
    display: 'block',
  },
};

/**
 * Renders an HTML5 video player for sandbox-generated MP4 files.
 *
 * The sandbox path is rewritten to the workspace binary API so the browser
 * can stream the video through the authenticated backend.
 */
export default function VideoPlayerBlock({ src, title }: VideoPlayerBlockProps) {
  const url = rewriteVideoSrc(src);

  return (
    <div style={S.wrap}>
      {title && (
        <div style={S.title}>
          <span role="img" aria-label="video">🎬</span> {title}
        </div>
      )}
      <video
        style={S.video}
        src={url}
        controls
        preload="metadata"
        playsInline
      >
        您的浏览器不支持视频播放
      </video>
    </div>
  );
}
