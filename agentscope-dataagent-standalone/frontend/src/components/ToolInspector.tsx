import React, { lazy, Suspense, useEffect, useId, useMemo, useRef, useState } from 'react';
import Icon from './Icon';
import Markdown from './Markdown';
import ErrorNotice from './ErrorNotice';
import AttachmentImage from './AttachmentImage';
import { ArtifactInfo, artifactSrc, downloadArtifact, parseResult } from './PythonCodeBlock';
import { downloadFile } from '../api/artifacts';
import { toolIcon, type ToolInspectPayload } from './ToolCallBlock';
import { officialToolName, record, wrenToolLabel } from '../utils/wrenToolPresentation';
import { decodeToolValue, displayText, inputFields, inspectResult, tableData, valueText } from '../utils/toolInspectorPresentation';
import './ToolInspector.css';

const EChartsBlock = lazy(() => import('./EChartsBlock'));
export interface ToolInspectorProps { tool: ToolInspectPayload; onClose: () => void }
const TABS = [['result', '结果'], ['input', '输入'], ['raw', '原始内容']] as const;
type Tab = typeof TABS[number][0];
const LABELS: Record<string, string> = {
  run_python: '执行 Python', render_chart: '生成图表', web_search: '联网搜索', wren_cli: '官方 CLI',
  read_file: '读取文件', write_file: '写入文件', edit_file: '修改文件',
  delete_file: '撤回文件', list_files: '查看文件目录', load_skill_through_path: '读取技能',
};
const FIELDS: Record<string, string> = {
  name: '名称', description: '说明', type: '类型', models: '模型', views: '视图', cubes: '业务指标',
  columns: '字段', relationships: '关系', instructions: '业务规则', tools: '可用工具',
  query: '检索问题', question: '问题', nl: '问题', nl_query: '问题', sql_query: 'SQL',
  datasource: '数据源', base_object: '基础对象', measures: '度量', dimensions: '维度',
  time_dimensions: '时间维度', expression: '表达式', source: '来源', path: '文件路径',
};

function CopyButton({ text, label = '复制' }: { text: string; label?: string }) {
  const [status, setStatus] = useState('');
  useEffect(() => { if (!status) return; const timer = setTimeout(() => setStatus(''), 2500); return () => clearTimeout(timer); }, [status]);
  return <span className="da-ti-copy"><button type="button" disabled={!text} onClick={async () => {
    try { await navigator.clipboard.writeText(text); setStatus('已复制'); }
    catch { setStatus('复制失败，请选择原文复制'); }
  }}>{status === '已复制' ? status : label}</button>{status && status !== '已复制' && <small role="status">{status}</small>}</span>;
}

function Code({ text, label }: { text: string; label?: string }) {
  const [limit, setLimit] = useState(20000);
  return <><div className="da-ti-caption"><strong>{label}</strong><CopyButton text={text} label={label === 'SQL' ? '复制 SQL' : label ? `复制${label}` : '复制原文'} /></div>
    <pre className="da-ti-code"><code>{text.slice(0, limit)}</code></pre>
    {text.length > limit && <button className="da-ti-text-button" type="button" onClick={() => setLimit(n => n + 50000)}>展开更多原文（已显示 {limit.toLocaleString()} / {text.length.toLocaleString()} 字符）</button>}</>;
}

function ResultTable({ data, sourceLabel = '返回' }: { data: NonNullable<ReturnType<typeof tableData>>; sourceLabel?: string }) {
  const [limit, setLimit] = useState(50);
  const numeric = data.columns.map((_, i) => data.rows.some(row => typeof row[i] === 'number')
    && data.rows.every(row => row[i] == null || typeof row[i] === 'number'));
  return <><div className="da-ti-caption"><strong>{sourceLabel} {data.rows.length.toLocaleString()} 行 · {data.columns.length} 列</strong>
    {data.rows.length > limit && <small>当前显示前 {limit} 行</small>}</div>
    {data.truncated && <div className="da-ti-notice">接口标记结果已截断，这里仅展示已返回的数据，不能据此推断完整总量。</div>}
    {data.rows.length ? <div className="da-ti-table-scroll" role="region" aria-label="查询结果表格，可横向滚动" tabIndex={0}><table><thead><tr>{data.columns.map((c, i) => <th className={numeric[i] ? 'is-numeric' : undefined} key={i}>{c}</th>)}</tr></thead>
      <tbody>{data.rows.slice(0, limit).map((row, i) => <tr key={i}>{row.map((v, j) => <td className={numeric[j] ? 'is-numeric' : undefined} key={j}>{valueText(v)}</td>)}</tr>)}</tbody></table></div>
      : <div className="da-ti-empty"><Icon name="table" size="lg" /><h3>查询成功，未返回数据行</h3><p>不等同于业务指标为 0。可核对日期、筛选条件和数据覆盖范围。</p></div>}
    {data.rows.length > limit && <button type="button" className="da-ti-text-button" onClick={() => setLimit(n => n + 50)}>显示更多（仅展开本次返回）</button>}</>;
}

