import { useState } from 'react'
import client, { apiError } from '../api/client'
import DataTable, { type Column } from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead, { FILTER_WIDTHS, pageActionBtn } from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDate, formatRub, monthStart } from '../utils/format'
import type { Client, Employee, PageResponse, Project, ProjectConfig, Row } from '../types'

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: 'Активен', SUSPENDED: 'Приостановлен', CLOSED: 'Закрыт',
}

/** Конкретные проекты у клиентов — экземпляры конфигураций (US-O4, US-O5). */
export default function ProjectsPage() {
  const { showToast } = useToast()
  const [status, setStatus] = useState<string | null>('ACTIVE')
  const [editing, setEditing] = useState<Project | 'new' | null>(null)
  const [openCard, setOpenCard] = useState<Project | null>(null)

  const { data: projects, loading, reload } = useApi<Project[]>(
    `/api/projects${status ? `?status=${status}` : ''}`, [status])
  const { data: configs } = useApi<ProjectConfig[]>('/api/project-configs')
  const { data: clients } = useApi<PageResponse<Client>>('/api/clients?size=200')
  const { data: employees } = useApi<Employee[]>('/api/employees')

  const columns: Column<Project>[] = [
    { key: 'code', title: 'Код', width: 110, render: p => <b>{p.code}</b> },
    { key: 'name', title: 'Проект' },
    { key: 'client_name', title: 'Клиент', width: 230 },
    { key: 'config_name', title: 'Конфигурация', width: 220,
      render: p => <>{p.config_name} <span className="muted small">v{p.config_version}</span></> },
    { key: 'lifecycle_type', title: 'Жизненный цикл', width: 140,
      render: p => p.lifecycle_type === 'ONE_OFF' ? 'Разовый' : 'Длительный' },
    { key: 'started_on', title: 'Начало', width: 100, render: p => formatDate(p.started_on) },
    { key: 'status', title: 'Статус', width: 130,
      render: p => (
        <span className={`badge ${p.status === 'ACTIVE' ? 'badge-ok'
          : p.status === 'CLOSED' ? 'badge-neutral' : 'badge-warn'}`}>
          {STATUS_LABELS[p.status]}
        </span>
      ) },
    { key: 'actions', title: '', width: 100,
      render: p => (
        <button className="btn-secondary btn-sm"
                onClick={e => { e.stopPropagation(); setEditing(p) }}>Правка</button>
      ) },
  ]

  return (
    <>
      <PageHead
        title="Проекты"
        filters={
          <StyledSelect
            value={status}
            options={Object.entries(STATUS_LABELS).map(([value, label]) => ({ value, label }))}
            onChange={setStatus}
            placeholder="Статус: все"
            allowEmpty
            width={FILTER_WIDTHS.first}
          />
        }
        right={
          <button className="btn-primary" style={pageActionBtn} onClick={() => setEditing('new')}>
            + Новый проект
          </button>
        }
      />

      <DataTable
        columns={columns}
        rows={projects || []}
        loading={loading}
        empty="Проектов нет"
        onRowClick={setOpenCard}
      />

      {editing && (
        <ProjectModal
          value={editing === 'new' ? null : editing}
          configs={configs || []}
          clients={clients?.items || []}
          employees={employees || []}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); showToast('Сохранено'); reload() }}
        />
      )}

      {openCard && (
        <ProjectCard project={openCard} onClose={() => setOpenCard(null)}
                     onChanged={() => { reload() }} />
      )}
    </>
  )
}

