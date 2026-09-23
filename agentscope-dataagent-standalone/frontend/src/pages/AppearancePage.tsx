import React, { useEffect, useState } from 'react';

const STORAGE_KEY = 'claw_appearance';

interface Appearance {
  accentColor: string;
  fontSize: number;
  compactMode: boolean;
  welcomeMessage: string;
}

const DEFAULTS: Appearance = {
  accentColor: 'var(--da-primary)',
  fontSize: 14,
  compactMode: false,
  welcomeMessage: 'Welcome to 红海DataAgent!',
};

function load(): Appearance {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return { ...DEFAULTS, ...JSON.parse(raw) };
  } catch { /* ignore */ }
  return { ...DEFAULTS };
}

const S: Record<string, React.CSSProperties> = {
  content: { padding: '2rem 1.75rem', maxWidth: 640, margin: '0 auto' },
  heading: { fontSize: '1.3rem', fontWeight: 700, color: 'var(--da-text)', marginBottom: '1.5rem' },
  section: { background: 'var(--da-surface)', border: '1px solid var(--da-border)', borderRadius: 10, padding: '1.4rem', marginBottom: 16 },
  sectionTitle: { fontSize: '0.85rem', fontWeight: 600, color: '#7c8bad', marginBottom: 16 },
  row: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 },
  label: { fontSize: '0.85rem', color: '#94a3b8' },
  sub: { fontSize: '0.75rem', color: '#374056', marginTop: 2 },
  input: {
    background: '#0d0f18', border: '1px solid var(--da-border-strong)', borderRadius: 6,
    color: 'var(--da-text)', fontSize: '0.85rem', padding: '5px 10px', outline: 'none',
    width: 220,
  },
  colorInput: {
    width: 48, height: 32, border: '1px solid var(--da-border-strong)', borderRadius: 6,
    background: 'none', cursor: 'pointer', padding: 2,
  },
  toggle: {
    width: 40, height: 22, borderRadius: 11,
    border: '1px solid var(--da-border-strong)', cursor: 'pointer',
    position: 'relative', flexShrink: 0,
    transition: 'background 0.2s',
  },
  toggleKnob: {
    position: 'absolute', top: 2, width: 16, height: 16,
    borderRadius: '50%', background: '#fff',
    transition: 'left 0.2s',
  },
  rangeInput: { width: 160, accentColor: 'var(--da-primary)' },
  saveBtn: {
    background: 'var(--da-primary)', color: '#fff', border: 'none', borderRadius: 7,
    padding: '8px 22px', cursor: 'pointer', fontWeight: 600, fontSize: '0.88rem',
  },
  resetBtn: {
    background: 'transparent', border: '1px solid var(--da-border-strong)', color: '#7c8bad',
    borderRadius: 7, padding: '8px 16px', cursor: 'pointer', fontSize: '0.88rem', marginLeft: 10,
  },
  saved: { color: '#4ade80', fontSize: '0.82rem', marginLeft: 12 },
};

function Toggle({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return (
    <div
      style={{ ...S.toggle, background: value ? 'var(--da-primary)' : 'var(--da-border)' }}
      onClick={() => onChange(!value)}
    >
      <div style={{ ...S.toggleKnob, left: value ? 20 : 2 }} />
    </div>
  );
}

export default function AppearancePage() {
  const [settings, setSettings] = useState<Appearance>(load);
  const [saved, setSaved] = useState(false);

  function set<K extends keyof Appearance>(k: K, v: Appearance[K]) {
    setSettings(s => ({ ...s, [k]: v }));
    setSaved(false);
  }

  function save() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
    setSaved(true);
    setTimeout(() => setSaved(false), 2500);
  }

  function reset() {
    setSettings({ ...DEFAULTS });
    localStorage.removeItem(STORAGE_KEY);
    setSaved(false);
  }

  // Apply accent color CSS variable live
  useEffect(() => {
    document.documentElement.style.setProperty('--claw-accent', settings.accentColor);
  }, [settings.accentColor]);

  return (
    <>
      <div style={S.content}>
        <h2 style={S.heading}>外观设置</h2>
        <p style={{ color: '#4b5571', fontSize: '0.82rem', marginBottom: '1.25rem', lineHeight: 1.6 }}>
          Customize the look and feel of the 红海DataAgent web interface. Settings are saved locally in your browser.
        </p>

        {/* Theme section */}
        <div style={S.section}>
          <div style={S.sectionTitle}>主题</div>

          <div style={S.row}>
            <div>
              <div style={S.label}>强调色</div>
              <div style={S.sub}>Used for buttons, active state, and highlights</div>
            </div>
            <input
              type="color"
              style={S.colorInput}
              value={settings.accentColor}
              onChange={e => set('accentColor', e.target.value)}
            />
          </div>

          <div style={S.row}>
            <div>
              <div style={S.label}>字号</div>
              <div style={S.sub}>{settings.fontSize}px</div>
            </div>
            <input
              type="range"
              style={S.rangeInput}
              min={12} max={18} step={1}
              value={settings.fontSize}
              onChange={e => set('fontSize', Number(e.target.value))}
            />
          </div>

          <div style={S.row}>
            <div>
              <div style={S.label}>紧凑模式</div>
              <div style={S.sub}>减小内边距与消息气泡尺寸</div>
            </div>
            <Toggle value={settings.compactMode} onChange={v => set('compactMode', v)} />
          </div>
        </div>

        {/* Chat section */}
        <div style={S.section}>
          <div style={S.sectionTitle}>聊天</div>

          <div>
            <div style={{ ...S.label, marginBottom: 8 }}>欢迎语</div>
            <div style={S.sub}>Shown on the chat page when no agent is selected</div>
            <textarea
              style={{
                ...S.input,
                width: '100%',
                marginTop: 8,
                height: 72,
                resize: 'vertical',
                boxSizing: 'border-box',
              }}
              value={settings.welcomeMessage}
              onChange={e => set('welcomeMessage', e.target.value)}
            />
          </div>
        </div>

        {/* Preview */}
        <div style={S.section}>
          <div style={S.sectionTitle}>强调色预览</div>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
            <button style={{ ...S.saveBtn, background: settings.accentColor }}>主按钮</button>
            <span style={{ color: settings.accentColor, fontWeight: 600, fontSize: '0.9rem' }}>激活链接</span>
            <div style={{ width: 14, height: 14, borderRadius: '50%', background: settings.accentColor }} />
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', marginTop: 8 }}>
          <button style={S.saveBtn} onClick={save}>保存</button>
          <button style={S.resetBtn} onClick={reset}>恢复默认</button>
          {saved && <span style={S.saved}>✓ Saved</span>}
        </div>
      </div>
    </>
  );
}