function Structured({ value, depth = 0 }: { value: unknown; depth?: number }) {
  if (typeof value === 'string') return value.length > 1000 ? <Code text={value} /> : <span className="da-ti-text">{value || '（空）'}</span>;
  if (depth > 3) return <Code text={displayText(value)} />;
  if (Array.isArray(value)) return value.length ? <div className="da-ti-items">{value.slice(0, 50).map((v, i) => <div key={i}><Structured value={v} depth={depth + 1} /></div>)}
    {value.length > 50 && <p className="da-ti-muted">其余 {value.length - 50} 项可在原始内容中查看。</p>}</div> : <p className="da-ti-muted">当前目录为空。</p>;
  if (record(value)) return <dl className="da-ti-fields">{Object.entries(value).slice(0, 50).map(([k, v]) => <div key={k}><dt>{FIELDS[k] ?? k}</dt><dd>
    {['sql', 'sql_query', 'expression', 'statement'].includes(k) && typeof v === 'string' ? <Code text={v} label="SQL" /> : <Structured value={v} depth={depth + 1} />}
  </dd></div>)}{Object.keys(value).length > 50 && <p>其余字段可在原始内容中查看。</p>}</dl>;
  return <span>{valueText(value)}</span>;
}

function ModelDirectory({ models }: { models: unknown[] }) {
  const [limit, setLimit] = useState(50);
  return <><div className="da-ti-caption"><strong>模型目录 · {models.length} 个</strong></div>
    {!models.length && <p className="da-ti-muted">当前没有返回可用模型。</p>}
    <div className="da-ti-model-list">{models.slice(0, limit).map((model, i) => record(model) ? <article key={i}>
      <div className="da-ti-model-title"><code>{valueText(model.name)}</code>{model.column_count != null && <span>{valueText(model.column_count)} 个字段</span>}</div>
      {model.description != null && <p>{valueText(model.description)}</p>}
      {Object.keys(model).some(k => !['name', 'description', 'column_count'].includes(k)) && <details className="da-ti-extra"><summary>其他属性</summary><Structured value={Object.fromEntries(Object.entries(model).filter(([k]) => !['name', 'description', 'column_count'].includes(k)))} /></details>}
    </article> : <Structured key={i} value={model} />)}</div>
    {models.length > limit && <button type="button" className="da-ti-text-button" onClick={() => setLimit(n => n + 50)}>显示更多模型</button>}</>;
}

function ModelSchema({ model }: { model: Record<string, unknown> }) {
  const columns = model.columns as unknown[];
  const [limit, setLimit] = useState(50);
  const extras = Object.fromEntries(Object.entries(model).filter(([k, v]) => !['name', 'description', 'columns', 'relationships'].includes(k) && v != null));
  return <><h3 className="da-ti-model-name">{valueText(model.name)}</h3>
    {model.description != null && <p className="da-ti-model-description">{valueText(model.description)}</p>}
    <div className="da-ti-caption"><strong>字段信息 · {columns.length} 个</strong>{columns.length > limit && <small>当前显示前 {limit} 个</small>}</div>
    {columns.length ? <div className="da-ti-table-scroll" role="region" aria-label="模型字段信息" tabIndex={0}><table className="da-ti-schema-table"><colgroup><col style={{ width: '31%' }} /><col style={{ width: '19%' }} /><col style={{ width: '50%' }} /></colgroup>
      <thead><tr><th>字段</th><th>类型</th><th>说明</th></tr></thead><tbody>{columns.slice(0, limit).map((column, i) => {
        if (!record(column)) return <tr key={i}><td colSpan={3}>{valueText(column)}</td></tr>;
        const extra = Object.fromEntries(Object.entries(column).filter(([k]) => !['name', 'type', 'sqlType', 'description'].includes(k)));
        return <tr key={i}><td><code>{valueText(column.name)}</code></td><td><code>{valueText(column.type ?? column.sqlType)}</code></td><td>{valueText(column.description)}
          {Object.keys(extra).length > 0 && <details className="da-ti-extra"><summary>更多属性</summary><Structured value={extra} /></details>}</td></tr>;
      })}</tbody></table></div> : <p className="da-ti-muted">当前没有返回字段。</p>}
    {columns.length > limit && <button type="button" className="da-ti-text-button" onClick={() => setLimit(n => n + 50)}>显示更多字段</button>}
    {Array.isArray(model.relationships) && model.relationships.length > 0 && <details className="da-ti-extra da-ti-model-extra"><summary>模型关系 · {model.relationships.length} 条</summary><Structured value={model.relationships} /></details>}
    {Object.keys(extras).length > 0 && <details className="da-ti-extra da-ti-model-extra"><summary>其他模型信息</summary><Structured value={extras} /></details>}</>;
}

