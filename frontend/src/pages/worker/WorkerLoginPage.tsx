import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import client, { apiError } from '../../api/client'

/** Вход исполнителя по PIN: на объекте вводить логин и пароль неудобно. */
export default function WorkerLoginPage() {
  const navigate = useNavigate()
  const [pin, setPin] = useState('')
  const [error, setError] = useState('')

  const submit = async () => {
    if (!pin.trim()) { setError('Введите PIN'); return }
    try {
      const res = await client.post('/api/worker/login', { pin: pin.trim() })
      sessionStorage.setItem('worker_employee_id', String(res.data.employee_id))
      sessionStorage.setItem('worker_name', String(res.data.display_name))
      navigate('/worker')
    } catch (e) {
      setError(apiError(e, 'Неверный PIN'))
    }
  }

  return (
    <div style={{
      display: 'flex', justifyContent: 'center', alignItems: 'center',
      minHeight: '100vh', background: '#f0f2f5', padding: 16,
    }}>
      <div style={{
        background: '#fff', padding: 32, borderRadius: 12,
        boxShadow: '0 4px 20px rgba(0,0,0,0.1)', width: '100%', maxWidth: 360,
      }}>
        <div style={{ textAlign: 'center', marginBottom: 20 }}>
          <div style={{ fontSize: 22, fontWeight: 700 }}>Кабинет исполнителя</div>
          <div className="small muted">Введите PIN, выданный прорабом</div>
        </div>
        <div className="form-group">
          <label>PIN</label>
          <input
            inputMode="numeric"
            value={pin}
            onChange={e => setPin(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && submit()}
            style={{ fontSize: 22, textAlign: 'center', letterSpacing: 6, height: 52 }}
            autoFocus
          />
        </div>
        {error && <div className="error-msg" style={{ marginBottom: 12 }}>{error}</div>}
        <button className="btn-primary" onClick={submit} style={{ width: '100%', height: 44 }}>
          Войти
        </button>
        <div style={{ textAlign: 'center', marginTop: 14 }}>
          <button type="button" onClick={() => navigate('/login')}
                  style={{ background: 'none', border: 'none', color: '#3498db',
                           fontSize: 'var(--font-sm)', textDecoration: 'underline' }}>
            ← Вход в основную систему
          </button>
        </div>
      </div>
    </div>
  )
}
