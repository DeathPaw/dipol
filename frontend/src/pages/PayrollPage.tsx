import { useState } from 'react'
import client, { apiError } from '../api/client'
import DataTable, { type Column } from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import Tabs from '../components/Tabs'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDate, formatQty, formatRub, monthStart, today, unitName } from '../utils/format'
import type { Employee, Project, Row, Sku, Unit } from '../types'

type Tab = 'entries' | 'terms' | 'rates' | 'settlements'

const SCHEMES: Record<string, string> = {
  SHIFT: 'За смену', HOUR: 'Почасовая', PIECE: 'Сдельная по объёму',
  PIECE_KPI: 'Сдельная/KPI', FIX: 'Фиксированная', MIXED: 'Комбинированная',
}

const ENTRY_STATUS: Record<string, string> = {
  DRAFT: 'Черновик', APPROVED: 'Утверждено', CLOSED: 'Закрыто', REVERSED: 'Сторнировано',
}

/**
 * ФОТ. Схема оплаты принадлежит связке «сотрудник + проект + период», а не
 * проекту: в одном проекте люди могут одновременно работать за смену и
 * сдельно (US-E4, US-J1).
 */
export default function PayrollPage() {
  const [tab, setTab] = useState<Tab>('entries')
  const [from, setFrom] = useState(monthStart())
  const [to, setTo] = useState(today())
  const [employeeId, setEmployeeId] = useState<number | null>(null)
  const [projectId, setProjectId] = useState<number | null>(null)

  const { data: employees } = useApi<Employee[]>('/api/employees')
  const { data: projects } = useApi<Project[]>('/api/projects')

  return (
    <>
      <PageHead
        title="ФОТ"
        filters={
          <>
            <input type="date" value={from} onChange={e => setFrom(e.target.value)} style={{ width: 150 }} />
            <input type="date" value={to} onChange={e => setTo(e.target.value)} style={{ width: 150 }} />
            <StyledSelect
              value={employeeId}
              options={(employees || []).map(e => ({ value: e.id, label: e.full_name }))}
              onChange={v => setEmployeeId(v as number | null)}
              placeholder="Сотрудник: все"
              allowEmpty
              width={230}
            />
          </>
        }
        extra={
          <div className="filters" style={{ marginBottom: 0 }}>
            <div className="form-group" style={{ minWidth: 220 }}>
              <label>Проект</label>
              <StyledSelect
                value={projectId}
                options={(projects || []).map(p => ({ value: p.id, label: `${p.code} — ${p.name}` }))}
                onChange={v => setProjectId(v as number | null)}
                placeholder="Все проекты"
                allowEmpty
              />
            </div>
          </div>
        }
      />

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'entries', label: 'Начисления' },
          { key: 'terms', label: 'Условия оплаты' },
          { key: 'rates', label: 'Тарифы' },
          { key: 'settlements', label: 'Взаиморасчёты' },
        ]}
      />

      {tab === 'entries' && (
        <EntriesTab from={from} to={to} employeeId={employeeId} projectId={projectId}
                    employees={employees || []} projects={projects || []} />
      )}
      {tab === 'terms' && (
        <TermsTab employeeId={employeeId} projectId={projectId}
                  employees={employees || []} projects={projects || []} />
      )}
      {tab === 'rates' && <RatesTab projectId={projectId} projects={projects || []} />}
      {tab === 'settlements' && <SettlementsTab from={from} to={to} />}
    </>
  )
}

