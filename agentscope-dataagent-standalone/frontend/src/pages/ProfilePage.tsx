import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { UserView, getProfile, changePassword } from '../api/users';

// ── Usage types ──────────────────────────────────────────────────────
interface UsageSummary { totalTurns: number; todayTurns: number; avgDurationMs: number; }
interface BucketCount  { epochMs: number; label: string; count: number; }

function authHeader(): Record<string, string> {
  const t = localStorage.getItem('claw_token') ?? '';
  return { Authorization: `Bearer ${t}` };
}

async function fetchMyUsage(): Promise<UsageSummary> {
  const r = await fetch('/api/usage/me/summary', { headers: authHeader() });
  if (!r.ok) throw new Error('Failed to fetch usage');
  return r.json();
}

async function fetchMyDaily(days = 7): Promise<BucketCount[]> {
  const r = await fetch(`/api/usage/me/daily?days=${days}`, { headers: authHeader() });
  if (!r.ok) throw new Error('Failed to fetch daily usage');
  return r.json();
}

// ── Mini sparkline (SVG bar chart) ───────────────────────────────────
function Sparkline({ data }: { data: BucketCount[] }) {
  if (!data.length) return null;
  const max = Math.max(...data.map(d => d.count), 1);
  const W = 220, H = 48, pad = 2;
  const barW = (W - pad * (data.length - 1)) / data.length;

  return (
    <svg width={W} height={H} style={{ display: 'block' }}>
      {data.map((d, i) => {
        const h = Math.max(2, (d.count / max) * H);
        const x = i * (barW + pad);
        const y = H - h;
        return (
          <g key={d.epochMs}>
            <rect x={x} y={y} width={barW} height={h}
              fill={d.count > 0 ? 'var(--da-primary)' : 'var(--da-border)'} rx={2} />
            {i === data.length - 1 && d.count > 0 && (
              <text x={x + barW / 2} y={y - 3} textAnchor="middle"
                fontSize={9} fill="var(--da-primary)">{d.count}</text>
            )}
          </g>
        );
      })}
    </svg>
  );
}

// ── Styles ───────────────────────────────────────────────────────────
const S: Record<string, React.CSSProperties> = {
  page:  { padding: '28px 32px', maxWidth: 700 },
  title: { margin: '0 0 24px', fontSize: '1.15rem', fontWeight: 700, color: 'var(--da-text)' },
  grid:  { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 20 },
  card:  {
    background: 'var(--da-surface)', border: '1px solid var(--da-border)', borderRadius: 12,
    padding: '20px 22px',
  },
  cardFull: {
    background: 'var(--da-surface)', border: '1px solid var(--da-border)', borderRadius: 12,
    padding: '20px 22px', marginBottom: 16,
  },
  cardLabel: { fontSize: '0.72rem', color: 'var(--da-text-muted)', fontWeight: 700,
    textTransform: 'uppercase' as const, letterSpacing: '0.07em', marginBottom: 10, display: 'block' },
  stat:  { fontSize: '2rem', fontWeight: 800, color: 'var(--da-primary)', lineHeight: 1 },
  statSub: { fontSize: '0.75rem', color: 'var(--da-text-3)', marginTop: 4 },
  row:   { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 },
  rowLabel: { width: 110, fontSize: '0.8rem', color: 'var(--da-text-3)', flexShrink: 0 },
  rowValue: { fontSize: '0.88rem', color: 'var(--da-text)' },
  badgeBase: {
    display: 'inline-block', padding: '2px 9px', borderRadius: 12, fontSize: '0.72rem',
    fontWeight: 600,
  } as React.CSSProperties,
  fieldLabel: { display: 'block', fontSize: '0.78rem', fontWeight: 500, color: '#94a3b8', marginBottom: 5 },
  input: {
    width: '100%', boxSizing: 'border-box' as const, padding: '8px 11px',
    background: 'var(--da-surface)', border: '1px solid var(--da-border-strong)', borderRadius: 7,
    color: 'var(--da-text)', fontSize: '0.85rem',
  },
  saveBtn: {
    marginTop: 14, padding: '8px 20px', background: 'var(--da-primary)', color: '#fff',
    border: 'none', borderRadius: 8, cursor: 'pointer', fontSize: '0.85rem', fontWeight: 600,
  },
  success: { color: 'var(--da-success)', fontSize: '0.8rem', marginTop: 8 },
  error:   { color: 'var(--da-danger)', fontSize: '0.8rem', marginTop: 8 },
  link: {
    color: 'var(--da-primary)', fontSize: '0.8rem', cursor: 'pointer',
    background: 'none', border: 'none', padding: 0, textDecoration: 'underline',
  },
};

