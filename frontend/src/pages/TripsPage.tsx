import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import client, { apiError } from '../api/client'
import DataTable, { type Column } from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead, { pageActionBtn } from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { daysAgo, formatDate, today } from '../utils/format'
import type { Employee, Reference, Row } from '../types'

const TRIP_STATUS: Record<string, string> = {
  PLANNED: 'Запланирован', IN_PROGRESS: 'В работе', DONE: 'Выполнен', CANCELLED: 'Отменён',
}

/**
 * Выезды. Одна заявка может выполняться несколькими выездами, один выезд —
 * закрывать несколько адресов (US-C1, US-C2).
 */
export default function TripsPage() {
  const { showToast } = useToast()
  const [from, setFrom] = useState(daysAgo(14))
  const [to, setTo] = useState('')
  const [status, setStatus] = useState<string | null>(null)
  const [openId, setOpenId] = useState<number | null>(null)
  const [create, setCreate] = useState(false)

  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  if (status) params.set('status', status)

  const { data, loading, reload } = useApi<Row[]>(`/api/trips?${params.toString()}`, [params.toString()])
  const { data: employees } = useApi<Employee[]>('/api/employees')
  const { data: vehicles } = useApi<Row[]>('/api/transport/vehicles')

  const columns: Column<Row>[] = [
    { key: 'trip_date', title: 'Дата', width: 120, render: t => <b>{formatDate(t.trip_date)}</b> },
    { key: 'responsible_name', title: 'Ответственный', width: 220,
      render: t => t.responsible_name || '—' },
    { key: 'vehicle_plate', title: 'Автомобиль', width: 150, render: t => t.vehicle_plate || '—' },
    { key: 'requests_count', title: 'Заявок', width: 90, align: 'right' },
    { key: 'members_count', title: 'Человек', width: 90, align: 'right' },
    { key: 'mileage_total', title: 'Пробег, км', width: 120, align: 'right',
      render: t => t.mileage_total ?? '—' },
    { key: 'status', title: 'Статус', width: 150,
      render: t => (
        <span className={`badge ${t.status === 'DONE' ? 'badge-ok'
          : t.status === 'CANCELLED' ? 'badge-alert' : 'badge-info'}`}>
          {TRIP_STATUS[t.status] || t.status}
        </span>
      ) },
    { key: 'note', title: 'Примечание', render: t => <span className="small">{t.note || '—'}</span> },
  ]

  return (
    <>
      <PageHead
        title="Выезды"
        filters={
          <>
            <input type="date" value={from} onChange={e => setFrom(e.target.value)}
                   style={{ width: 150 }} title="Период с" />
            <input type="date" value={to} onChange={e => setTo(e.target.value)}
                   style={{ width: 150 }} title="Период по" />
            <StyledSelect
              value={status}
              options={Object.entries(TRIP_STATUS).map(([value, label]) => ({ value, label }))}
              onChange={setStatus}
              placeholder="Статус: все"
              allowEmpty
              width={190}
            />
          </>
        }
        right={
          <button className="btn-primary" style={pageActionBtn} onClick={() => setCreate(true)}>
            + Новый выезд
          </button>
        }
      />

      <DataTable
        columns={columns}
        rows={data || []}
        loading={loading}
        empty="Выездов за период нет"
        onRowClick={t => setOpenId(Number(t.id))}
      />

      {create && (
        <CreateTripModal
          employees={employees || []}
          vehicles={vehicles || []}
          onClose={() => setCreate(false)}
          onDone={id => { setCreate(false); reload(); setOpenId(id) }}
        />
      )}

      {openId && (
        <TripCard
          tripId={openId}
          employees={employees || []}
          vehicles={vehicles || []}
          onClose={() => setOpenId(null)}
          onChanged={() => { reload(); showToast('Выезд обновлён') }}
        />
      )}
    </>
  )
}

