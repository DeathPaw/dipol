import { useState } from 'react'
import client, { apiError } from '../api/client'
import DataTable from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead, { pageActionBtn } from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import type { Employee, Row } from '../types'

const ROLES: Record<string, string> = {
  SUPERVISOR: 'Руководитель',
  ADMIN: 'Администратор системы',
  OPERATOR: 'Оператор',
  FOREMAN: 'Прораб/мастер',
  WORKER: 'Рабочий',
  SAFETY: 'Техник по безопасности',
  READONLY: 'Только просмотр',
}

/**
 * Пользователи и права. Роль даёт базовый набор прав, отдельные права
 * (снабжение, расчёты, закрытие периода) выдаются точечно — без создания
 * новой роли под каждую комбинацию (US-N1).
 */
export default function UsersPage() {
  const { showToast } = useToast()
  const [editing, setEditing] = useState<Row | 'new' | null>(null)
  const [permissionsFor, setPermissionsFor] = useState<Row | null>(null)

  const { data, loading, reload } = useApi<Row[]>('/api/users')
  const { data: employees } = useApi<Employee[]>('/api/employees')

  return (
    <>
      <PageHead
        title="Пользователи"
        right={
          <button className="btn-primary" style={pageActionBtn} onClick={() => setEditing('new')}>
            + Пользователь
          </button>
        }
      />
      <DataTable
        columns={[
          { key: 'username', title: 'Логин', width: 180 },
          { key: 'display_name', title: 'Имя' },
          { key: 'role', title: 'Роль', width: 230, render: u => ROLES[u.role] || u.role },
          { key: 'employee_id', title: 'Сотрудник', width: 220,
            render: u => employees?.find(e => e.id === u.employee_id)?.full_name || '—' },
          { key: 'has_pin', title: 'PIN', width: 90, render: u => u.has_pin ? 'задан' : '—' },
          { key: 'is_active', title: 'Активен', width: 110, render: u => u.is_active ? 'да' : 'нет' },
          { key: 'actions', title: '', width: 190,
            render: u => (
              <div className="actions">
                <button className="btn-secondary btn-sm" onClick={() => setPermissionsFor(u)}>Права</button>
                <button className="btn-secondary btn-sm" onClick={() => setEditing(u)}>Правка</button>
              </div>
            ) },
        ]}
        rows={data || []}
        loading={loading}
        empty="Пользователей нет"
      />
      {editing && (
        <UserModal value={editing === 'new' ? null : editing} employees={employees || []}
                   onClose={() => setEditing(null)}
                   onDone={() => { setEditing(null); showToast('Сохранено'); reload() }} />
      )}
      {permissionsFor && (
        <PermissionsModal user={permissionsFor} onClose={() => setPermissionsFor(null)} />
      )}
    </>
  )
}

function UserModal({ value, employees, onClose, onDone }: {
  value: Row | null; employees: Employee[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    username: value?.username ?? '',
    password: '',
    display_name: value?.display_name ?? '',
    role: value?.role ?? 'OPERATOR',
    employee_id: value?.employee_id ?? null,
    pin_code: '',
    is_active: value?.is_active ?? true,
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    try {
      if (value) {
        await client.put(`/api/users/${value.id}`, form)
        if (form.password) {
          await client.post(`/api/users/${value.id}/password`, { password: form.password })
        }
      } else {
        await client.post('/api/users', form)
      }
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title={value ? 'Пользователь' : 'Новый пользователь'} onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <div className="form-row">
        <Field label="Логин" required>
          <input value={form.username} onChange={e => set('username', e.target.value)}
                 disabled={!!value} />
        </Field>
        <Field label={value ? 'Новый пароль' : 'Пароль'} required={!value}
               hint={value ? 'Оставьте пустым, чтобы не менять' : undefined}>
          <input type="password" value={form.password} onChange={e => set('password', e.target.value)} />
        </Field>
      </div>
      <Field label="Отображаемое имя" required>
        <input value={form.display_name} onChange={e => set('display_name', e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="Роль" required>
          <StyledSelect value={form.role}
                        options={Object.entries(ROLES).map(([v, label]) => ({ value: v, label }))}
                        onChange={v => set('role', v)} />
        </Field>
        <Field label="Сотрудник" hint="Связь нужна для мобильного кабинета и своего ФОТ">
          <StyledSelect value={form.employee_id as number | null}
                        options={employees.map(e => ({ value: e.id, label: e.full_name }))}
                        onChange={v => set('employee_id', v)} allowEmpty />
        </Field>
      </div>
      {form.role === 'WORKER' && (
        <Field label="PIN для мобильного кабинета" hint="4–6 цифр, вход без логина и пароля">
          <input value={form.pin_code} onChange={e => set('pin_code', e.target.value)} />
        </Field>
      )}
      <label className="toggle-box">
        <input type="checkbox" checked={form.is_active} onChange={e => set('is_active', e.target.checked)} />
        Активен
      </label>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function PermissionsModal({ user, onClose }: { user: Row; onClose: () => void }) {
  const { showToast } = useToast()
  const { data: catalog } = useApi<{ catalog: Row[] }>('/api/users/permissions')
  const { data: granted, reload } = useApi<Row[]>(`/api/users/${user.id}/permissions`, [user.id])

  const setPermission = async (code: string, value: boolean | null) => {
    await client.put(`/api/users/${user.id}/permissions/${code}`, { granted: value })
    showToast('Права обновлены')
    reload()
  }

  const bySection = (catalog?.catalog || []).reduce<Record<string, Row[]>>((acc, p) => {
    (acc[p.section] = acc[p.section] || []).push(p)
    return acc
  }, {})

  return (
    <Modal title={`Права: ${user.display_name}`} size="large" onClose={onClose}
           actions={<button className="btn-secondary" onClick={onClose}>Закрыть</button>}>
      <div className="notice notice-info">
        Роль <b>{ROLES[user.role] || user.role}</b> уже даёт базовый набор прав. Здесь можно
        выдать дополнительное право или явно отозвать то, что даёт роль.
      </div>
      {Object.entries(bySection).map(([section, items]) => (
        <div key={section} className="card">
          <h3>{section}</h3>
          {items.map(p => {
            const override = granted?.find(g => g.permission_code === p.code)
            return (
              <div key={p.code} style={{
                display: 'flex', alignItems: 'center', gap: 10, padding: '4px 0',
              }}>
                <span style={{ flex: 1 }}>{p.name}</span>
                <div className="segmented compact">
                  <button className={override === undefined ? 'active' : ''}
                          onClick={() => setPermission(p.code, null)}>По роли</button>
                  <button className={override?.granted === true ? 'active' : ''}
                          onClick={() => setPermission(p.code, true)}>Выдано</button>
                  <button className={override?.granted === false ? 'active' : ''}
                          onClick={() => setPermission(p.code, false)}>Отозвано</button>
                </div>
              </div>
            )
          })}
        </div>
      ))}
    </Modal>
  )
}
