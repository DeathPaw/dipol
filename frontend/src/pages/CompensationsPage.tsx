import { useState } from 'react'
import client, { apiError } from '../api/client'
import DataTable, { type Column } from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead, { pageActionBtn } from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { daysAgo, formatDate, formatRub, today } from '../utils/format'
import type { Employee, Project, Row } from '../types'

/** Путь чека: на руках → в офисе → у бухгалтера → компенсировано (US-J4). */
const FLOW = ['CHECK_ON_HANDS', 'PASSED_TO_OFFICE', 'PASSED_TO_ACCOUNTANT', 'COMPENSATED'] as const

const STATUS_LABELS: Record<string, string> = {
  CHECK_ON_HANDS: 'Чек на руках',
  PASSED_TO_OFFICE: 'Чек передан в офис',
  PASSED_TO_ACCOUNTANT: 'Чек передан бухгалтеру',
  COMPENSATED: 'Компенсировано',
}

export default function CompensationsPage() {
  const { showToast } = useToast()
  const [from, setFrom] = useState(daysAgo(90))
  const [to, setTo] = useState(today())
  const [status, setStatus] = useState<string | null>(null)
  const [employeeId, setEmployeeId] = useState<number | null>(null)
  const [create, setCreate] = useState(false)

  const params = new URLSearchParams({ from, to })
  if (status) params.set('status', status)
  if (employeeId) params.set('employeeId', String(employeeId))

  const { data, loading, reload } =
    useApi<Row[]>(`/api/compensations?${params.toString()}`, [params.toString()])
  const { data: summary } = useApi<Row[]>(`/api/compensations/summary?from=${from}&to=${to}`, [from, to])
  const { data: employees } = useApi<Employee[]>('/api/employees')
  const { data: projects } = useApi<Project[]>('/api/projects')

  const advance = async (row: Row) => {
    const idx = FLOW.indexOf(row.status)
    if (idx < 0 || idx >= FLOW.length - 1) return
    try {
      await client.post(`/api/compensations/${row.id}/status`, { status: FLOW[idx + 1] })
      showToast('Статус чека обновлён')
      reload()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const columns: Column<Row>[] = [
    { key: 'spent_on', title: 'Дата', width: 110, render: c => formatDate(c.spent_on) },
    { key: 'employee_name', title: 'Сотрудник', width: 220 },
    { key: 'project_code', title: 'Проект', width: 100, render: c => c.project_code || '—' },
    { key: 'title', title: 'За что' },
    { key: 'amount', title: 'Сумма', width: 130, align: 'right', render: c => formatRub(c.amount) },
    { key: 'status', title: 'Статус чека', width: 220,
      render: c => (
        <span className={`badge ${c.status === 'COMPENSATED' ? 'badge-ok' : 'badge-warn'}`}>
          {STATUS_LABELS[c.status] || c.status}
        </span>
      ) },
    { key: 'actions', title: '', width: 150,
      render: c => c.status === 'COMPENSATED' ? null : (
        <button className="btn-secondary btn-sm" onClick={() => advance(c)}>
          {STATUS_LABELS[FLOW[FLOW.indexOf(c.status) + 1]] || 'Далее'}
        </button>
      ) },
  ]

  return (
    <>
      <PageHead
        title="Компенсации"
        filters={
          <>
            <input type="date" value={from} onChange={e => setFrom(e.target.value)} style={{ width: 150 }} />
            <input type="date" value={to} onChange={e => setTo(e.target.value)} style={{ width: 150 }} />
            <StyledSelect
              value={status}
              options={Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label }))}
              onChange={setStatus}
              placeholder="Статус: все"
              allowEmpty
              width={220}
            />
          </>
        }
        right={
          <button className="btn-primary" style={pageActionBtn} onClick={() => setCreate(true)}>
            + Компенсация
          </button>
        }
        extra={
          <div className="filters" style={{ marginBottom: 0 }}>
            <div className="form-group" style={{ minWidth: 230 }}>
              <label>Сотрудник</label>
              <StyledSelect
                value={employeeId}
                options={(employees || []).map(e => ({ value: e.id, label: e.full_name }))}
                onChange={v => setEmployeeId(v as number | null)}
                placeholder="Все"
                allowEmpty
              />
            </div>
          </div>
        }
      />

      <div className="notice notice-info">
        Компенсации ведутся отдельно от зарплаты и в отчётах с ней не смешиваются, даже если
        деньги переведены одним платежом.
      </div>

      <div className="tiles">
        {FLOW.map(s => {
          const row = summary?.find(x => x.status === s)
          return (
            <div key={s} className="tile static">
              <div className="tile-value" style={{ fontSize: 18 }}>{formatRub(row?.total ?? 0)}</div>
              <div className="tile-label">{STATUS_LABELS[s]} · {row?.count ?? 0} шт.</div>
            </div>
          )
        })}
      </div>

      <DataTable columns={columns} rows={data || []} loading={loading}
                 empty="Компенсаций за период нет" />

      {create && (
        <CompensationModal
          employees={employees || []}
          projects={projects || []}
          onClose={() => setCreate(false)}
          onDone={() => { setCreate(false); showToast('Компенсация добавлена'); reload() }}
        />
      )}
    </>
  )
}

function CompensationModal({ employees, projects, onClose, onDone }: {
  employees: Employee[]; projects: Project[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    employee_id: null as number | null,
    project_id: null as number | null,
    spent_on: today(),
    amount: '',
    title: '',
    comment: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.employee_id || !form.amount) { setError('Сотрудник и сумма обязательны'); return }
    try {
      await client.post('/api/compensations', { ...form, amount: Number(form.amount) })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Компенсация расхода" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <div className="form-row">
        <Field label="Сотрудник" required>
          <StyledSelect
            value={form.employee_id}
            options={employees.map(e => ({ value: e.id, label: e.full_name }))}
            onChange={v => set('employee_id', v)}
          />
        </Field>
        <Field label="Проект">
          <StyledSelect
            value={form.project_id}
            options={projects.map(p => ({ value: p.id, label: p.code }))}
            onChange={v => set('project_id', v)}
            allowEmpty
          />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Дата расхода" required>
          <input type="date" value={form.spent_on} onChange={e => set('spent_on', e.target.value)} />
        </Field>
        <Field label="Сумма, ₽" required>
          <input type="number" value={form.amount} onChange={e => set('amount', e.target.value)} />
        </Field>
      </div>
      <Field label="За что" required hint="Газ, бензин, расходники">
        <input value={form.title} onChange={e => set('title', e.target.value)} />
      </Field>
      <Field label="Комментарий">
        <input value={form.comment} onChange={e => set('comment', e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
