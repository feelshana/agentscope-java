import React, { useEffect, useMemo, useRef, useState } from 'react';
import embed, { VisualizationSpec } from 'vega-embed';
import { VegaSpec } from '../utils/charts';

interface Props {
  spec: VegaSpec;
}

const s: Record<string, React.CSSProperties> = {
  wrapper: {
    background: '#ffffff',
    border: '1px solid #e2e8f0',
    borderRadius: 9,
    margin: '0.5rem 0',
    overflow: 'hidden',
    fontSize: '0.9rem',
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '0.6rem 0.9rem',
    background: '#f0fdf4',
    borderBottom: '1px solid #e2e8f0',
    color: '#065f46',
    fontWeight: 600,
    fontSize: '0.85rem',
  },
  canvas: { padding: '0.75rem', overflowX: 'auto', width: '100%', minWidth: 320 },
  error: {
    padding: '0.85rem 1rem',
    color: '#b91c1c',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.85rem',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
  },
};

/**
 * Renders a Vega-Lite spec inline in the chat stream. The spec arrives with the
 * `tool_call` event (client-side rendering is the intended architecture for
 * `render_chart`), so the chart shows up while the tool is still running.
 */
export default function ChartBlock({ spec }: Props) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Serialize so equal-but-new spec objects (extracted on every parent render)
  // do not re-run the embed effect and flash the chart.
  const specJson = useMemo(() => JSON.stringify(spec), [spec]);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    let active = true;
    let instance: { finalize: () => void } | null = null;
    setError(null);
    const spec = JSON.parse(specJson) as Record<string, unknown>;
    // Specs that omit width/height render at Vega-Lite's tiny defaults —
    // give them an explicit size so the chart is always visible. `container`
    // makes the chart track the chat bubble width.
    if (typeof spec.width === 'undefined') {
      spec.width = 'container';
    }
    if (typeof spec.height === 'undefined') {
      spec.height = 300;
    }
    embed(el, spec as VisualizationSpec, {
      actions: false,
      renderer: 'svg',
    })
      .then(result => {
        if (active) {
          instance = result;
        } else {
          result.finalize();
        }
      })
      .catch(err => {
        if (active) {
          setError(err instanceof Error ? err.message : String(err));
        }
      });
    return () => {
      active = false;
      instance?.finalize();
    };
  }, [specJson]);

  return (
    <div style={s.wrapper}>
      <div style={s.header}>Chart: Vega-Lite</div>
      {error ? (
        <div style={s.error}>{`chart render failed: ${error}`}</div>
      ) : (
        <div style={s.canvas} ref={ref} />
      )}
    </div>
  );
}