function EntriesTab({ from, to, employeeId, projectId, employees, projects }: {
  from: string; to: string; employeeId: number | null; projectId: number | null
  employees: Employee[]; projects: Project[]
}) {
  const { showToast } = useToast()
  const [manual, setManual] = useState(false)

  const params = new URLSearchParams({ from, to })
  if (employeeId) params.set('employeeId', String(employeeId))
  if (projectId) params.set('projectId', String(projectId))

  const { data, loading, reload } =
    useApi<Row[]>(`/api/payroll/entries?${params.toString()}`, [params.toString()])

  const total = (data || []).reduce((sum, e) => sum + Number(e.amount || 0), 0)

  const reverse = async (id: number) => {
    const reason = window.prompt('Основание сторно начисления')
    if (!reason) return
    try {
      await client.post(`/api/payroll/entries/${id}/reverse`, { reason })
      showToast('Начисление сторнировано')
      reload()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const columns: Column<Row>[] = [
    { key: 'entry_date', title: 'Дата', width: 110, render: e => formatDate(e.entry_date) },
    { key: 'employee_name', title: 'Сотрудник', width: 200 },
    { key: 'project_code', title: 'Проект', width: 100 },
    { key: 'sku_name', title: 'Работа', render: e => e.sku_name || '—' },
    { key: 'qty', title: 'Объём', width: 120, align: 'right',
      render: e => formatQty(e.qty, e.unit_code) },
    { key: 'rate', title: 'Ставка', width: 110, align: 'right', render: e => formatRub(e.rate) },
    { key: 'coefficient', title: 'k', width: 70, align: 'right',
      render: e => Number(e.coefficient) === 1 ? '—' : e.coefficient },
    { key: 'amount', title: 'Сумма', width: 130, align: 'right',
      render: e => <b>{formatRub(e.amount)}</b> },
    { key: 'formula', title: 'Расчёт', render: e => <span className="small muted">{e.formula || '—'}</span> },
    { key: 'status', title: 'Статус', width: 130,
      render: e => (
        <span className={`badge ${e.status === 'REVERSED' ? 'badge-alert'
          : e.status === 'DRAFT' ? 'badge-warn' : 'badge-ok'}`}>
          {ENTRY_STATUS[e.status] || e.status}
        </span>
      ) },
    { key: 'actions', title: '', width: 100,
      render: e => e.status === 'REVERSED' ? null : (
        <button className="btn-secondary btn-sm" onClick={() => reverse(Number(e.id))}>Сторно</button>
      ) },
  ]

  return (
    <>
      <div className="filters">
        <button className="btn-secondary btn-control-h" onClick={() => setManual(true)}>
          + Ручное начисление
        </button>
        <span className="muted" style={{ alignSelf: 'center' }}>
          Итого за период: <b>{formatRub(total)}</b> по {data?.length ?? 0} начислениям
        </span>
      </div>
      <DataTable columns={columns} rows={data || []} loading={loading}
                 empty="Начислений за период нет" />
      {manual && (
        <ManualEntryModal
          employees={employees}
          projects={projects}
          onClose={() => setManual(false)}
          onDone={() => { setManual(false); showToast('Начисление добавлено'); reload() }}
        />
      )}
    </>
  )
}

function ManualEntryModal({ employees, projects, onClose, onDone }: {
  employees: Employee[]; projects: Project[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    employee_id: null as number | null,
    project_id: null as number | null,
    entry_date: today(),
    amount: '',
    reason: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    try {
      await client.post('/api/payroll/entries/manual', { ...form, amount: Number(form.amount) })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Ручное начисление" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Начислить</button>
             </>
           }>
      <div className="notice notice-info">
        Ручное начисление всегда хранит основание и автора — в отчётах оно отделяется
        от расчёта по факту.
      </div>
      <div className="form-row">
        <Field label="Сотрудник" required>
          <StyledSelect
            value={form.employee_id}
            options={employees.map(e => ({ value: e.id, label: e.full_name }))}
            onChange={v => set('employee_id', v)}
          />
        </Field>
        <Field label="Проект" required>
          <StyledSelect
            value={form.project_id}
            options={projects.map(p => ({ value: p.id, label: p.code }))}
            onChange={v => set('project_id', v)}
          />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Дата" required>
          <input type="date" value={form.entry_date} onChange={e => set('entry_date', e.target.value)} />
        </Field>
        <Field label="Сумма, ₽" required>
          <input type="number" value={form.amount} onChange={e => set('amount', e.target.value)} />
        </Field>
      </div>
      <Field label="Основание" required>
        <input value={form.reason} onChange={e => set('reason', e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function TermsTab({ employeeId, projectId, employees, projects }: {
  employeeId: number | null; projectId: number | null
  employees: Employee[]; projects: Project[]
}) {
  const { showToast } = useToast()
  const [create, setCreate] = useState(false)
  const params = new URLSearchParams()
  if (employeeId) params.set('employeeId', String(employeeId))
  if (projectId) params.set('projectId', String(projectId))

  const { data, loading, reload } =
    useApi<Row[]>(`/api/payroll/terms?${params.toString()}`, [params.toString()])

  return (
    <>
      <div className="notice notice-info">
        Схема назначается связке «сотрудник + проект + период действия». Конфигурация проекта
        задаёт только допустимые схемы. Изменение схемы или ставки создаёт новую версию —
        закрытые начисления не пересчитываются.
      </div>
      <button className="btn-secondary" style={{ marginBottom: 12 }} onClick={() => setCreate(true)}>
        + Назначить схему оплаты
      </button>
      <DataTable
        columns={[
          { key: 'employee_name', title: 'Сотрудник', width: 220 },
          { key: 'project_code', title: 'Проект', width: 110 },
          { key: 'scheme_name', title: 'Схема', width: 180 },
          { key: 'rate', title: 'Ставка', width: 130, align: 'right', render: t => formatRub(t.rate) },
          { key: 'valid_from', title: 'Действует с', width: 130, render: t => formatDate(t.valid_from) },
          { key: 'valid_to', title: 'по', width: 130, render: t => formatDate(t.valid_to) },
          { key: 'version', title: 'Версия', width: 90, align: 'right' },
          { key: 'is_active', title: '', width: 110,
            render: t => t.is_active
              ? <span className="badge badge-ok">действует</span>
              : <span className="badge badge-neutral">архив</span> },
        ]}
        rows={data || []}
        loading={loading}
        empty="Условия оплаты не заданы"
      />
      {create && (
        <TermsModal
          employees={employees}
          projects={projects}
          onClose={() => setCreate(false)}
          onDone={() => { setCreate(false); showToast('Схема назначена'); reload() }}
        />
      )}
    </>
  )
}

function TermsModal({ employees, projects, onClose, onDone }: {
  employees: Employee[]; projects: Project[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    employee_id: null as number | null,
    project_id: null as number | null,
    scheme_code: 'SHIFT',
    valid_from: monthStart(),
    rate: '',
    comment: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const project = projects.find(p => p.id === form.project_id)
  const { data: configs } = useApi<Row[]>('/api/project-configs')
  const allowed = configs?.find(c => Number(c.id) === project?.config_id)?.allowed_pay_schemes as
    string[] | undefined

  const submit = async () => {
    try {
      await client.post('/api/payroll/terms', { ...form, rate: form.rate ? Number(form.rate) : null })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Условие оплаты сотрудника" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Назначить</button>
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
        <Field label="Проект" required>
          <StyledSelect
            value={form.project_id}
            options={projects.map(p => ({ value: p.id, label: `${p.code} — ${p.name}` }))}
            onChange={v => set('project_id', v)}
          />
        </Field>
      </div>
      <Field label="Схема оплаты" required
             hint={allowed ? `Допустимые в проекте: ${allowed.map(a => SCHEMES[a] || a).join(', ')}` : undefined}>
        <StyledSelect
          value={form.scheme_code}
          options={Object.entries(SCHEMES)
            .filter(([code]) => !allowed || allowed.includes(code))
            .map(([value, label]) => ({ value, label }))}
          onChange={v => set('scheme_code', v)}
        />
      </Field>
      <div className="form-row">
        <Field label="Действует с" required>
          <input type="date" value={form.valid_from} onChange={e => set('valid_from', e.target.value)} />
        </Field>
        <Field label="Ставка, ₽" hint="За смену, час или единицу — в зависимости от схемы">
          <input type="number" value={form.rate} onChange={e => set('rate', e.target.value)} />
        </Field>
      </div>
      <Field label="Комментарий">
        <input value={form.comment} onChange={e => set('comment', e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function RatesTab({ projectId, projects }: { projectId: number | null; projects: Project[] }) {
  const { showToast } = useToast()
  const [create, setCreate] = useState(false)
  const { data, loading, reload } = useApi<{ rates: Row[]; ambiguous: Row[] }>(
    `/api/payroll/rates${projectId ? `?projectId=${projectId}` : ''}`, [projectId])
  const { data: skus } = useApi<Sku[]>('/api/sku')
  const { data: units } = useApi<Unit[]>('/api/references/units')

  return (
    <>
      {data?.ambiguous && data.ambiguous.length > 0 && (
        <div className="notice notice-warning">
          Найдено пересечений тарифов с одинаковым приоритетом: <b>{data.ambiguous.length}</b>.
          Пока приоритет не различается, какой тариф сработает — неочевидно.
        </div>
      )}
      <button className="btn-secondary" style={{ marginBottom: 12 }} onClick={() => setCreate(true)}>
        + Тариф
      </button>
      <DataTable
        columns={[
          { key: 'sku_name', title: 'Работа', render: r => r.sku_name || 'любая' },
          { key: 'project_code', title: 'Проект', width: 110, render: r => r.project_code || 'любой' },
          { key: 'employee_name', title: 'Сотрудник', width: 190, render: r => r.employee_name || '—' },
          { key: 'employee_role_code', title: 'Роль', width: 140,
            render: r => r.employee_role_code || '—' },
          { key: 'value', title: 'Ставка', width: 130, align: 'right',
            render: r => `${formatRub(r.value)} / ${unitName(r.unit_code)}` },
          { key: 'valid_from', title: 'С', width: 110, render: r => formatDate(r.valid_from) },
          { key: 'valid_to', title: 'По', width: 110, render: r => formatDate(r.valid_to) },
          { key: 'priority', title: 'Приоритет', width: 100, align: 'right' },
        ]}
        rows={data?.rates || []}
        loading={loading}
        empty="Тарифы не заданы"
      />
      {create && (
        <RateModal
          skus={skus || []}
          projects={projects}
          units={units || []}
          onClose={() => setCreate(false)}
          onDone={() => { setCreate(false); showToast('Тариф добавлен'); reload() }}
        />
      )}
    </>
  )
}

function RateModal({ skus, projects, units, onClose, onDone }: {
  skus: Sku[]; projects: Project[]; units: Unit[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    sku_id: null as number | null,
    project_id: null as number | null,
    employee_role_code: null as string | null,
    unit_code: 'M2',
    value: '',
    valid_from: today(),
    priority: 100,
    comment: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.value) { setError('Укажите ставку'); return }
    try {
      await client.post('/api/payroll/rates', { ...form, value: Number(form.value) })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Тариф" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <div className="notice notice-info">
        Чем уже правило, тем выше приоритет: тариф на конкретного сотрудника перебивает
        тариф на роль, тариф проекта — общий.
      </div>
      <Field label="Работа">
        <StyledSelect
          value={form.sku_id}
          options={skus.map(s => ({ value: s.id, label: s.name, hint: unitName(s.unit_code) }))}
          onChange={v => {
            set('sku_id', v)
            const s = skus.find(x => x.id === v)
            if (s) set('unit_code', s.unit_code)
          }}
          placeholder="Любая работа"
          allowEmpty
        />
      </Field>
      <div className="form-row">
        <Field label="Проект">
          <StyledSelect
            value={form.project_id}
            options={projects.map(p => ({ value: p.id, label: p.code }))}
            onChange={v => set('project_id', v)}
            placeholder="Любой проект"
            allowEmpty
          />
        </Field>
        <Field label="Единица" required>
          <StyledSelect
            value={form.unit_code}
            options={units.map(u => ({ value: u.code, label: u.name }))}
            onChange={v => set('unit_code', v)}
          />
        </Field>
      </div>
      <div className="form-row three">
        <Field label="Ставка, ₽" required>
          <input type="number" value={form.value} onChange={e => set('value', e.target.value)} />
        </Field>
        <Field label="Действует с" required>
          <input type="date" value={form.valid_from} onChange={e => set('valid_from', e.target.value)} />
        </Field>
        <Field label="Приоритет" hint="Меньше — важнее">
          <input type="number" value={form.priority}
                 onChange={e => set('priority', Number(e.target.value))} />
        </Field>
      </div>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function SettlementsTab({ from, to }: { from: string; to: string }) {
  const { data, loading } = useApi<Row[]>(`/api/payouts/balance?from=${from}&to=${to}`, [from, to])

  return (
    <>
      <div className="notice notice-info">
        Остаток = начислено + премии − штрафы + компенсации − авансы − выплачено.
        Отрицательный остаток — переплата, она переносится, а не обнуляется.
      </div>
      <DataTable
        columns={[
          { key: 'full_name', title: 'Сотрудник' },
          { key: 'accrued', title: 'Начислено', width: 130, align: 'right',
            render: r => formatRub(r.accrued) },
          { key: 'bonuses', title: 'Премии', width: 120, align: 'right',
            render: r => formatRub(r.bonuses) },
          { key: 'penalties', title: 'Штрафы', width: 120, align: 'right',
            render: r => formatRub(r.penalties) },
          { key: 'compensations', title: 'Компенсации', width: 140, align: 'right',
            render: r => formatRub(r.compensations) },
          { key: 'advances', title: 'Авансы', width: 120, align: 'right',
            render: r => formatRub(r.advances) },
          { key: 'paid', title: 'Выплачено', width: 130, align: 'right',
            render: r => formatRub(r.paid) },
          { key: 'balance', title: 'Остаток', width: 140, align: 'right',
            render: r => (
              <b style={{ color: Number(r.balance) < 0 ? 'var(--c-danger)' : undefined }}>
                {formatRub(r.balance)}
              </b>
            ) },
        ]}
        rows={data || []}
        loading={loading}
        empty="Данных за период нет"
      />
    </>
  )
}