function BusinessRules({ data }: { data: Record<string, unknown> & { instructions: string } }) {
  const [limit, setLimit] = useState(20000);
  const text = data.instructions;
  const extra = Object.fromEntries(Object.entries(data).filter(([key]) => key !== 'instructions'));
  return <><div className="da-ti-caption"><strong>已发布业务规则</strong><CopyButton text={text} label="复制规则" /></div>
    {text.trim() ? <Markdown>{text.slice(0, limit)}</Markdown> : <p className="da-ti-muted">本次调用没有返回业务规则。</p>}
    {text.length > limit && <button type="button" className="da-ti-text-button" onClick={() => setLimit(n => n + 50000)}>展开更多规则（已显示 {limit.toLocaleString()} / {text.length.toLocaleString()} 字符）</button>}
    {Object.keys(extra).length > 0 && <Structured value={extra} />}</>;
}

function ArtifactList({ artifacts }: { artifacts: ArtifactInfo[] }) {
  const [preview, setPreview] = useState<number | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [imageError, setImageError] = useState(false);
  return <><div className="da-ti-caption"><strong>分析文件 · {artifacts.length} 个文件</strong></div>
    {!artifacts.length && <p className="da-ti-muted">本次调用未生成文件。</p>}
    {artifacts.map((a, i) => {
      const src = ['image', 'svg'].includes(a.type) ? artifactSrc(a) : null;
      const downloadable = Boolean(a.path || a.contentB64 || a.content !== undefined);
      return <div className="da-ti-file" key={`${i}:${a.path ?? a.name}`}><Icon name={a.type === 'csv' ? 'table' : src ? 'chart' : 'file'} />
        <div className="da-ti-file-name"><strong title={a.name}>{a.name}</strong><small>{a.type === 'csv' ? '数据文件' : src ? '图片' : '文档'}{a.size > 0 ? ` · ${(a.size / 1024).toFixed(1)} KB` : ''}</small></div>
        {src && <button type="button" onClick={() => { setPreview(preview === i ? null : i); setImageError(false); }}>{preview === i ? '收起' : '预览'}</button>}
        <button type="button" disabled={busy !== null || !downloadable} title={downloadable ? undefined : '当前记录缺少附件地址或内容'} aria-label={`下载 ${a.name}`} onClick={async () => {
          setBusy(i); setError('');
          try { if (a.path) await downloadFile(a.path, a.name); else downloadArtifact(a); }
          catch (e) { setError(e instanceof Error ? e.message : '文件下载失败，请重试'); }
          finally { setBusy(null); }
        }}>{busy === i ? '下载中' : downloadable ? '下载' : '文件未保存'}</button>
        {src && preview === i && <div className="da-ti-preview">{imageError ? <p role="alert">图片加载失败，可重新预览或尝试下载。</p>
          : <AttachmentImage src={src} alt={a.name} onError={() => setImageError(true)} />}</div>}
      </div>;
    })}{error && <ErrorNotice error={error} context="file" />}</>;
}

function PythonResult({ raw }: { raw: string }) {
  const parsed = useMemo(() => parseResult(raw), [raw]);
  if (!raw.includes('### stdout') && !raw.includes('### artifact:')) return <Code text={raw} label="返回文本" />;
  return <><div className="da-ti-caption"><strong>运行结果</strong><CopyButton text={parsed.stdout} label="复制结果" /></div>
    <pre className="da-ti-stdout" tabIndex={0} aria-label="分析运行结果，可横向滚动">{parsed.stdout || '本次分析没有文本结果。'}</pre><ArtifactList artifacts={parsed.artifacts} /></>;
}

