import { useState } from 'react';

export function questionRequest(questions: string[]): string {
  return '这些是可选的业务问题。请加载同版本官方 Wren 技能，结合当前工程与可选文档澄清歧义，按官方流程完善模型。复用已有资产，选择模型、视图或 Cube 的依据遵循官方指导，不要求每题新建资产。通过原生文件工具提出草稿变更，并由你执行官方校验、编译和查询检查。预设问题不是发布前置条件；需要保存问题与 SQL 时使用官方 memory store，并让我确认。\n' + questions.join('\n');
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
    <p className="da-small">预设问题可选，每行一个，最多 50 个。不填写也可直接对话建模；问题测试与确认不阻碍模型发布。</p>
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
    <p role="status" className="da-small">{questions.length} 个不同问题 · 提交后交给建模助手处理。助手保存的官方 SQL 样例可在“查看语义模型 → 业务知识与样例”中查看。</p>
    {(questions.length > 50 || tooLong) && <p role="alert">每次最多 50 个问题，每个问题最多 1000 字符，请调整后提交。</p>}
    <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
      {onCancel && <button className="da-btn" onClick={onCancel}>取消</button>}
      <button className="da-btn da-btn-primary" disabled={!questions.length || questions.length > 50 || tooLong}
        onClick={() => onSubmit(questionRequest(questions))}>提交问题，开始建模</button>
    </div>
  </div>;
}
