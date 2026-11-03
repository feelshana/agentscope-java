import { useEffect, useMemo, useState } from 'react';
import { getCompiledMdl } from '../../api/semanticModeling';
import type { CompiledMdl } from '../../api/semanticModeling';
import ReadableCode from '../ReadableCode';

type Section = { key: string; label: string; value: unknown };

/** Splits the compiled MDL into its models, views and relationships, each selectable alone. */
function sections(mdl: Record<string, unknown>): Section[] {
  const out: Section[] = [{ key: 'all', label: '全部', value: mdl }];
  const add = (kind: string, label: string) => {
    const list = mdl[kind];
    if (!Array.isArray(list)) return;
    for (const item of list as Record<string, unknown>[])
      out.push({ key: `${kind}:${String(item.name)}`, label: `${label}：${String(item.name)}`, value: item });
  };
  add('models', '模型');
  add('views', '查询模板');
  add('relationships', '关系');
  add('cubes', 'Cube');
  return out;
}

const time = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '';

/**
 * The official compiled MDL (target/mdl.json). The online one is exactly what the query assistant
 * reads as wren://mdl; the draft one is from the draft's last check, so it may lag behind edits.
 */
export default function CompiledMdlPanel({ groupId, version }: { groupId: string; version: 'draft' | 'published' }) {
  const [compiled, setCompiled] = useState<CompiledMdl | null>(null);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState('all');
  useEffect(() => {
    let cancelled = false;
    const refresh = () => void getCompiledMdl(groupId)
      .then(value => { if (!cancelled) { setCompiled(value); setError(''); } })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : '读取失败'); });
    refresh();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) refresh();
    };
    window.addEventListener('modeling:updated', update);
    return () => { cancelled = true; window.removeEventListener('modeling:updated', update); };
  }, [groupId]);
  const text = version === 'published' ? compiled?.published : compiled?.draft;
  const builtAt = version === 'published' ? compiled?.publishedBuiltAt : compiled?.draftBuiltAt;
  const parsed = useMemo(() => {
    if (!text) return null;
    try { return JSON.parse(text) as Record<string, unknown>; } catch { return null; }
  }, [text]);
  const list = useMemo(() => parsed ? sections(parsed) : [], [parsed]);
  const current = list.find(s => s.key === selected) ?? list[0];
  return <details className="da-card sm-compiled">
    <summary><strong>编译后的 MDL</strong>
      <span className="da-small">　Wren 把工程 YAML 编译成的语义模型{version === 'published' ? '，问数读到的就是这一份（wren://mdl）' : ''}</span></summary>
    {error && <div role="alert" className="sm-issue">{error}</div>}
    {!text ? <div className="da-small">{version === 'published' ? '还没有发布过。' : '草稿还没有编译过，让助手校验一次或发布后即可看到。'}</div> : <>
      <div className="da-small">
        {version === 'published' ? '线上版本' : '草稿最近一次编译'}，生成于 {time(builtAt ?? null)}。
        {version === 'draft' && '之后的修改要再次校验或发布才会反映在这里。'}
        这里只读；要改内容，请在建模对话里说明，或在公共视图页编辑对应视图。
      </div>
      {parsed ? <>
        <select className="da-input" aria-label="查看部分" value={current?.key ?? 'all'} onChange={e => setSelected(e.target.value)} style={{ maxWidth: 360, margin: '8px 0' }}>
          {list.map(s => <option key={s.key} value={s.key}>{s.label}</option>)}
        </select>
        {current && <ReadableCode text={JSON.stringify(current.value, null, 2)} language="json" title={current.label} />}
      </> : <ReadableCode text={text} language="json" title="编译结果" />}
    </>}
  </details>;
}
