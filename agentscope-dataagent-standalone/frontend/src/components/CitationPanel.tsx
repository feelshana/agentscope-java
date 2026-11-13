import React, { useMemo, useState } from 'react';
import { answerQueryRecords, queryRecordStatus, type QueryToolEntry } from '../utils/answerQueryRecords';
import type { ToolInspectPayload } from './ToolCallBlock';
import Icon from './Icon';
import './answer-query-records.css';

const timeLabel = (value: string) => new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
}).format(new Date(value));

export default function CitationPanel({ tools, groupNames = {}, onInspect }: {
  tools: QueryToolEntry[];
  groupNames?: Record<string, string>;
  onInspect?: (tool: ToolInspectPayload) => void;
}) {
  const [open, setOpen] = useState(false);
  const records = useMemo(() => answerQueryRecords(tools), [tools]);
  if (!records.length) return null;
  return <details className="da-query-records" open={open} onToggle={e => setOpen(e.currentTarget.open)}>
    <summary><Icon name="database" size="sm" /><span>本轮查询记录</span><span className="da-query-count">{records.length}</span><Icon name="chevron" size="sm" /></summary>
    {open && <div className="da-query-records-body">
      <p className="da-query-context">记录本轮实际调用，不代表每条结论已经核验。请核对指标口径、日期和筛选条件。</p>
      {records.map((item, index) => <section className="da-query-record" key={item.tool.id}>
        <div className="da-query-record-heading"><strong>查询 {index + 1}</strong><span className={item.state === 'error' ? 'da-query-error' : ''}>{queryRecordStatus(item)}</span>
          {onInspect && <button type="button" className="da-query-inspect" onClick={() => onInspect(item.tool)}>查看数据与 SQL<Icon name="chevron" size="sm" /></button>}</div>
        {item.groupId && <p className="da-query-context">知识库：{groupNames[item.groupId] || item.groupId}</p>}
        <dl className="da-query-metadata"><div><dt>查询完成时间</dt><dd>{item.queriedAt ? `${timeLabel(item.queriedAt)}（北京时间）` : '未记录'}</dd></div>
          <div><dt>数据更新时间</dt><dd>未记录</dd></div><div><dt>当时模型版本</dt><dd>未记录</dd></div>
          {item.queryId && <div><dt>查询记录编号</dt><dd><code>{item.queryId}</code></dd></div>}</dl>
        {item.sql ? <details className="da-query-sql"><summary>{item.state === 'success' ? '查看本次 SQL' : '查看记录中的 SQL'}</summary><pre tabIndex={0} aria-label={`查询 ${index + 1} 的 SQL，可横向滚动`}>{item.sql}</pre></details>
          : <p className="da-query-context">当前记录未保存 SQL，可查看原始调用详情。</p>}
        {item.truncated && <p className="da-query-warning">工具返回已截断，不能据此当作完整查询结果。</p>}
        {item.historyTruncated && <p className="da-query-warning">历史返回存在截断或驱逐标记，缺失内容无法从当前记录恢复。</p>}
        {item.rows !== undefined && item.returnedRows !== item.rows && <p className="da-query-context">本查询返回 {item.returnedRows} 行，当前回执保留 {item.rows} 行样本；完整文件是否可用以下载入口为准。</p>}
        {item.limit !== undefined && <p className="da-query-context">请求行数上限 {item.limit}；返回行数不等于业务表总行数，也不能据此判断源数据完整。</p>}
      </section>)}
    </div>}
  </details>;
}
