import { useLayoutEffect, useRef, useState } from 'react';
import type { MutableRefObject } from 'react';
import { autocompletion, closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete';
import type { CompletionSource } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap, isolateHistory } from '@codemirror/commands';
import { StandardSQL, sql } from '@codemirror/lang-sql';
import { bracketMatching, defaultHighlightStyle, indentOnInput, syntaxHighlighting, syntaxTree } from '@codemirror/language';
import { Compartment, EditorSelection, EditorState } from '@codemirror/state';
import { EditorView, keymap, lineNumbers } from '@codemirror/view';

export type ExpressionReference = {
  key: string;
  label: string;
  detail?: string | null;
  kind?: string | null;
  prefix: string;
  tail?: string;
};

export function insertExpressionReference(view: EditorView, reference: ExpressionReference, from?: number, to?: number) {
  if (view.state.readOnly) return;
  const { main } = view.state.selection;
  const start = from ?? main.from;
  const end = to ?? main.to;
  const { prefix, tail } = reference;
  const selected = from === undefined && tail ? view.state.sliceDoc(start, end) : '';
  const insert = prefix + selected + (tail ?? '');
  const anchor = start + (tail && !selected ? prefix.length : insert.length);
  view.dispatch({
    changes: { from: start, to: end, insert },
    selection: EditorSelection.cursor(anchor),
    scrollIntoView: true,
    annotations: isolateHistory.of('full'),
    userEvent: 'input.complete',
  });
  view.focus();
}

function completeFrom(references: readonly ExpressionReference[]): CompletionSource {
  return context => {
    const node = syntaxTree(context.state).resolveInner(context.pos, -1);
    if (['String', 'QuotedIdentifier', 'LineComment', 'BlockComment'].includes(node.name)) return null;
    const word = context.matchBefore(/[\p{L}\p{N}_$]*/u);
    if (!word || (!context.explicit && word.from === word.to)) return null;
    const search = word.text.toLowerCase();
    const options = references.filter(item =>
      item.key.toLowerCase().includes(search) || item.label.toLowerCase().includes(search),
    ).map(item => ({
      label: item.label,
      detail: item.tail === undefined && item.label !== item.key ? item.key : item.kind ?? undefined,
      type: item.tail === undefined ? 'variable' : 'function',
      info: item.detail || undefined,
      apply: (view: EditorView, _completion: unknown, from: number, to: number) => insertExpressionReference(view, item, from, to),
    }));
    return options.length ? { from: word.from, options, filter: false } : null;
  };
}

function editability(disabled: boolean) {
  return [EditorState.readOnly.of(disabled), EditorView.editable.of(!disabled)];
}

export default function CalculatedExpressionEditor({ initialValue, onChange, disabled, references, editorRef }: {
  initialValue: string;
  onChange: (value: string) => void;
  disabled: boolean;
  references: readonly ExpressionReference[];
  editorRef: MutableRefObject<EditorView | null>;
}) {
  const host = useRef<HTMLDivElement>(null);
  const [access] = useState(() => new Compartment());
  const [completions] = useState(() => new Compartment());
  const [documentInfo, setDocumentInfo] = useState(() => ({
    lines: initialValue.split(/\r\n?|\n/).length, empty: !initialValue.trim(),
  }));
  const [notice, setNotice] = useState('');
  const [formatting, setFormatting] = useState(false);

  useLayoutEffect(() => {
    if (!host.current) return;
    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: initialValue,
        extensions: [
          lineNumbers(), history(), bracketMatching(), closeBrackets(), indentOnInput(),
          sql({ dialect: StandardSQL }), syntaxHighlighting(defaultHighlightStyle),
          EditorView.lineWrapping,
          EditorView.contentAttributes.of({ 'aria-label': 'SQL 表达式', spellcheck: 'false', autocapitalize: 'off' }),
          keymap.of([...closeBracketsKeymap, ...defaultKeymap, ...historyKeymap]),
          access.of([]), completions.of([]),
          EditorView.updateListener.of(update => {
            if (!update.docChanged) return;
            const value = update.state.doc.toString();
            onChange(value);
            setDocumentInfo({ lines: update.state.doc.lines, empty: !value.trim() });
            setNotice('');
          }),
        ],
      }),
    });
    editorRef.current = view;
    return () => { editorRef.current = null; view.destroy(); };
  }, [access, completions, editorRef, initialValue, onChange]);

  useLayoutEffect(() => {
    editorRef.current?.dispatch({ effects: access.reconfigure(editability(disabled)) });
  }, [access, disabled, editorRef, initialValue, onChange]);

  useLayoutEffect(() => {
    editorRef.current?.dispatch({ effects: completions.reconfigure(autocompletion({
      override: [completeFrom(references)],
    })) });
  }, [completions, editorRef, initialValue, onChange, references]);

  async function format() {
    const view = editorRef.current;
    if (!view || view.state.readOnly || view.composing) return;
    const original = view.state.doc;
    setFormatting(true);
    try {
      const { formatCalculatedExpression } = await import('./formatCalculatedExpression');
      if (editorRef.current !== view || view.state.doc !== original || view.state.readOnly || view.composing) return;
      const before = original.toString();
      const after = formatCalculatedExpression(before);
      if (after === before) {
        setNotice('已是规范格式');
        return;
      }
      view.dispatch({
        changes: { from: 0, to: original.length, insert: after },
        annotations: isolateHistory.of('full'),
      });
      setNotice('已格式化，可撤销');
      view.focus();
    } catch {
      if (editorRef.current === view && view.state.doc === original) setNotice('无法安全格式化，已保留原表达式');
    } finally {
      if (editorRef.current === view) setFormatting(false);
    }
  }

  return <div className="calc-expression">
    <div className="calc-expression-toolbar">
      <span>SQL</span><span className="calc-expression-notice" role="status">{notice}</span>
      <span>{documentInfo.lines} 行</span>
      <button type="button" className="da-btn da-btn-sm" disabled={disabled || documentInfo.empty || formatting}
        onClick={() => void format()}>{formatting ? '格式化中…' : '格式化'}</button>
    </div>
    <div className="calc-expression-editor" ref={host} />
  </div>;
}
