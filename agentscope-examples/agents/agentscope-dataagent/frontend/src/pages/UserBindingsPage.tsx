import React, { useEffect, useState } from 'react';
import { getToken } from '../api/auth';

interface UserBinding {
  channelId: string;
  displayLabel?: string | null;
  sessionScope?: string | null;
  language?: string | null;
  enabledSkills?: string[] | null;
}

function authHeaders() {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` };
}

async function jsonFetch<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, { headers: authHeaders(), ...init });
  if (!res.ok) {
    const t = await res.text().catch(() => res.statusText);
    throw new Error(t || `HTTP ${res.status}`);
  }
  if (res.status === 204) return undefined as unknown as T;
  return res.json() as Promise<T>;
}

const apiList   = () => jsonFetch<UserBinding[]>('/api/user/bindings');
const apiAdd    = (b: UserBinding) => jsonFetch<UserBinding>('/api/user/bindings', { method: 'POST', body: JSON.stringify(b) });
const apiUpdate = (i: number, b: UserBinding) => jsonFetch<UserBinding>(`/api/user/bindings/${i}`, { method: 'PUT', body: JSON.stringify(b) });
const apiRemove = (i: number) => jsonFetch<{ removed: boolean }>(`/api/user/bindings/${i}`, { method: 'DELETE' });

const S: Record<string, React.CSSProperties> = {
  content: { padding: '2rem 1.5rem', maxWidth: 900, margin: '0 auto' },
  title:   { fontSize: '1.1rem', fontWeight: 700, color: 'var(--da-text)', marginBottom: 12 },
  intro:   { color: '#7c8bad', fontSize: '0.82rem', marginBottom: 18, lineHeight: 1.6 },
  toolbar: { display: 'flex', gap: 10, marginBottom: 14, alignItems: 'center' },
  addBtn:  { background: 'var(--da-primary)', color: '#fff', border: 'none', borderRadius: 7, padding: '7px 16px', cursor: 'pointer', fontSize: '0.85rem', fontWeight: 600 },
  err:     { color: 'var(--da-danger)', fontSize: '0.82rem', background: '#1f1520', border: '1px solid #5b2030', borderRadius: 8, padding: '8px 12px', marginBottom: 12 },
  ok:      { color: 'var(--da-success)', fontSize: '0.82rem', background: '#0d1f14', border: '1px solid #166534', borderRadius: 8, padding: '8px 12px', marginBottom: 12 },
  table:   { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.85rem' },
  th:      { textAlign: 'left' as const, padding: '0.5rem 0.75rem', background: 'var(--da-surface)', color: '#7c8bad', borderBottom: '1px solid var(--da-border)', fontWeight: 600 },
  td:      { padding: '0.55rem 0.75rem', borderBottom: '1px solid var(--da-border)', color: '#94a3b8' },
  mono:    { fontFamily: 'monospace', fontSize: '0.78rem' },
  editBtn: { background: 'var(--da-border)', border: 'none', color: 'var(--da-primary)', borderRadius: 4, padding: '3px 10px', cursor: 'pointer', fontSize: '0.78rem', marginRight: 4 },
  delBtn:  { background: 'transparent', border: '1px solid #5b2030', color: 'var(--da-danger)', borderRadius: 4, padding: '3px 9px', cursor: 'pointer', fontSize: '0.78rem' },
  modal:   { position: 'fixed' as const, inset: 0, background: 'rgba(0,0,0,0.72)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 },
  modalBox:{ background: '#1a1d27', border: '1px solid var(--da-border-strong)', borderRadius: 10, padding: '1.5rem', width: 520 },
  modalTitle: { fontSize: '1rem', fontWeight: 700, color: 'var(--da-text)', marginBottom: 14 },
  label:   { display: 'block', fontSize: '0.78rem', color: '#94a3b8', fontWeight: 500, marginBottom: 4 },
  hint:    { display: 'block', fontSize: '0.7rem', color: 'var(--da-text-muted)', marginTop: -8, marginBottom: 10 },
  input:   { width: '100%', boxSizing: 'border-box' as const, padding: '7px 10px', background: 'var(--da-surface)', border: '1px solid var(--da-border-strong)', borderRadius: 6, color: 'var(--da-text)', fontSize: '0.85rem', marginBottom: 12 },
  btnRow:  { display: 'flex', gap: 8, marginTop: 4 },
  saveBtn: { background: 'var(--da-primary)', color: '#fff', border: 'none', borderRadius: 7, padding: '8px 20px', cursor: 'pointer', fontSize: '0.85rem', fontWeight: 600 },
  cancelBtn:{ background: 'transparent', border: '1px solid var(--da-border-strong)', color: '#7c8bad', borderRadius: 7, padding: '8px 14px', cursor: 'pointer', fontSize: '0.85rem' },
};

interface PreferenceFormProps {
  initial?: { index: number; data: UserBinding };
  onSaved: () => void;
  onClose: () => void;
}

function PreferenceFormModal({ initial, onSaved, onClose }: PreferenceFormProps) {
  const isEdit = !!initial;
  const [channelId,    setChannelId]    = useState(initial?.data.channelId ?? 'chatui');
  const [displayLabel, setDisplayLabel] = useState(initial?.data.displayLabel ?? '');
  const [sessionScope, setSessionScope] = useState(initial?.data.sessionScope ?? '');
  const [language,     setLanguage]     = useState(initial?.data.language ?? '');
  const [skillsText,   setSkillsText]   = useState((initial?.data.enabledSkills ?? []).join(', '));
  const [err,          setErr]          = useState<string | null>(null);
  const [saving,       setSaving]       = useState(false);

  async function submit() {
    setErr(null); setSaving(true);
    try {
      const skills = skillsText
        .split(',')
        .map(s => s.trim())
        .filter(s => s.length > 0);
      const b: UserBinding = {
        channelId: channelId.trim(),
        displayLabel: displayLabel.trim() || null,
        sessionScope: sessionScope.trim() || null,
        language: language.trim() || null,
        enabledSkills: skills.length > 0 ? skills : null,
      };
      if (isEdit) await apiUpdate(initial!.index, b);
      else        await apiAdd(b);
      onSaved();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={S.modal} onClick={onClose}>
      <div style={S.modalBox} onClick={e => e.stopPropagation()}>
        <div style={S.modalTitle}>{isEdit ? 'Edit Preference' : 'New Channel Preference'}</div>
        {err && <div style={S.err}>{err}</div>}

        <label style={S.label}>通道 ID</label>
        <input style={S.input} value={channelId} onChange={e => setChannelId(e.target.value)} placeholder="chatui" />
        <span style={S.hint}>Use <code>chatui</code> for the web UI; other ids match integrations (e.g. <code>slack</code>).</span>

        <label style={S.label}>Display label (optional)</label>
        <input style={S.input} value={displayLabel} onChange={e => setDisplayLabel(e.target.value)} placeholder="我的默认会话" />

        <label style={S.label}>Session scope (optional)</label>
        <input style={S.input} value={sessionScope} onChange={e => setSessionScope(e.target.value)} placeholder="MAIN" />
        <span style={S.hint}>Hint used by the chat UI to derive a stable session key (default: <code>MAIN</code>).</span>

        <label style={S.label}>Reply language (optional, BCP-47)</label>
        <input style={S.input} value={language} onChange={e => setLanguage(e.target.value)} placeholder="zh-CN" />
        <span style={S.hint}>If set, DataAgent is asked to reply in this language.</span>

        <label style={S.label}>Enabled skills (optional, comma-separated)</label>
        <input style={S.input} value={skillsText} onChange={e => setSkillsText(e.target.value)} placeholder="sql-analysis, chart-rendering" />
        <span style={S.hint}>If empty, all skills under <code>skills/</code> are available.</span>

        <div style={S.btnRow}>
          <button style={S.saveBtn} disabled={saving || !channelId.trim()} onClick={submit}>
            {saving ? 'Saving…' : isEdit ? 'Update' : 'Add'}
          </button>
          <button style={S.cancelBtn} onClick={onClose}>取消</button>
        </div>
      </div>
    </div>
  );
}

export default function UserBindingsPage() {
  const [bindings, setBindings] = useState<UserBinding[]>([]);
  const [err,      setErr]      = useState<string | null>(null);
  const [ok,       setOk]       = useState<string | null>(null);
  const [editing,  setEditing]  = useState<{ index: number; data: UserBinding } | 'new' | null>(null);
  const [loading,  setLoading]  = useState(true);

  async function load() {
    setLoading(true); setErr(null);
    try {
      setBindings(await apiList());
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  async function onDelete(i: number) {
    if (!confirm('Delete this preference?')) return;
    try {
      await apiRemove(i);
      setOk('偏好已删除');
      await load();
      setTimeout(() => setOk(null), 3000);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <>
      <div style={S.content}>
        <h2 style={S.title}>通道偏好</h2>
        <p style={S.intro}>
          DataAgent answers every chat — these settings just shape <em>how</em> it answers on a
          given channel. Set a reply language, restrict which skills are loaded, or override the
          default session scope. Preferences apply only to your account.
        </p>

        <div style={S.toolbar}>
          <button style={S.addBtn} onClick={() => setEditing('new')}>+ New Preference</button>
        </div>

        {err && <div style={S.err}>{err}</div>}
        {ok  && <div style={S.ok}>{ok}</div>}

        {loading && <p style={{ color: '#7c8bad' }}>Loading…</p>}

        {!loading && bindings.length === 0 && (
          <p style={{ color: '#7c8bad', fontSize: '0.85rem' }}>
            No channel preferences yet — DataAgent uses defaults for every channel.
          </p>
        )}

        {!loading && bindings.length > 0 && (
          <table style={S.table}>
            <thead>
              <tr>
                <th style={S.th}>#</th>
                <th style={S.th}>通道</th>
                <th style={S.th}>标签</th>
                <th style={S.th}>语言</th>
                <th style={S.th}>作用域</th>
                <th style={S.th}>技能</th>
                <th style={S.th}>操作</th>
              </tr>
            </thead>
            <tbody>
              {bindings.map((b, i) => (
                <tr key={i}>
                  <td style={S.td}>{i}</td>
                  <td style={{ ...S.td, ...S.mono }}>{b.channelId}</td>
                  <td style={S.td}>{b.displayLabel ?? '—'}</td>
                  <td style={{ ...S.td, ...S.mono }}>{b.language ?? '—'}</td>
                  <td style={{ ...S.td, ...S.mono }}>{b.sessionScope ?? '—'}</td>
                  <td style={{ ...S.td, ...S.mono }}>
                    {b.enabledSkills && b.enabledSkills.length > 0
                      ? b.enabledSkills.join(', ')
                      : '(all)'}
                  </td>
                  <td style={S.td}>
                    <button style={S.editBtn} onClick={() => setEditing({ index: i, data: b })}>编辑</button>
                    <button style={S.delBtn} onClick={() => onDelete(i)}>删除</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {editing && (
        <PreferenceFormModal
          initial={editing === 'new' ? undefined : editing}
          onSaved={() => { setEditing(null); load(); setOk('Saved.'); setTimeout(() => setOk(null), 3000); }}
          onClose={() => setEditing(null)}
        />
      )}
    </>
  );
}