function ResultContent({ tool, display, onInput }: { tool: ToolInspectPayload; display: ReturnType<typeof inspectResult>; onInput: () => void }) {
  const table = tableData(display.data);
  const input = inputFields(tool.input);
  const args = record(input.arguments) ? input.arguments : {};
  const sql = display.receipt?.sql ?? (record(display.data) ? display.data.sql : undefined) ?? args.sql ?? input.sql;
  if (tool.id === '__python_artifacts__') return <>{(tool.pythonTools ?? []).map((t, i) => <section className="da-ti-call-files" key={i}>
    <h3>分析步骤 {i + 1}</h3>{t.result === undefined ? <p className="da-ti-muted">本次调用没有保存结果回执。</p> : <>
      {inspectResult('run_python', t.result).state === 'error' && <p className="da-ti-notice">本次执行失败，已保存文件可能不完整。</p>}
      <ArtifactList artifacts={parseResult(displayText(decodeToolValue(t.result))).artifacts} /></>}</section>)}
    {!tool.pythonTools?.length && <p className="da-ti-muted">当前记录中没有分析文件。</p>}<p className="da-ti-muted">文件按分析步骤分别保留，同名文件不会跨步骤合并。</p></>;
  if (display.state === 'unconfirmed') return <div className="da-ti-empty"><Icon name="clock" size="lg" /><h3>结果未确认</h3><p>尚未收到该调用的结果回执。可先查看输入；有新返回时这里会更新。</p><button type="button" onClick={onInput}>查看输入</button></div>;
  if (display.state === 'error' && tool.name === 'run_python') {
    const failed = parseResult(display.text);
    return <><ErrorNotice error={failed.stdout || display.text || '本次分析未能完成'} context="chat" />
      {failed.artifacts.length > 0 && <><p className="da-ti-notice">执行中断前已保存以下文件，内容可能不完整，请核对后使用。</p><ArtifactList artifacts={failed.artifacts} /></>}
      <button type="button" className="da-ti-text-button" onClick={onInput}>查看本次代码</button></>;
  }
  if (display.state === 'error') return <><ErrorNotice error={display.note || display.text || '本次处理未能完成'} context="chat" />
    <p className="da-ti-muted">保留本次错误供核对。重新执行请通过正常对话发起。</p></>;
  if (display.historyTruncated) return <><div className="da-ti-notice">已保存的返回包含截断或驱逐标记，当前记录无法恢复缺失部分。</div><Code text={display.text} label="已保存内容" /></>;
  if (tool.name === 'run_python') return <><PythonResult raw={display.text} /><button type="button" className="da-ti-text-button" onClick={onInput}><Icon name="code" size="sm" />查看本次代码</button></>;
  if (tool.name === 'render_chart' && record(display.data)) {
    const d = display.data;
    return <><h3>图表预览</h3><p className="da-ti-muted">查看本次图表；完整配置在输入中，原始回执单独保留。</p>
      {typeof d.chartId === 'string' || record(d.option) ? <Suspense fallback={<p>正在加载图表…</p>}><EChartsBlock payload={{
        chartId: typeof d.chartId === 'string' ? d.chartId : undefined, option: record(d.option) ? d.option : undefined,
        title: typeof d.title === 'string' ? d.title : undefined,
      }} /></Suspense> : <div className="da-ti-notice">当前回执没有可用的图表标识或配置，可查看原始内容。</div>}
      <button type="button" className="da-ti-text-button" onClick={onInput}>查看图表配置</button></>;
  }
  const actualTool = officialToolName(tool.name, tool.input);
  if (actualTool === 'get_instructions' && record(display.data) && typeof display.data.instructions === 'string') return <BusinessRules data={display.data as Record<string, unknown> & { instructions: string }} />;
  if (actualTool === 'list_models' && record(display.data) && Array.isArray(display.data.models)) return <ModelDirectory models={display.data.models} />;
  if (actualTool === 'describe_model' && record(display.data) && Array.isArray(display.data.columns)) return <ModelSchema model={display.data} />;
  const matches = record(display.data) ? display.data.matches ?? display.data.queries : undefined;
  if (Array.isArray(matches)) return <><h3>相似问答</h3><p className="da-ti-muted">{matches.length ? `返回 ${matches.length} 条参考问答，参考示例不等于本次查询结果。` : '未找到相似问答，可依据已发布模型继续查询。'}</p>
    {matches.map((m, i) => <article className="da-ti-match" key={i}><h3>{i + 1}. {record(m) ? valueText(m.nl_query ?? m.nl ?? m.question) : valueText(m)}</h3>
      {record(m) && <Structured value={Object.fromEntries(Object.entries(m).filter(([k]) => !['nl_query', 'nl', 'question'].includes(k)))} />}</article>)}</>;
  return <>{typeof display.receipt?.question === 'string' && <h3>{display.receipt.question}</h3>}
    {typeof sql === 'string' && <Code text={sql} label="SQL" />}
    {table ? <ResultTable data={table} /> : display.data !== undefined ? <Structured value={display.data} /> : null}
    {display.note && (display.note.length > 20000 ? <Code text={display.note} label="返回文本" /> : <Markdown>{display.note}</Markdown>)}
    {display.data === undefined && !display.note && <p className="da-ti-muted">工具已返回，内容为空。</p>}</>;
}