function CreateTripModal({ employees, vehicles, onClose, onDone }: {
  employees: Employee[]; vehicles: Row[]; onClose: () => void; onDone: (id: number) => void
}) {
  const [date, setDate] = useState(today())
  const [responsible, setResponsible] = useState<number | null>(null)
  const [vehicleId, setVehicleId] = useState<number | null>(null)
  const [note, setNote] = useState('')
  const [error, setError] = useState('')

  const submit = async () => {
    try {
      const res = await client.post('/api/trips', {
        trip_date: date,
        responsible_employee_id: responsible,
        vehicle_id: vehicleId,
        note,
      })
      onDone(Number(res.data.id))
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title="Новый выезд"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Создать</button>
        </>
      }
    >
      <Field label="Дата" required>
        <input type="date" value={date} onChange={e => setDate(e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="Ответственный">
          <StyledSelect
            value={responsible}
            options={employees.map(e => ({ value: e.id, label: e.full_name }))}
            onChange={v => setResponsible(v as number | null)}
            allowEmpty
          />
        </Field>
        <Field label="Автомобиль">
          <StyledSelect
            value={vehicleId}
            options={vehicles.map(v => ({ value: Number(v.id), label: String(v.plate) }))}
            onChange={v => setVehicleId(v as number | null)}
            allowEmpty
          />
        </Field>
      </div>
      <Field label="Примечание">
        <input value={note} onChange={e => setNote(e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function TripCard({ tripId, employees, vehicles, onClose, onChanged }: {
  tripId: number
  employees: Employee[]
  vehicles: Row[]
  onClose: () => void
  onChanged: () => void
}) {
  const navigate = useNavigate()
  const { showToast } = useToast()
  const { data, reload } = useApi<{
    trip: Row; requests: Row[]; members: Row[]; points: Row[]
    mileage_allocations: Row[]; allocated_mileage: number; conflicts: Row[]; reschedules: Row[]
  }>(`/api/trips/${tripId}`, [tripId])
  const { data: reasons } = useApi<Reference[]>('/api/references/reschedule-reasons')

  const [addOpen, setAddOpen] = useState(false)
  const [mileage, setMileage] = useState('')
  const [newDate, setNewDate] = useState('')
  const [rescheduleReason, setRescheduleReason] = useState<string | null>(null)

  if (!data) return null
  const trip = data.trip

  const saveTrip = async (patch: Row) => {
    try {
      await client.put(`/api/trips/${tripId}`, {
        trip_date: newDate || trip.trip_date,
        responsible_employee_id: trip.responsible_employee_id,
        vehicle_id: trip.vehicle_id,
        status: trip.status,
        mileage_total: mileage ? Number(mileage) : trip.mileage_total,
        mileage_source: mileage ? 'MANUAL' : trip.mileage_source,
        note: trip.note,
        reschedule_reason: rescheduleReason,
        ...patch,
      })
      reload()
      onChanged()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const distribute = async () => {
    try {
      await client.post(`/api/trips/${tripId}/mileage/distribute`)
      showToast('Пробег распределён поровну — при необходимости поправьте вручную')
      reload()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const setMember = async (employeeId: number, on: boolean) => {
    const members = data.members
      .filter(m => on || Number(m.employee_id) !== employeeId)
      .map(m => ({ employee_id: m.employee_id, is_planned: m.is_planned, is_fact: m.is_fact }))
    if (on) members.push({ employee_id: employeeId, is_planned: true, is_fact: true })
    await client.put(`/api/trips/${tripId}/members`, members)
    reload()
  }

  return (
    <Modal
      title={`Выезд ${formatDate(trip.trip_date)}`}
      size="wide"
      onClose={onClose}
      actions={<button className="btn-secondary" onClick={onClose}>Закрыть</button>}
    >
      {data.conflicts.length > 0 && (
        <div className="notice notice-warning">
          Пересечения занятости:{' '}
          {data.conflicts.map((c, i) => (
            <span key={i}>
              {c.kind === 'EMPLOYEE' ? 'сотрудник' : 'автомобиль'} {c.name} также в выезде
              №{c.other_trip_id} ({formatDate(c.trip_date)}){i < data.conflicts.length - 1 ? '; ' : ''}
            </span>
          ))}
        </div>
      )}

      <div className="detail-layout">
        <div>
          <div className="card">
            <div className="page-header" style={{ marginBottom: 12 }}>
              <h2 style={{ margin: 0 }}>Заявки в выезде</h2>
              <button className="btn-secondary btn-sm" onClick={() => setAddOpen(true)}>
                + Добавить заявку
              </button>
            </div>
            {data.requests.length === 0 ? (
              <div className="empty">Заявки не добавлены</div>
            ) : (
              <table>
                <thead>
                  <tr><th>№</th><th>Адрес</th><th>Кв.</th><th className="num">Пробег, км</th><th /></tr>
                </thead>
                <tbody>
                  {data.requests.map(r => (
                    <tr key={r.id}>
                      <td>
                        <a href={`/requests/${r.id}`}
                           onClick={e => { e.preventDefault(); navigate(`/requests/${r.id}`) }}>
                          {r.number}
                        </a>
                      </td>
                      <td>{r.building_address}</td>
                      <td>{r.apartment || '—'}</td>
                      <td className="num">{r.km || '—'}</td>
                      <td>
                        <button className="btn-secondary btn-sm" onClick={async () => {
                          await client.delete(`/api/trips/${tripId}/requests/${r.id}`)
                          reload()
                        }}>×</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <div className="card">
            <h2>Пробег</h2>
            <div className="filters">
              <div className="form-group">
                <label>Общий пробег, км</label>
                <input type="number" value={mileage || trip.mileage_total || ''}
                       onChange={e => setMileage(e.target.value)} />
              </div>
              <button className="btn-primary btn-control-h" onClick={() => saveTrip({})}>
                Сохранить
              </button>
              <button className="btn-secondary btn-control-h" onClick={distribute}>
                Распределить поровну
              </button>
              <span className="muted small" style={{ alignSelf: 'center' }}>
                Распределено: {data.allocated_mileage} км
              </span>
            </div>
          </div>
        </div>

        <div>
          <div className="card">
            <h2>Состав</h2>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {employees.filter(e => e.is_active).map(e => {
                const on = data.members.some(m => Number(m.employee_id) === e.id)
                return (
                  <button
                    key={e.id}
                    type="button"
                    className={`chip badge ${on ? 'badge-info' : 'badge-neutral'}`}
                    onClick={() => setMember(e.id, !on)}
                  >
                    {e.short_name || e.full_name}
                  </button>
                )
              })}
            </div>
          </div>

          <div className="card">
            <h2>Параметры</h2>
            <Field label="Автомобиль">
              <StyledSelect
                value={trip.vehicle_id ? Number(trip.vehicle_id) : null}
                options={vehicles.map(v => ({ value: Number(v.id), label: String(v.plate) }))}
                onChange={v => saveTrip({ vehicle_id: v })}
                allowEmpty
              />
            </Field>
            <Field label="Статус">
              <StyledSelect
                value={String(trip.status)}
                options={Object.entries(TRIP_STATUS).map(([value, label]) => ({ value, label }))}
                onChange={v => saveTrip({ status: v })}
              />
            </Field>
            <Field label="Перенос даты"
                   hint="Перенос фиксируется с причиной — срывы должны быть анализируемыми">
              <input type="date" value={newDate} onChange={e => setNewDate(e.target.value)} />
            </Field>
            {newDate && newDate !== String(trip.trip_date).slice(0, 10) && (
              <>
                <Field label="Причина переноса" required>
                  <StyledSelect
                    value={rescheduleReason}
                    options={(reasons || []).map(r => ({ value: r.code, label: r.name }))}
                    onChange={setRescheduleReason}
                  />
                </Field>
                <button className="btn-warning" style={{ width: '100%' }}
                        onClick={() => saveTrip({})} disabled={!rescheduleReason}>
                  Перенести выезд
                </button>
              </>
            )}
          </div>

          {data.reschedules.length > 0 && (
            <div className="card">
              <h2>История переносов</h2>
              <div className="timeline">
                {data.reschedules.map(r => (
                  <div key={r.id} className="timeline-item">
                    <div>{formatDate(r.old_date)} → {formatDate(r.new_date)}</div>
                    <div className="timeline-meta">{r.reason_code} · {r.actor}</div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>

      {addOpen && (
        <AddRequestsModal tripId={tripId} onClose={() => setAddOpen(false)}
                          onDone={() => { setAddOpen(false); reload() }} />
      )}
    </Modal>
  )
}

/** Кандидаты в выезд: только незакрытые заявки (US-C2). */
function AddRequestsModal({ tripId, onClose, onDone }: {
  tripId: number; onClose: () => void; onDone: () => void
}) {
  const [q, setQ] = useState('')
  const { data } = useApi<Row[]>(`/api/trips/candidates?limit=100${q ? `&q=${encodeURIComponent(q)}` : ''}`, [q])

  const add = async (requestId: number) => {
    await client.post(`/api/trips/${tripId}/requests`, { request_id: requestId })
    onDone()
  }

  return (
    <Modal
      title="Добавить заявки в выезд"
      size="large"
      onClose={onClose}
      actions={<button className="btn-secondary" onClick={onClose}>Закрыть</button>}
    >
      <Field label="Поиск">
        <input value={q} onChange={e => setQ(e.target.value)} placeholder="Адрес, номер, квартира" />
      </Field>
      <div className="table-scroll" style={{ maxHeight: 380 }}>
        <table>
          <thead>
            <tr><th>№</th><th>Проект</th><th>Адрес</th><th>Кв.</th><th>План</th><th /></tr>
          </thead>
          <tbody>
            {(data || []).map(r => (
              <tr key={r.id}>
                <td>{r.number}</td>
                <td>{r.project_code}</td>
                <td>{r.building_address}</td>
                <td>{r.apartment || '—'}</td>
                <td>{formatDate(r.planned_date)}</td>
                <td>
                  <button className="btn-primary btn-sm" onClick={() => add(Number(r.id))}>+</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Modal>
  )
}
