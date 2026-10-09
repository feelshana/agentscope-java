import { useState } from 'react';

export function questionRequest(questions: string[]): string {
  return '这是本次建模的分析问题，请先核对已有问题并去重，经受保护文件工具与 HITL 保存；工具成功前不要声称已保存。结合已有模型和可选文档，合并澄清缺失或冲突的业务口径。已有资产足够时直接生成逻辑查询并沉淀确认实例，不要求每题新建资产；只有可复用的业务定义需要新增视图、Cube 或 SQL 定义模型。自动完成 YAML 校验、编译与查询检查，生成每题查询供我验证结果。不要根据问题标题猜测业务规则，也不要只记录空 SQL 后结束：\n' + questions.join('\n');
}

export default function QuestionIntakeForm({ onSubmit, onCancel, initial = false }: {
  onSubmit: (message: string) => void;
  onCancel?: () => void;
  initial?: boolean;
}) {
  const [rows, setRows] = useState(['', '', '']);
  const [paste, setPaste] = useState('');
  const [pasteError, setPasteError] = useState<string | null>(null);
  const questions = [...new Set(rows.map(x => x.trim()).filter(Boolean))];
  const tooLong = questions.some(q => q.length > 1000);
  return <div aria-label="分析问题表单">
    <h3>{initial ? '填写你希望分析的问题' : '添加分析问题'}</h3>
    <p className="da-small">每行一个问题，最多 50 个。可以先填写一个，再继续补充；助手优先复用已有模型，只在需要时提出新增资产。</p>
    <table className="da-table" style={{ width: '100%' }}>
      <thead><tr><th style={{ width: 40 }}>序号</th><th>分析问题</th><th style={{ width: 70 }}>操作</th></tr></thead>
      <tbody>{rows.map((row, index) => <tr key={index}>
        <td>{index + 1}</td><td><textarea className="da-input" aria-label={`第 ${index + 1} 个问题`}
          value={row} maxLength={1000} rows={2} style={{ width: '100%', boxSizing: 'border-box' }}
          placeholder={index === 0 ? '例如：每月客户营收如何变化？' : '填写问题，可留空'}
          onChange={e => setRows(previous => previous.map((value, i) => i === index ? e.target.value : value))} /></td>
        <td><button type="button" className="da-btn da-btn-sm" aria-label={`删除第 ${index + 1} 行`}
          onClick={() => setRows(previous => previous.length === 1 ? [''] : previous.filter((_, i) => i !== index))}>删除</button></td>
      </tr>)}</tbody>
    </table>
    <button type="button" className="da-btn" disabled={rows.length >= 50} onClick={() => setRows(previous => [...previous, ''])}>＋ 增加一行</button>
    <details style={{ marginTop: 12 }}><summary>批量粘贴问题</summary>
      <textarea className="da-input" aria-label="批量粘贴问题" value={paste} onChange={e => setPaste(e.target.value)}
        rows={4} style={{ width: '100%', boxSizing: 'border-box' }} placeholder="每行一个问题" />
      <button type="button" className="da-btn" disabled={!paste.trim()}
        onClick={() => {
          const merged = [...new Set([...questions, ...paste.split(/\r?\n/).map(x => x.trim()).filter(Boolean)])];
          if (merged.length > 50 || merged.some(q => q.length > 1000)) {
            setPasteError('加入后最多 50 个不同问题，每题最多 1000 字符，请缩小粘贴范围。'); return;
          }
          setRows(merged); setPaste(''); setPasteError(null);
        }}>加入表格</button>
      {pasteError && <p role="alert">{pasteError}</p>}
    </details>
    <p role="status" className="da-small">{questions.length} 个不同问题 · 尚未保存。提交后进入澄清与建模，保存成功后出现在问题清单中。</p>
    {(questions.length > 50 || tooLong) && <p role="alert">每次最多 50 个问题，每个问题最多 1000 字符，请调整后提交。</p>}
    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
      {onCancel && <button className="da-btn" onClick={onCancel}>取消</button>}
      <button className="da-btn da-btn-primary" disabled={!questions.length || questions.length > 50 || tooLong}
        onClick={() => onSubmit(questionRequest(questions))}>提交问题，开始建模</button>
    </div>
  </div>;
}