export default function ProfilePage() {
  const navigate = useNavigate();
  const [profile,  setProfile]  = useState<UserView | null>(null);
  const [usage,    setUsage]    = useState<UsageSummary | null>(null);
  const [daily,    setDaily]    = useState<BucketCount[]>([]);
  const [loadErr,  setLoadErr]  = useState<string | null>(null);

  const [curPwd,   setCurPwd]   = useState('');
  const [newPwd,   setNewPwd]   = useState('');
  const [conPwd,   setConPwd]   = useState('');
  const [pwdErr,   setPwdErr]   = useState<string | null>(null);
  const [pwdOk,    setPwdOk]    = useState(false);

  useEffect(() => {
    getProfile().then(setProfile).catch(e => setLoadErr(e.message));
    fetchMyUsage().then(setUsage).catch(() => {});
    fetchMyDaily(7).then(setDaily).catch(() => {});
  }, []);

  async function handleChangePwd() {
    setPwdErr(null); setPwdOk(false);
    if (newPwd.length < 6) { setPwdErr('密码至少 6 位'); return; }
    if (newPwd !== conPwd) { setPwdErr('两次输入的密码不一致'); return; }
    try {
      await changePassword(curPwd, newPwd);
      setPwdOk(true); setCurPwd(''); setNewPwd(''); setConPwd('');
    } catch (e: unknown) {
      setPwdErr(e instanceof Error ? e.message : 'Error');
    }
  }

  const fmt = (ms: number) =>
    ms < 1000 ? `${ms}ms` : ms < 60_000 ? `${(ms / 1000).toFixed(1)}s` : `${(ms / 60_000).toFixed(1)}m`;

  return (
    <>
      <div style={S.page}>
        <h2 style={S.title}>个人资料</h2>

        {loadErr && <p style={S.error}>{loadErr}</p>}

        {/* Account info + Usage stats grid */}
        <div style={S.grid}>

          {/* Account card */}
          <div style={S.card}>
            <span style={S.cardLabel}>账号信息</span>
            {profile && (
              <>
                <div style={S.row}>
                  <span style={S.rowLabel}>用户名</span>
                  <span style={{ ...S.rowValue, fontWeight: 600 }}>{profile.username}</span>
                </div>
                <div style={S.row}>
                  <span style={S.rowLabel}>用户 ID</span>
                  <span style={{ ...S.rowValue, fontFamily: 'monospace', fontSize: '0.78rem', color: 'var(--da-text-3)' }}>{profile.userId}</span>
                </div>
                <div style={S.row}>
                  <span style={S.rowLabel}>角色</span>
                  <span>
                    {profile.roles.map(r => (
                      <span key={r} style={{ ...S.badgeBase, background: r === 'admin' ? 'var(--da-primary-subtle)' : 'var(--da-border)', color: r === 'admin' ? 'var(--da-primary)' : 'var(--da-text-3)' }}>{r}</span>
                    ))}
                  </span>
                </div>
                {profile.roles.includes('admin') && (
                  <button style={S.link} onClick={() => navigate('/admin/overview')}>
                    进入管理控制台 →
                  </button>
                )}
              </>
            )}
          </div>

          {/* Usage stats card */}
          <div style={S.card}>
            <span style={S.cardLabel}>我的用量</span>
            {usage ? (
              <>
                <div style={{ display: 'flex', gap: 24, marginBottom: 12 }}>
                  <div>
                    <div style={S.stat}>{usage.totalTurns}</div>
                    <div style={S.statSub}>总对话数</div>
                  </div>
                  <div>
                    <div style={S.stat}>{usage.todayTurns}</div>
                    <div style={S.statSub}>今日</div>
                  </div>
                  {usage.avgDurationMs > 0 && (
                    <div>
                      <div style={{ ...S.stat, fontSize: '1.4rem' }}>{fmt(usage.avgDurationMs)}</div>
                      <div style={S.statSub}>平均响应</div>
                    </div>
                  )}
                </div>
                {daily.length > 0 && (
                  <>
                    <div style={{ fontSize: '0.7rem', color: 'var(--da-text-muted)', marginBottom: 4 }}>近 7 天</div>
                    <Sparkline data={daily} />
                  </>
                )}
              </>
            ) : (
              <div style={{ color: 'var(--da-text-muted)', fontSize: '0.8rem' }}>暂无用量数据</div>
            )}
          </div>
        </div>

        {/* Change password card */}
        <div style={S.cardFull}>
          <span style={S.cardLabel}>修改密码</span>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
            <div>
              <label style={S.fieldLabel}>当前密码</label>
              <input style={S.input} type="password" value={curPwd}
                onChange={e => setCurPwd(e.target.value)} placeholder="当前密码" />
            </div>
            <div>
              <label style={S.fieldLabel}>新密码</label>
              <input style={S.input} type="password" value={newPwd}
                onChange={e => setNewPwd(e.target.value)} placeholder="至少 6 位" />
            </div>
            <div>
              <label style={S.fieldLabel}>确认新密码</label>
              <input style={S.input} type="password" value={conPwd}
                onChange={e => setConPwd(e.target.value)} placeholder="重复新密码" />
            </div>
          </div>
          {pwdErr && <p style={S.error}>{pwdErr}</p>}
          {pwdOk  && <p style={S.success}>密码修改成功</p>}
          <button style={S.saveBtn} onClick={handleChangePwd}>更新密码</button>
        </div>
      </div>
    </>
  );
}
