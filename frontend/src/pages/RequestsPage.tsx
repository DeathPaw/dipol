import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import client, { apiError } from '../api/client'
import DataTable, { type Column } from '../components/DataTable'
import Modal from '../components/Modal'
import MultiSelectFilter from '../components/MultiSelectFilter'
import PageHead, { FILTER_WIDTHS, pageActionBtn } from '../components/PageHead'
import StatusBadge, { STATUS_NAMES, STATUS_ORDER } from '../components/StatusBadge'
import StyledSelect from '../components/StyledSelect'
import Field from '../components/Field'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDate, today } from '../utils/format'
import type { Building, Client, PageResponse, Project, Reference, Request } from '../types'

const PAGE_SIZE = 50

/** Реестр заявок. Проект, финансовый класс и статус фильтруются независимо (US-B3). */
export default function RequestsPage() {
  const navigate = useNavigate()
  const { showToast } = useToast()
  const [params, setParams] = useSearchParams()

  const [q, setQ] = useState('')
  const [projectIds, setProjectIds] = useState<string[]>([])
  const [statuses, setStatuses] = useState<string[]>(
    params.get('statuses') ? params.get('statuses')!.split(',') : [])
  const [financialClasses, setFinancialClasses] = useState<string[]>([])
  const [urgency, setUrgency] = useState<string | null>(params.get('urgency'))
  // Переходы с дашборда: «просрочены по плану» и «без плана выполнения».
  const [overdue] = useState(params.get('overdue') === '1')
  const [unplanned] = useState(params.get('unplanned') === '1')
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [dateField, setDateField] = useState<'received' | 'done' | 'planned' | 'accepted'>('received')
  const [page, setPage] = useState(0)
  const [showCreate, setShowCreate] = useState(false)

  const { data: projects } = useApi<Project[]>('/api/projects?status=ACTIVE')
  const { data: finClasses } = useApi<Reference[]>('/api/references/financial-classes')

  const query = useMemo(() => {
    const p = new URLSearchParams()
    if (q.trim()) p.set('q', q.trim())
    projectIds.forEach(id => p.append('projectIds', id))
    statuses.forEach(s => p.append('statuses', s))
    financialClasses.forEach(s => p.append('financialClasses', s))
    if (urgency) p.set('urgency', urgency)
    if (overdue) p.set('overdue', 'true')
    if (unplanned) p.set('unplanned', 'true')
    if (dateFrom) p.set('dateFrom', dateFrom)
    if (dateTo) p.set('dateTo', dateTo)
    p.set('dateField', dateField)
    p.set('page', String(page))
    p.set('size', String(PAGE_SIZE))
    return p.toString()
  }, [q, projectIds, statuses, financialClasses, urgency, overdue, unplanned,
      dateFrom, dateTo, dateField, page])

  const { data, loading, reload } = useApi<PageResponse<Request>>(`/api/requests?${query}`, [query])

  // Ссылки с дашборда приходят с уже выбранным фильтром — сбрасываем страницу.
  useEffect(() => { setPage(0) }, [q, projectIds, statuses, financialClasses, urgency, dateFrom, dateTo])

  useEffect(() => {
    if (params.toString()) setParams({}, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const columns: Column<Request>[] = [
    { key: 'number', title: '№', width: 130, nowrap: true,
      render: r => <b>{r.number}</b> },
    { key: 'external_number', title: '№ заказчика', width: 110, nowrap: true,
      render: r => r.external_number || '—' },
    { key: 'received_at', title: 'Поступила', width: 105, nowrap: true,
      render: r => formatDate(r.received_at) },
    { key: 'project_code', title: 'Проект', width: 90, nowrap: true },
    { key: 'building_address', title: 'Адрес',
      render: r => (
        <>
          {r.building_address}
          {r.apartment && <span className="muted"> · кв. {r.apartment}</span>}
          {r.entrance && <span className="muted"> · подъезд {r.entrance}</span>}
        </>
      ) },
    { key: 'description', title: 'Описание',
      render: r => <span className="small">{r.description || '—'}</span> },
    { key: 'status', title: 'Статус', width: 150, nowrap: true,
      render: r => <StatusBadge status={r.status} name={r.status_name} /> },
    { key: 'financial_class', title: 'Фин. класс', width: 140, nowrap: true,
      render: r => finClasses?.find(f => f.code === r.financial_class)?.name || '—' },
    { key: 'done_at', title: 'Выполнена', width: 105, nowrap: true,
      render: r => formatDate(r.done_at) },
    { key: 'flags', title: '', width: 70, nowrap: true,
      render: r => (
        <>
          {r.urgency === 'EMERGENCY' && <span className="badge badge-alert" title="Аварийная">АВ</span>}
          {r.is_warranty && <span className="badge badge-warn" title="Гарантийная">ГР</span>}
        </>
      ) },
  ]

  const totalPages = data ? Math.ceil(data.total / PAGE_SIZE) : 0

  return (
    <>
      <PageHead
        title="Заявки"
        filters={
          <>
            <MultiSelectFilter
              options={(projects || []).map(p => ({ value: String(p.id), label: p.code }))}
              value={projectIds}
              onChange={setProjectIds}
              placeholder="Проект: все"
              width={FILTER_WIDTHS.first}
            />
            <MultiSelectFilter
              options={STATUS_ORDER.map(s => ({ value: s, label: STATUS_NAMES[s] }))}
              value={statuses}
              onChange={setStatuses}
              placeholder="Статус: все"
              width={FILTER_WIDTHS.first}
            />
            <input
              value={q}
              onChange={e => setQ(e.target.value)}
              placeholder="Адрес, номер, квартира, описание"
              style={{ width: FILTER_WIDTHS.third }}
            />
          </>
        }
        right={
          <button className="btn-primary" style={pageActionBtn} onClick={() => setShowCreate(true)}>
            + Новая заявка
          </button>
        }
        extra={
          <div className="filters" style={{ marginBottom: 0 }}>
            <MultiSelectFilter
              options={(finClasses || []).map(f => ({ value: f.code, label: f.name }))}
              value={financialClasses}
              onChange={setFinancialClasses}
              placeholder="Фин. класс: все"
              width={200}
            />
            <div className="segmented compact">
              {([['received', 'Поступление'], ['planned', 'План'],
                 ['done', 'Выполнение'], ['accepted', 'Приёмка']] as const).map(([k, label]) => (
                <button key={k} className={dateField === k ? 'active' : ''} onClick={() => setDateField(k)}>
                  {label}
                </button>
              ))}
            </div>
            <input type="date" value={dateFrom} onChange={e => setDateFrom(e.target.value)}
                   style={{ width: 150 }} title="Период с" />
            <input type="date" value={dateTo} onChange={e => setDateTo(e.target.value)}
                   style={{ width: 150 }} title="Период по" />
            <StyledSelect
              value={urgency}
              options={[{ value: 'EMERGENCY', label: 'Аварийные' }, { value: 'PLANNED', label: 'Плановые' }]}
              onChange={setUrgency}
              placeholder="Срочность: любая"
              allowEmpty
              width={180}
            />
            <span className="muted small" style={{ alignSelf: 'center' }}>
              Найдено: {data?.total ?? 0}
            </span>
          </div>
        }
      />

      <DataTable
        columns={columns}
        rows={data?.items || []}
        loading={loading}
        empty="Заявок по этим условиям нет"
        onRowClick={r => navigate(`/requests/${r.id}`)}
      />

      {totalPages > 1 && (
        <div className="pagination">
          <button className="btn-secondary btn-sm" disabled={page === 0}
                  onClick={() => setPage(p => p - 1)}>← Назад</button>
          <span className="small muted">Страница {page + 1} из {totalPages}</span>
          <button className="btn-secondary btn-sm" disabled={page + 1 >= totalPages}
                  onClick={() => setPage(p => p + 1)}>Вперёд →</button>
        </div>
      )}

      {showCreate && (
        <CreateRequestModal
          projects={projects || []}
          onClose={() => setShowCreate(false)}
          onCreated={id => {
            showToast('Заявка создана')
            setShowCreate(false)
            reload()
            navigate(`/requests/${id}`)
          }}
        />
      )}
    </>
  )
}

/**
 * Создание заявки. Дом выбирается поиском по реестру: вводить адрес строкой
 * нельзя — связь должна идти по ID объекта (US-A1).
 */
function CreateRequestModal({
  projects, onClose, onCreated,
}: {
  projects: Project[]
  onClose: () => void
  onCreated: (id: number) => void
}) {
  const [projectId, setProjectId] = useState<number | null>(projects[0]?.id ?? null)
  const [buildingQuery, setBuildingQuery] = useState('')
  const [buildingId, setBuildingId] = useState<number | null>(null)
  const [apartment, setApartment] = useState('')
  const [externalNumber, setExternalNumber] = useState('')
  const [receivedAtCustomer, setReceivedAtCustomer] = useState('')
  const [urgency, setUrgency] = useState<string | null>('PLANNED')
  const [description, setDescription] = useState('')
  const [plannedDate, setPlannedDate] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  const { data: buildings } = useApi<PageResponse<Building>>(
    buildingQuery.trim().length >= 2 ? `/api/buildings?q=${encodeURIComponent(buildingQuery)}&size=20` : null,
    [buildingQuery])
  const { data: clients } = useApi<PageResponse<Client>>('/api/clients?size=100')

  const project = projects.find(p => p.id === projectId)

  const submit = async () => {
    if (!projectId || !buildingId) {
      setError('Проект и дом обязательны: заявка без адреса не создаётся')
      return
    }
    const clientId = project?.client_id ?? clients?.items[0]?.id
    if (!clientId) {
      setError('Не удалось определить клиента проекта')
      return
    }
    setSaving(true)
    setError('')
    try {
      const res = await client.post<Request>('/api/requests', {
        client_id: clientId,
        project_id: projectId,
        building_id: buildingId,
        apartment: apartment || null,
        external_number: externalNumber || null,
        external_system: externalNumber ? 'СУПЕР МКД+' : null,
        source_code: externalNumber ? 'CUSTOMER_SYSTEM' : 'VERBAL',
        received_at_customer: receivedAtCustomer ? `${receivedAtCustomer}T00:00:00` : null,
        planned_date: plannedDate || null,
        urgency,
        description: description || null,
      })
      onCreated(res.data.id)
    } catch (e) {
      setError(apiError(e, 'Не удалось создать заявку'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      title="Новая заявка"
      size="large"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit} disabled={saving}>
            {saving ? 'Сохранение…' : 'Создать'}
          </button>
        </>
      }
    >
      <div className="form-row">
        <Field label="Проект" required>
          <StyledSelect
            value={projectId}
            options={projects.map(p => ({ value: p.id, label: `${p.code} — ${p.name}` }))}
            onChange={v => setProjectId(v as number)}
          />
        </Field>
        <Field label="Срочность">
          <StyledSelect
            value={urgency}
            options={[{ value: 'PLANNED', label: 'Плановая' }, { value: 'EMERGENCY', label: 'Аварийная' }]}
            onChange={setUrgency}
          />
        </Field>
      </div>

      <Field label="Дом" required hint="Начните вводить адрес — дом выбирается из реестра МКД">
        <input
          value={buildingQuery}
          onChange={e => { setBuildingQuery(e.target.value); setBuildingId(null) }}
          placeholder="Например: Сиверский, Военный городок, 12"
        />
      </Field>
      {buildings && buildings.items.length > 0 && !buildingId && (
        <div style={{
          border: '1px solid var(--c-border)', borderRadius: 'var(--radius)',
          maxHeight: 180, overflowY: 'auto', marginBottom: 14,
        }}>
          {buildings.items.map(b => (
            <div
              key={b.id}
              onClick={() => { setBuildingId(b.id); setBuildingQuery(b.address) }}
              style={{ padding: '7px 10px', cursor: 'pointer', borderBottom: '1px solid var(--c-border)' }}
            >
              {b.address}
              <span className="muted small"> · ID {b.legacy_id ?? b.id}{b.zheu_name ? ` · ${b.zheu_name}` : ''}</span>
            </div>
          ))}
        </div>
      )}
      {buildingId && (
        <div className="notice notice-success">Дом выбран: {buildingQuery}</div>
      )}

      <div className="form-row three">
        <Field label="Квартира">
          <input value={apartment} onChange={e => setApartment(e.target.value)} />
        </Field>
        <Field label="Номер заявки заказчика">
          <input value={externalNumber} onChange={e => setExternalNumber(e.target.value)} />
        </Field>
        <Field label="Дата заказчика">
          <input type="date" value={receivedAtCustomer} max={today()}
                 onChange={e => setReceivedAtCustomer(e.target.value)} />
        </Field>
      </div>

      <Field label="Плановая дата выполнения">
        <input type="date" value={plannedDate} onChange={e => setPlannedDate(e.target.value)} />
      </Field>

      <Field label="Описание проблемы" required>
        <textarea rows={3} value={description} onChange={e => setDescription(e.target.value)} />
      </Field>

      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
