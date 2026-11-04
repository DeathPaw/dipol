import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

export default function LoginPage() {
  const navigate = useNavigate()
  const { refresh } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const submit = async () => {
    if (!username.trim() || !password.trim()) {
      setError('Введите логин и пароль')
      return
    }
    setLoading(true)
    setError('')
    const token = btoa(unescape(encodeURIComponent(`${username}:${password}`)))
    try {
      const response = await fetch('/api/me', { headers: { Authorization: `Basic ${token}` } })
      if (response.status === 401) {
        setError('Неверный логин или пароль')
        setLoading(false)
        return
      }
      sessionStorage.setItem('auth', token)
      await refresh()
      navigate('/dashboard')
    } catch {
      setError('Ошибка подключения к серверу')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{
      display: 'flex', justifyContent: 'center', alignItems: 'center',
      minHeight: '100vh', background: '#f0f2f5',
    }}>
      <div style={{
        background: '#fff', padding: 40, borderRadius: 12,
        boxShadow: '0 4px 20px rgba(0,0,0,0.1)', width: 380,
      }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <div style={{ fontSize: 26, fontWeight: 700, letterSpacing: 1 }}>ERP «ДИПОЛЬ»</div>
          <div style={{ fontSize: 'var(--font-sm)', color: '#888', marginTop: 4 }}>
            Заявки, выезды, материалы, ФОТ и документы
          </div>
        </div>

        <div className="form-group">
          <label>Логин</label>
          <input
            value={username}
            onChange={e => setUsername(e.target.value)}
            placeholder="Введите логин"
            onKeyDown={e => e.key === 'Enter' && submit()}
            autoFocus
          />
        </div>
        <div className="form-group">
          <label>Пароль</label>
          <input
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            placeholder="Введите пароль"
            onKeyDown={e => e.key === 'Enter' && submit()}
          />
        </div>

        {error && <div className="error-msg" style={{ marginBottom: 12 }}>{error}</div>}

        <button
          className="btn-primary"
          onClick={submit}
          disabled={loading}
          style={{ width: '100%' }}
        >
          {loading ? 'Вход…' : 'Войти'}
        </button>

        <div style={{ textAlign: 'center', marginTop: 16 }}>
          <button
            type="button"
            onClick={() => navigate('/worker-login')}
            style={{
              background: 'transparent', border: 'none', color: '#3498db',
              fontSize: 'var(--font-sm)', cursor: 'pointer', textDecoration: 'underline',
            }}
          >
            Вход для исполнителя по PIN →
          </button>
        </div>
      </div>
    </div>
  )
}
