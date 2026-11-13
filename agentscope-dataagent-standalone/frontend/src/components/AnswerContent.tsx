import type { ComponentProps } from 'react';
import Markdown from './Markdown';
import { normalizeChartReferences } from './answerRefs';

/** Shared answer typography, tables, charts and image viewer for live and read-only history. */
export default function AnswerContent(props: ComponentProps<typeof Markdown>) {
  return <div className="da-answer-content"><Markdown {...props}>{normalizeChartReferences(props.children, props.charts?.keys() ?? [])}</Markdown></div>;
}
