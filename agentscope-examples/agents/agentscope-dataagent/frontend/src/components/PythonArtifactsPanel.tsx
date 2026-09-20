import React from 'react';
import Icon from './Icon';
import { parseResult, ArtifactInfo, deduplicateArtifacts } from './PythonCodeBlock';

interface ToolEntry {
  name: string;
  input?: string;
  result?: string;
}

interface Props {
  tools: ToolEntry[];
  onInspect?: (t: { id: string; name: string; pythonTools: ToolEntry[] }) => void;
}

export default function PythonArtifactsPanel({ tools, onInspect }: Props) {
  const { allArtifacts, pythonTools } = React.useMemo(() => {
    const raw: ArtifactInfo[] = [];
    const pyTools: ToolEntry[] = [];
    for (const t of tools) {
      if (t.name !== 'run_python' || !t.result) continue;
      pyTools.push(t);
      try {
        const parsed = parseResult(t.result);
        raw.push(...parsed.artifacts);
      } catch (e) {
        console.warn('[PythonArtifactsPanel] parseResult threw:', e, 'result preview:', t.result.substring(0, 500));
      }
    }
    return { allArtifacts: deduplicateArtifacts(raw), pythonTools: pyTools };
  }, [tools]);

  if (allArtifacts.length === 0) return null;

  return (
    <div className="da-toolcall">
      <button
        type="button"
        className="da-toolcall-head"
        onClick={() => onInspect?.({ id: '__python_artifacts__', name: 'run_python', pythonTools })}
        title="在侧栏查看并下载产物"
        aria-label="查看 Python 生成产物"
      >
        <span style={{ color: 'var(--da-text-muted)', display: 'inline-flex' }}>
          <Icon name="file" size="sm" />
        </span>
        <span className="da-toolcall-name">生成产物（{allArtifacts.length} 个文件）</span>
        <span className="da-toolcall-status">
          <Icon name="check" size="sm" />
        </span>
        <span className="da-trace-chevron">
          <Icon name="chevron" size="sm" />
        </span>
      </button>
    </div>
  );
}
