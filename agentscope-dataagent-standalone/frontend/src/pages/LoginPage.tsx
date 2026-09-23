import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login, saveToken } from '../api/auth';

/**
 * Split-brand login: a deep-blue gradient brand pane (product name, tagline, capability bullets)
 * beside a clean surface form — an editorial enterprise opening rather than a lone centered box.
 */
export default function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const res = await login(username, password);
      saveToken(res.token);
      navigate('/chat', { replace: true });
    } catch {
      setError('用户名或密码不正确');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'flex', background: 'var(--da-app-bg)' }}>
      <div className="da-brandpane">
        <div className="da-enter" style={{ position: 'relative', zIndex: 1 }}>
          <span style={{ fontSize: 12, fontWeight: 600, letterSpacing: '0.06em', opacity: 0.8 }}>
            红海DataAgent
          </span>
          <h1
            style={{
              margin: '12px 0 0',
              fontSize: 40,
              lineHeight: 1.2,
              fontWeight: 700,
              letterSpacing: '-0.02em',
            }}
          >
            你的大数据智囊团
          </h1>
          <p style={{ margin: '16px 0 0', fontSize: 15, lineHeight: 1.7, opacity: 0.85, maxWidth: 420 }}>
            连接企业数据源与知识文档，用自然语言完成查询、分析与可视化；口径与图谱让每一次回答都可溯源。
          </p>
          <ul
            style={{
              margin: '28px 0 0',
              padding: 0,
              listStyle: 'none',
              display: 'flex',
              flexDirection: 'column',
              gap: 10,
              fontSize: 13.5,
              opacity: 0.9,
            }}
          >
            <li>· 知识库：表格 / 文本随意传，数据知识一起用</li>
            <li>· 语义图谱：实体与关系自动构建，问数先查口径</li>
            <li>· 多租户隔离：每人只见自己的数据与知识</li>
          </ul>
        </div>
      </div>

      <div
        style={{
          width: 440,
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: 32,
        }}
      >
        <form
          className="da-enter"
          onSubmit={handleSubmit}
          style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 18 }}
        >
          <div>
            <span className="da-eyebrow">Sign in</span>
            <div className="da-page-title" style={{ fontSize: 22 }}>
              登录红海DataAgent
            </div>
          </div>
          <div>
            <label className="da-label">用户名</label>
            <input
              className="da-input"
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              autoFocus
              autoComplete="username"
              placeholder="请输入用户名"
            />
          </div>
          <div>
            <label className="da-label">密码</label>
            <input
              className="da-input"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="current-password"
              placeholder="请输入密码"
            />
          </div>
          {error && <div style={{ color: 'var(--da-danger)', fontSize: 13 }}>{error}</div>}
          <button
            className="da-btn da-btn-primary"
            type="submit"
            disabled={loading}
            style={{ padding: '10px 12px', fontSize: 14 }}
          >
            {loading ? '登录中…' : '登 录'}
          </button>
          <div className="da-small" style={{ textAlign: 'center' }}>
            默认账号 admin / admin，登录后请及时修改
          </div>
        </form>
      </div>
    </div>
  );
}
