import { collapseHistoryMessage } from './historyPresentation';

/** Historical code is rendered as text; expanding never executes HTML or SQL. */
export default function HistoryUserMessage({ text }: { text: string | null }) {
  if (!text) return <p>（无文字）</p>;
  if (!collapseHistoryMessage(text)) return <p>{text}</p>;
  return <>
    <p>{text.slice(0, 160).trimEnd()}…</p>
    <details className="ah-long-message"><summary>展开完整用户消息（{text.length.toLocaleString()} 字符）</summary><p>{text}</p></details>
  </>;
}
