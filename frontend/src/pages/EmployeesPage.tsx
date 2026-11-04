import { useState } from 'react'
import client, { apiError } from '../api/client'
import DataTable from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead, { pageActionBtn } from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { unitName } from '../utils/format'
import type { Employee, Reference, Sku } from '../types'

/**
 * Сотрудники. Жёсткого справочника бригад нет: состав фиксируется конкретными
 * людьми на работе и выезде (US-A5). Шаблон состава — только подсказка.
 */
export default function EmployeesPage() {
  const { showToast } = useToast()
  const [includeInactive, setIncludeInactive] = useState(false)
  const [editing, setEditing] = useState<Employee | 'new' | null>(null)

  const { data, loading, reload } =
    useApi<Employee[]>(`/api/employees?includeInactive=${includeInactive}`, [includeInactive])
  const { data: roles } = useApi<Reference[]>('/api/references/employee-roles')
  const { data: skus } = useApi<Sku[]>('/api/sku')

  return (
    <>
      <PageHead
        title="Сотрудники"
        right={
          <>
            <label className="toggle-box">
              <input type="checkbox" checked={includeInactive}
                     onChange={e => setIncludeInactive(e.target.checked)} />
              Показать уволенных
            </label>
            <button className="btn-primary" style={pageActionBtn} onClick={() => setEditing('new')}>
              + Сотрудник
            </button>
          </>
        }
      />
      <DataTable
        columns={[
          { key: 'full_name', title: 'ФИО' },
          { key: 'short_name', title: 'Как в таблицах', width: 200,
            render: e => e.short_name || '—' },
          { key: 'role_name', title: 'Роль', width: 200, render: e => e.role_name || '—' },
          { key: 'phone', title: 'Телефон', width: 160, render: e => e.phone || '—' },
          { key: 'skus', title: 'Специализации', width: 120, align: 'right',
            render: e => e.sku_ids.length || '—' },
          { key: 'is_active', title: 'Активен', width: 110, render: e => e.is_active ? 'да' : 'нет' },
          { key: 'actions', title: '', width: 100,
            render: e => (
              <button className="btn-secondary btn-sm" onClick={() => setEditing(e)}>Правка</button>
            ) },
        ]}
        rows={data || []}
        loading={loading}
        empty="Сотрудников нет"
      />
      {editing && (
        <EmployeeModal
          value={editing === 'new' ? null : editing}
          roles={roles || []}
          skus={skus || []}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); showToast('Сохранено'); reload() }}
        />
      )}
    </>
  )
}

function EmployeeModal({ value, roles, skus, onClose, onDone }: {
  value: Employee | null; roles: Reference[]; skus: Sku[]
  onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    full_name: value?.full_name ?? '',
    short_name: value?.short_name ?? '',
    role_code: value?.role_code ?? null,
    position: value?.position ?? '',
    phone: value?.phone ?? '',
    email: value?.email ?? '',
    is_active: value?.is_active ?? true,
    note: value?.note ?? '',
    sku_ids: value?.sku_ids ?? [],
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.full_name.trim()) { setError('ФИО обязательно'); return }
    try {
      if (value) await client.put(`/api/employees/${value.id}`, form)
      else await client.post('/api/employees', form)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title={value ? 'Сотрудник' : 'Новый сотрудник'} size="large" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <div className="form-row">
        <Field label="ФИО" required>
          <input value={form.full_name} onChange={e => set('full_name', e.target.value)} />
        </Field>
        <Field label="Короткое имя"
               hint="Как человек записан в действующих таблицах: «Мухтар», «Никита К»">
          <input value={form.short_name ?? ''} onChange={e => set('short_name', e.target.value)} />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Роль">
          <StyledSelect value={form.role_code}
                        options={roles.map(r => ({ value: r.code, label: r.name }))}
                        onChange={v => set('role_code', v)} allowEmpty />
        </Field>
        <Field label="Должность">
          <input value={form.position ?? ''} onChange={e => set('position', e.target.value)} />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Телефон"><input value={form.phone ?? ''} onChange={e => set('phone', e.target.value)} /></Field>
        <Field label="E-mail"><input value={form.email ?? ''} onChange={e => set('email', e.target.value)} /></Field>
      </div>
      <Field label="Специализации" hint="Подсказка при назначении исполнителей на работу">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {skus.map(s => {
            const on = form.sku_ids.includes(s.id)
            return (
              <button key={s.id} type="button"
                      className={`chip badge ${on ? 'badge-info' : 'badge-neutral'}`}
                      onClick={() => set('sku_ids', on
                        ? form.sku_ids.filter(x => x !== s.id) : [...form.sku_ids, s.id])}>
                {s.name} <span className="muted"> · {unitName(s.unit_code)}</span>
              </button>
            )
          })}
        </div>
      </Field>
      <label className="toggle-box">
        <input type="checkbox" checked={form.is_active} onChange={e => set('is_active', e.target.checked)} />
        Активен
      </label>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
