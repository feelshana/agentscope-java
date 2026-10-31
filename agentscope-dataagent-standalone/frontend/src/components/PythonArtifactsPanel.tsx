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
        title="在右侧栏预览或下载分析文件"
        aria-label="查看分析文件"
        disabled={!onInspect}
      >
        <span style={{ color: 'var(--da-text-muted)', display: 'inline-flex' }}>
          <Icon name="file" size="sm" />
        </span>
        <span className="da-toolcall-name">分析文件（{allArtifacts.length} 个文件）</span>
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
