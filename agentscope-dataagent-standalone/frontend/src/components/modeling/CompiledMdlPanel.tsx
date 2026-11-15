import { useEffect, useMemo, useState } from 'react';
import { getCompiledMdl } from '../../api/semanticModeling';
import type { CompiledMdl } from '../../api/semanticModeling';
import ReadableCode from '../ReadableCode';
import Icon from '../Icon';

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
  const [loading, setLoading] = useState(true);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let cancelled = false;
    let generation = 0;
    setCompiled(null); setSelected('all');
    const refresh = () => {
      const current = ++generation;
      setLoading(true); setError('');
      void getCompiledMdl(groupId)
        .then(value => { if (!cancelled && current === generation) setCompiled(value); })
        .catch(e => { if (!cancelled && current === generation) setError(e instanceof Error ? e.message : '读取失败'); })
        .finally(() => { if (!cancelled && current === generation) setLoading(false); });
    };
    refresh();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) refresh();
    };
    window.addEventListener('modeling:updated', update);
    return () => { cancelled = true; window.removeEventListener('modeling:updated', update); };
  }, [groupId, retry]);
  const text = version === 'published' ? compiled?.published : compiled?.draft;
  const builtAt = version === 'published' ? compiled?.publishedBuiltAt : compiled?.draftBuiltAt;
  const parsed = useMemo(() => {
    if (!text) return null;
    try { return JSON.parse(text) as Record<string, unknown>; } catch { return null; }
  }, [text]);
  const list = useMemo(() => parsed ? sections(parsed) : [], [parsed]);
  const current = list.find(s => s.key === selected) ?? list[0];
  return <section className="am-section">
    <header className="am-section-head"><div><h3>编译后的 MDL</h3><p>Wren 把工程 YAML 编译成的语义模型{version === 'published' ? '，问数读到的就是这一份（wren://mdl）。' : '，展示草稿最近一次编译结果。'}</p></div><span className="am-source">{version === 'published' ? '线上版本' : '当前草稿'}</span></header>
    {loading ? <p className="am-empty" role="status">正在读取编译结果…</p> : error ? <div role="alert" className="am-error"><span>{error}</span><button type="button" className="da-btn" onClick={() => setRetry(n => n + 1)}>重试</button></div> : !text ? <p className="am-empty">{version === 'published' ? '还没有发布过。' : '草稿还没有编译过，让助手校验一次或发布后即可看到。'}</p> : <>
      <div className="am-notice"><Icon name="info" size="sm" /><span>{builtAt && <>生成于 {time(builtAt)}。 </>}{version === 'draft' && '之后的修改要再次校验或发布才会反映在这里。'}这里只读；要改内容，请在建模对话里说明，或在公共视图页编辑对应视图。</span></div>
      {parsed ? <>
        <label className="am-compiled-select">查看部分<select className="da-input" aria-label="查看部分" value={current?.key ?? 'all'} onChange={e => setSelected(e.target.value)}>
          {list.map(s => <option key={s.key} value={s.key}>{s.label}</option>)}
        </select></label>
        {current && <ReadableCode text={JSON.stringify(current.value, null, 2)} language="json" title={current.label} />}
      </> : <ReadableCode text={text} language="json" title="编译结果" />}
    </>}
  </section>;
}
