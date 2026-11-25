import { useMemo, useRef } from 'react';

export default function SourceFileEditor({ label, value, onChange, disabled, errorLine }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  errorLine?: number;
}) {
  const gutter = useRef<HTMLDivElement>(null);
  const editor = useRef<HTMLTextAreaElement>(null);
  const count = useMemo(() => value.split('\n').length, [value]);
  const numbers = useMemo(() => Array.from({ length: count }, (_, index) => index + 1).join('\n'), [count]);
  return <div className="eng-source-editor">
    <div className="eng-source-gutter" ref={gutter} aria-hidden="true"><pre>{errorLine && errorLine <= count
      ? <>{numbers.split('\n').slice(0, errorLine - 1).map(number => number + '\n').join('')}<mark>{errorLine}</mark>{'\n' + numbers.split('\n').slice(errorLine).join('\n')}</>
      : numbers}</pre></div>
    <textarea ref={editor} autoFocus aria-label={label} aria-invalid={errorLine ? true : undefined} spellCheck={false} autoCapitalize="off" autoComplete="off" wrap="off"
      disabled={disabled} value={value} onChange={event => onChange(event.currentTarget.value)}
      onScroll={event => { if (gutter.current) gutter.current.scrollTop = event.currentTarget.scrollTop; }} />
    {!!errorLine && errorLine <= count && <button type="button" className="sw-link eng-error-locate" onClick={() => {
      const area = editor.current;
      if (!area) return;
      const lines = value.split('\n');
      const start = lines.slice(0, errorLine - 1).reduce((offset, line) => offset + line.length + 1, 0);
      area.focus();
      area.setSelectionRange(start, start + lines[errorLine - 1].length);
      const lineHeight = Number.parseFloat(getComputedStyle(area).lineHeight);
      area.scrollTop = Math.max(0, (errorLine - 3) * lineHeight);
    }}>定位第 {errorLine} 行</button>}
  </div>;
}