function ProjectModal({ value, configs, clients, employees, onClose, onDone }: {
  value: Project | null
  configs: ProjectConfig[]
  clients: Client[]
  employees: Employee[]
  onClose: () => void
  onDone: () => void
}) {
  const [form, setForm] = useState({
    code: value?.code ?? '',
    name: value?.name ?? '',
    client_id: value?.client_id ?? clients[0]?.id ?? null,
    config_id: value?.config_id ?? configs[0]?.id ?? null,
    lifecycle_type: value?.lifecycle_type ?? 'PERMANENT',
    started_on: value?.started_on ?? '',
    finish_criteria: value?.finish_criteria ?? '',
    responsible_employee_id: value?.responsible_employee_id ?? null,
    season_from: value?.season_from ?? '',
    season_to: value?.season_to ?? '',
    contract_number: value?.contract_number ?? '',
    note: value?.note ?? '',
    status: value?.status ?? 'ACTIVE',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.code.trim() || !form.name.trim()) { setError('Код и название обязательны'); return }
    const body = {
      ...form,
      started_on: form.started_on || null,
      season_from: form.season_from || null,
      season_to: form.season_to || null,
    }
    try {
      if (value) await client.put(`/api/projects/${value.id}`, body)
      else await client.post('/api/projects', body)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title={value ? 'Проект' : 'Новый проект'}
      size="large"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Сохранить</button>
        </>
      }
    >
      <div className="form-row">
        <Field label="Код" required hint="Используется в номерах заявок: АБ_2026_137">
          <input value={form.code} onChange={e => set('code', e.target.value)} disabled={!!value} />
        </Field>
        <Field label="Название" required>
          <input value={form.name} onChange={e => set('name', e.target.value)} />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Клиент" required>
          <StyledSelect
            value={form.client_id}
            options={clients.map(c => ({ value: c.id, label: c.name }))}
            onChange={v => set('client_id', v)}
          />
        </Field>
        <Field label="Конфигурация" required
               hint={value ? 'Конфигурацию созданного проекта менять нельзя' : 'Определяет SKU, статусы, ФОТ и документы'}>
          <StyledSelect
            value={form.config_id}
            options={configs.map(c => ({ value: c.id, label: c.name, hint: `v${c.current_version}` }))}
            onChange={v => set('config_id', v)}
            disabled={!!value}
          />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Жизненный цикл" required>
          <StyledSelect
            value={form.lifecycle_type}
            options={[
              { value: 'PERMANENT', label: 'Длительный / постоянный' },
              { value: 'ONE_OFF', label: 'Разовый' },
            ]}
            onChange={v => set('lifecycle_type', v)}
          />
        </Field>
        <Field label="Дата начала" required={form.lifecycle_type === 'ONE_OFF'}>
          <input type="date" value={form.started_on ?? ''} onChange={e => set('started_on', e.target.value)} />
        </Field>
      </div>
      {form.lifecycle_type === 'ONE_OFF' && (
        <Field label="Критерий завершения" required
               hint="Разовый проект обязан знать, при каком условии он закрывается">
          <input value={form.finish_criteria ?? ''} onChange={e => set('finish_criteria', e.target.value)} />
        </Field>
      )}
      <div className="form-row">
        <Field label="Ответственный">
          <StyledSelect
            value={form.responsible_employee_id}
            options={employees.map(e => ({ value: e.id, label: e.full_name }))}
            onChange={v => set('responsible_employee_id', v)}
            allowEmpty
          />
        </Field>
        <Field label="Номер договора">
          <input value={form.contract_number ?? ''} onChange={e => set('contract_number', e.target.value)} />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Сезон с" hint="Для зимних проектов: снег, наледь">
          <input type="date" value={form.season_from ?? ''} onChange={e => set('season_from', e.target.value)} />
        </Field>
        <Field label="Сезон по">
          <input type="date" value={form.season_to ?? ''} onChange={e => set('season_to', e.target.value)} />
        </Field>
      </div>
      <Field label="Примечание">
        <textarea rows={2} value={form.note ?? ''} onChange={e => set('note', e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function ProjectCard({ project, onClose, onChanged }: {
  project: Project; onClose: () => void; onChanged: () => void
}) {
  const { showToast } = useToast()
  const { data, reload } = useApi<{
    project: Project; summary: Row; monthly_plans: Row[]; buildings: Row[]
  }>(`/api/projects/${project.id}`, [project.id])
  const [planMonth, setPlanMonth] = useState(monthStart())
  const [payrollPlan, setPayrollPlan] = useState('')
  const [materialsPlan, setMaterialsPlan] = useState('')

  const savePlan = async () => {
    await client.put(`/api/projects/${project.id}/monthly-plan`, {
      period_month: planMonth,
      payroll_plan: payrollPlan ? Number(payrollPlan) : null,
      materials_plan: materialsPlan ? Number(materialsPlan) : null,
    })
    showToast('План сохранён')
    reload()
  }

  const changeStatus = async (status: string) => {
    const reason = status === 'ACTIVE' && project.status === 'CLOSED'
      ? window.prompt('Причина возобновления проекта') : null
    if (status === 'ACTIVE' && project.status === 'CLOSED' && !reason) return
    try {
      await client.post(`/api/projects/${project.id}/status`, { status, reason })
      showToast('Статус проекта изменён')
      reload()
      onChanged()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  return (
    <Modal title={`${project.code} — ${project.name}`} size="wide" onClose={onClose}
           actions={<button className="btn-secondary" onClick={onClose}>Закрыть</button>}>
      <div className="tiles">
        <div className="tile static">
          <div className="tile-value">{data?.summary?.requests_total ?? 0}</div>
          <div className="tile-label">Заявок</div>
        </div>
        <div className="tile static">
          <div className="tile-value">{data?.summary?.requests_open ?? 0}</div>
          <div className="tile-label">Открыто</div>
        </div>
        <div className="tile static">
          <div className="tile-value" style={{ fontSize: 18 }}>
            {formatRub(data?.summary?.payroll_total)}
          </div>
          <div className="tile-label">ФОТ всего</div>
        </div>
        <div className="tile static">
          <div className="tile-value" style={{ fontSize: 18 }}>
            {formatRub(data?.summary?.materials_total)}
          </div>
          <div className="tile-label">Материалы</div>
        </div>
        <div className="tile static">
          <div className="tile-value">{data?.summary?.buildings_count ?? 0}</div>
          <div className="tile-label">Объектов</div>
        </div>
      </div>

      <div className="card">
        <h2>Месячный план</h2>
        <div className="filters">
          <div className="form-group">
            <label>Месяц</label>
            <input type="month" value={planMonth.slice(0, 7)}
                   onChange={e => setPlanMonth(`${e.target.value}-01`)} />
          </div>
          <div className="form-group">
            <label>План ФОТ, ₽</label>
            <input type="number" value={payrollPlan} onChange={e => setPayrollPlan(e.target.value)} />
          </div>
          <div className="form-group">
            <label>План материалов, ₽</label>
            <input type="number" value={materialsPlan} onChange={e => setMaterialsPlan(e.target.value)} />
          </div>
          <button className="btn-primary btn-control-h" onClick={savePlan}>Сохранить план</button>
        </div>
        {data?.monthly_plans?.length ? (
          <table>
            <thead>
              <tr><th>Месяц</th><th className="num">План ФОТ</th><th className="num">План материалов</th></tr>
            </thead>
            <tbody>
              {data.monthly_plans.map(p => (
                <tr key={p.period_month}>
                  <td>{formatDate(p.period_month)}</td>
                  <td className="num">{formatRub(p.payroll_plan)}</td>
                  <td className="num">{formatRub(p.materials_plan)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <div className="empty">План не задан</div>}
      </div>

      <div className="actions">
        {project.status !== 'ACTIVE' && (
          <button className="btn-success" onClick={() => changeStatus('ACTIVE')}>Возобновить</button>
        )}
        {project.status === 'ACTIVE' && (
          <>
            <button className="btn-warning" onClick={() => changeStatus('SUSPENDED')}>Приостановить</button>
            <button className="btn-secondary" onClick={() => changeStatus('CLOSED')}>Закрыть проект</button>
          </>
        )}
      </div>
    </Modal>
  )
}
