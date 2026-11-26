import { useMemo, useState } from 'react';
import type { MdlPreview } from '../../api/semanticModeling';
import Icon from '../Icon';
import EngineeringDirectory from './EngineeringDirectory';
import type { EngineeringVersion } from './EngineeringDialog';

const sectionLabels: Record<string, string> = {
  'Default filters': '默认过滤', 'Naming conventions': '术语', 'External identifiers': '外部标识',
  Currency: '币种', 'Canonical tables': '指定取数表', Metrics: '指标',
};
const isRule = (file: { path: string }) => /^knowledge\/rules\/.+\.md$/.test(file.path);

export default function EngineeringRules({ preview, version }: { preview: MdlPreview | null; version: EngineeringVersion }) {
  const [selected, setSelected] = useState('');
  const [copyStatus, setCopyStatus] = useState('');
  const files = (version === 'draft' ? preview?.files : preview?.publishedFiles)?.filter(isRule) ?? [];
  const file = files.find(candidate => candidate.path === selected) ?? files[0];
  const online = useMemo(() => new Set(preview?.publishedFiles.filter(isRule).flatMap(f => f.content.split('\n').map(line => line.trim()))), [preview?.publishedFiles]);
  if (!file) return <p className="am-empty" role="status">{preview ? '当前版本暂无业务规则。' : '正在读取业务规则…'}</p>;
  return <div className="eng-browser">
    <EngineeringDirectory paths={files.map(candidate => candidate.path)} current={file.path} onSelect={path => { setSelected(path); setCopyStatus(''); }} />
    <section className="eng-document">
      <div className="eng-filebar"><strong title={file.path}>{file.path}</strong><span className="da-small" aria-live="polite">{copyStatus}</span>
        <button type="button" className="da-btn da-btn-sm" onClick={() => void navigator.clipboard.writeText(file.content)
          .then(() => setCopyStatus('已复制'), () => setCopyStatus('复制失败'))}><Icon name="copy" size="sm" />复制内容</button>
      </div>
      <div key={file.path} className="eng-doc-text" tabIndex={0} aria-label="业务规则正文">
        {file.content.split('\n').map((line, index) => {
          const heading = /^##?\s+(.+)$/.exec(line.trim());
          if (heading) return <h3 key={index}>{sectionLabels[heading[1]] ?? heading[1]}</h3>;
          if (!line.trim()) return null;
          const changed = version === 'draft' && !!preview?.mdlVersion && !online.has(line.trim());
          return <p className={changed ? 'qc-rule-line qc-added' : undefined} key={index} title={changed ? '草稿新增或修改' : undefined}>{line}</p>;
        })}
      </div>
    </section>
  </div>;
}
