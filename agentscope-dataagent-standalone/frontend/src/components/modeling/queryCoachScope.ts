import { DEFINITION_FORMAT } from './QuestionIntakeForm';
import type { TurnPurpose } from '../../api/chat';

export const COACH_PREFIX = 'q-coach-';
const RECEIPT = '【问数优化】';

export type CoachQuestion = { id: string; question: string };
export type CoachRequest = { text: string; purpose?: TurnPurpose; questionId?: string; label: string; interpretAction?: boolean };

export const isScopedQuestion = (id: string) => id.length <= 64
  && /^q-(?:coach-[A-Za-z0-9_-]{1,56}|(?:asset-)?[a-z0-9]+-[0-9]+)$/.test(id);

export function newCoachQuestion(question: string): CoachQuestion {
  const nonce = Array.from(crypto.getRandomValues(new Uint32Array(3)), value => value.toString(36)).join('-');
  return { id: COACH_PREFIX + nonce, question };
}

export function coachReceipt(text: string): { id: string; label: string } | undefined {
  if (!text.startsWith(RECEIPT)) return undefined;
  const newline = text.indexOf('\n');
  if (newline < 0) return undefined;
  try {
    const receipt = JSON.parse(text.slice(RECEIPT.length, newline));
    if (typeof receipt.id === 'string' && isScopedQuestion(receipt.id) && typeof receipt.label === 'string') return receipt;
  } catch { /* Older messages and ordinary chat render as written. */ }
}

export function coachQuestionRequest(q: CoachQuestion, label: string, instruction: string, status?: string): CoachRequest {
  return { questionId: q.id, purpose: 'coach', label,
    text: RECEIPT + JSON.stringify({ id: q.id, label }) + '\n'
      + `仅处理问数优化中的这一题：${q.question}\n目标文件 knowledge/questions/${q.id}.yml。保留 question 原文，仅更新 definition、sql、analysis（${DEFINITION_FORMAT}）。`
      + '只用 wren_cli dry-plan 检查 SQL，不查询数据。用户要求仅用于本题的口径直接写入本题。'
      + '涉及共享规则、关系或计算字段的变更，使用 propose_modeling_change 关联本题，等待批准。缺少单题口径时在回复里问用户。\n'
      + (status ? `本轮开始时的工作台状态：${status}。\n` : '')
      + '写入题目文件、检查 SQL 和人工验收是不同步骤。以工作台状态为准，未完成验收时提醒用户使用题目详情中的确认按钮。发布状态以知识库发布结果为准。\n'
      + `本次要求：${instruction}` };
}

export function coachIntent(text: string): { kind: 'create' | 'select' | 'remove'; text: string } | undefined {
  const cleaned = text.trim();
  const body = cleaned.replace(/^问数优化\s*[:：]\s*/, '');
  if (/^(?:(?:移除|移出)(?:这道题|这题|这个问题)|下架(?:这条示例|这个示例))[。！!？?]?$/.test(body)) {
    return { kind: 'remove', text: '' };
  }
  const removal = /^(?:(?:移除|移出)(?:业务问题|题目)|下架示例)\s*[:：]\s*(.+)$/.exec(body);
  if (removal?.[1].trim()) return { kind: 'remove', text: removal[1].trim().replace(/^[「“]|[」”]$/g, '').trim() };
  // Leave instructions about removing SQL clauses with the assistant.
  const addition = /^(?:添加|新增|加入)(?:到问数优化)?(?:一道|一个)?(?:常用|业务)?问题\s*[:：]\s*([\s\S]+)$/.exec(body);
  if (addition?.[1].trim()) return { kind: 'create', text: addition[1].trim().replace(/^[「“]|[」”]$/g, '') };
  if (body !== cleaned || /(?:优化|修正|核对|改写).*(?:[「“].+[」”]|这道题)/.test(body)) {
    return { kind: 'select', text: body };
  }
}

const normalize = (text: string) => text.toLowerCase().replace(/[\s？?。！!，,「」“”]/g, '')
  .replace(/(?:是?多少|是什么|有哪些)$/, '');

export function findCoachQuestions(text: string, questions: CoachQuestion[]): CoachQuestion[] {
  const quoted = [...text.matchAll(/[「“]([^」”]+)[」”]/g)].map(match => normalize(match[1])).filter(Boolean);
  const request = normalize(text);
  const exact = questions.filter(q => quoted.includes(normalize(q.question)));
  if (exact.length) return exact;
  return questions.filter(q => {
    const title = normalize(q.question);
    return title && (request.includes(title) || quoted.some(term => title.includes(term)));
  });
}

/** A quoted question that cannot be identified must not silently edit the previous target. */
export function resolveCoachTarget(instruction: string | undefined, questions: CoachQuestion[],
  scope?: CoachQuestion, explicitlyChosen = false) {
  const matches = instruction ? findCoachQuestions(instruction, questions) : [];
  const namesQuestion = instruction && /[「“][^」”]+[」”]\s*(?:这道题|这题|这个问题)|(?:题目|业务问题|这道题)\s*[「“]/.test(instruction);
  if (matches.length === 1) return { question: matches[0], matches };
  const ask = matches.length > 1 || namesQuestion;
  return { question: ask && !explicitlyChosen ? undefined : scope, matches };
}