function Inspector({ tool, onClose }: ToolInspectorProps) {
  const [tab, setTab] = useState<Tab>('result');
  const [expanded, setExpanded] = useState(false);
  const [rawPart, setRawPart] = useState('result');
  const [narrow, setNarrow] = useState(() => window.matchMedia('(max-width: 760px)').matches);
  const root = useRef<HTMLElement>(null);
  const close = useRef<HTMLButtonElement>(null);
  const tabs = useRef<(HTMLButtonElement | null)[]>([]);
  const id = useId();
  const display = useMemo(() => inspectResult(tool.name, tool.result), [tool.name, tool.result]);
  const input = useMemo(() => inputFields(tool.input), [tool.input]);
  const title = tool.id === '__python_artifacts__' ? '分析文件' : wrenToolLabel(tool.name, tool.input) ?? LABELS[tool.name] ?? tool.name;
  const args = record(input.arguments) ? input.arguments : {};
  const code = tool.name === 'run_python' ? input.code : args.sql ?? input.sql;
  const otherInput = { ...input };
  const chartData = tool.name === 'render_chart' ? tableData(input) : null;
  if (chartData) { delete otherInput.columns; delete otherInput.rows; }
  for (const k of ['code', 'sql', 'answer_question', 'description', 'query_type', 'path']) delete otherInput[k];
  if (record(otherInput.arguments)) {
    const rest = { ...otherInput.arguments }; delete rest.sql;
    if (Object.keys(rest).length) otherInput.arguments = rest; else delete otherInput.arguments;
  }
  const source = rawPart === 'input' ? tool.input : tool.id === '__python_artifacts__' ? JSON.stringify(tool.pythonTools, null, 2) : tool.result;
  const state = tool.id === '__python_artifacts__' ? 'returned' : display.state;
  const stateLabel = { unconfirmed: '结果未确认', returned: '已返回', success: '执行成功', error: '调用失败' }[state];
  const latest = useRef({ onClose, expanded, narrow });
  latest.current = { onClose, expanded, narrow };
  useEffect(() => {
    const media = window.matchMedia('(max-width: 760px)');
    const update = () => { setNarrow(media.matches); if (media.matches) setExpanded(false); };
    media.addEventListener('change', update); return () => media.removeEventListener('change', update);
  }, []);
  useEffect(() => {
    const origin = document.activeElement;
    const pane = root.current;
    close.current?.focus({ preventScroll: true });
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); if (latest.current.expanded) setExpanded(false); else latest.current.onClose(); }
      if (e.key !== 'Tab' || (!latest.current.expanded && !latest.current.narrow)) return;
      const nodes = Array.from(root.current?.querySelectorAll<HTMLElement>('button:not(:disabled), select, a[href], [tabindex="0"]') ?? []).filter(n => n.offsetParent !== null);
      const first = nodes[0], last = nodes[nodes.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus(); }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      if (origin instanceof HTMLElement && origin.isConnected && (document.activeElement === document.body || pane?.contains(document.activeElement))) origin.focus({ preventScroll: true });
    };
  }, []);
  return <aside ref={root} className={`da-ti ${expanded ? 'da-ti-expanded' : ''}`} role={expanded || narrow ? 'dialog' : 'complementary'} aria-modal={expanded || narrow ? true : undefined} aria-label="工具调用详情">
    <header className="da-ti-head"><span className="da-ti-tool-icon"><Icon name={toolIcon(tool.name, tool.input)} /></span>
      <h2 title={title}>{title}</h2><span className={`da-ti-state da-ti-state-${state}`}>{stateLabel}</span>
      {!narrow && <button type="button" className="da-ti-icon-button" aria-label={expanded ? '恢复侧栏' : '放大阅读工具详情'} title={expanded ? '恢复侧栏' : '放大阅读'} onClick={() => setExpanded(v => !v)}><Icon name={expanded ? 'back' : 'fit'} size="sm" /></button>}
      <button ref={close} type="button" className="da-ti-icon-button" aria-label="关闭工具详情" title="关闭" onClick={onClose}><Icon name="close" size="sm" /></button></header>
    <div className="da-ti-meta"><code>{officialToolName(tool.name, tool.input)}</code><span title={tool.id}>本次调用 · {tool.id === '__python_artifacts__' ? '文件汇总' : tool.id.slice(-8)}</span></div>
    <nav role="tablist" aria-label="工具详情内容" className="da-ti-tabs">{TABS.map(([key, label], i) => <button key={key} ref={node => { tabs.current[i] = node; }} type="button" role="tab" id={`${id}-${key}`} aria-controls={`${id}-panel`} aria-selected={tab === key} tabIndex={tab === key ? 0 : -1} onClick={() => setTab(key)} onKeyDown={e => {
      let next = i;
      if (e.key === 'ArrowRight') next = (i + 1) % 3; else if (e.key === 'ArrowLeft') next = (i + 2) % 3;
      else if (e.key === 'Home') next = 0; else if (e.key === 'End') next = 2; else return;
      e.preventDefault(); setTab(TABS[next][0]); tabs.current[next]?.focus();
    }}>{label}</button>)}</nav>
    <section className="da-ti-body" role="tabpanel" id={`${id}-panel`} aria-labelledby={`${id}-${tab}`} tabIndex={0}>
      {tab === 'result' ? <ResultContent tool={tool} display={display} onInput={() => { setTab('input'); tabs.current[1]?.focus(); }} /> : tab === 'input' ? <>
        <h3>本次调用输入</h3>
        <dl className="da-ti-fields">{['answer_question', 'description', 'query_type', 'path'].filter(k => input[k] !== undefined).map(k => <div key={k}><dt>{{ answer_question: '查询问题', description: '任务说明', query_type: '查询用途', path: '文件路径' }[k]}</dt><dd>{valueText(input[k])}</dd></div>)}</dl>
        {typeof code === 'string' ? <Code text={code} label={tool.name === 'run_python' ? '代码' : 'SQL'} /> : null}
        {Object.keys(otherInput).length ? <Structured value={otherInput} /> : null}
        {chartData && <div className="da-ti-chart-input"><ResultTable data={chartData} sourceLabel="输入" /></div>}
        {tool.input !== undefined && !record(decodeToolValue(tool.input)) ? <Code text={displayText(decodeToolValue(tool.input))} label="参数" /> : null}
        {tool.input === undefined ? <p className="da-ti-muted">当前记录没有保存输入参数。</p> : null}
        <button type="button" className="da-ti-text-button" onClick={() => { setRawPart('input'); setTab('raw'); tabs.current[2]?.focus(); }}>查看完整原始输入</button>
      </> : <><div className="da-ti-raw-select"><label htmlFor={`${id}-raw-source`}>原始内容类型</label><select id={`${id}-raw-source`} value={rawPart} onChange={e => setRawPart(e.target.value)}><option value="result">原始返回</option><option value="input">原始输入</option></select></div>
        {source !== undefined ? <Code key={rawPart} text={source} label="原文" /> : <div className="da-ti-notice">当前记录没有保存{rawPart === 'input' ? '输入' : '结果回执'}。</div>}
        <p className="da-ti-muted">保留原始字符、转义及错误信息，不自动补齐或重新执行工具。</p></>}
    </section>
    <footer className="da-ti-foot">{state === 'success' ? '成功表示工具已返回可用结果，业务结论仍需核对。' : state === 'error' ? '本次调用状态与最终回答状态分别展示。' : '按本次已保存的调用内容展示，缺失信息不补造。'}</footer>
  </aside>;
}

export default function ToolInspector(props: ToolInspectorProps) {
  return <Inspector key={props.tool.id} {...props} />;
}
