import { useState } from 'react'
import client, { apiError } from '../api/client'
import DataTable, { type Column } from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead, { pageActionBtn } from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { daysAgo, formatDate, formatRub } from '../utils/format'
import type { PageResponse, Project, Reference, Row } from '../types'

/**
 * Смены спецтехники: ПУХТО, кран, вышка, манипулятор.
 *
 * Это самостоятельная сущность, а не атрибут выезда (US-C4): одна смена
 * обслуживает несколько адресов, и её стоимость распределяется между ними.
 */
export default function EquipmentShiftsPage() {
  const { showToast } = useToast()
  const [from, setFrom] = useState(daysAgo(60))
  const [to, setTo] = useState('')
  const [typeCode, setTypeCode] = useState<string | null>(null)
  const [create, setCreate] = useState(false)
  const [openId, setOpenId] = useState<number | null>(null)

  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  if (typeCode) params.set('typeCode', typeCode)

  const { data, loading, reload } =
    useApi<Row[]>(`/api/equipment-shifts?${params.toString()}`, [params.toString()])
  const { data: types } = useApi<Reference[]>('/api/references/special-equipment-types')
  const { data: unallocated } = useApi<Row[]>('/api/equipment-shifts/unallocated')

  const columns: Column<Row>[] = [
    { key: 'date_from', title: 'Дата', width: 120, render: s => formatDate(s.date_from) },
    { key: 'type_name', title: 'Тип техники', width: 160 },
    { key: 'supplier_display_name', title: 'Поставщик', width: 200,
      render: s => s.supplier_display_name || s.supplier_name || '—' },
    { key: 'resource_qty', title: 'Ресурс', width: 110, align: 'right',
      render: s => s.resource_qty ? `${s.resource_qty} ${s.resource_unit || ''}` : '—' },
    { key: 'cost', title: 'Стоимость', width: 130, align: 'right',
      render: s => formatRub(s.actual_cost ?? s.planned_cost) },
    { key: 'allocated_amount', title: 'Распределено', width: 140, align: 'right',
      render: s => {
        const cost = Number(s.actual_cost ?? s.planned_cost ?? 0)
        const alloc = Number(s.allocated_amount ?? 0)
        const full = cost > 0 && Math.abs(cost - alloc) < 0.01
        return (
          <span className={`badge ${full ? 'badge-ok' : 'badge-warn'}`}>{formatRub(alloc)}</span>
        )
      } },
    { key: 'note', title: 'Примечание', render: s => <span className="small">{s.note || '—'}</span> },
  ]

  return (
    <>
      <PageHead
        title="Спецтехника"
        filters={
          <>
            <input type="date" value={from} onChange={e => setFrom(e.target.value)} style={{ width: 150 }} />
            <input type="date" value={to} onChange={e => setTo(e.target.value)} style={{ width: 150 }} />
            <StyledSelect
              value={typeCode}
              options={(types || []).map(t => ({ value: t.code, label: t.name }))}
              onChange={setTypeCode}
              placeholder="Тип: все"
              allowEmpty
              width={190}
            />
          </>
        }
        right={
          <button className="btn-primary" style={pageActionBtn} onClick={() => setCreate(true)}>
            + Привлечение техники
          </button>
        }
      />

      {unallocated && unallocated.length > 0 && (
        <div className="notice notice-warning">
          Смен с нераспределённой стоимостью: <b>{unallocated.length}</b>. Пока стоимость не
          разнесена по адресам, она не попадает в производственную себестоимость заявок.
        </div>
      )}

      <DataTable
        columns={columns}
        rows={data || []}
        loading={loading}
        empty="Привлечений техники за период нет"
        onRowClick={s => setOpenId(Number(s.id))}
      />

      {create && (
        <ShiftModal types={types || []} onClose={() => setCreate(false)}
                    onDone={id => { setCreate(false); reload(); setOpenId(id) }} />
      )}

      {openId && (
        <ShiftCard shiftId={openId} onClose={() => setOpenId(null)}
                   onChanged={() => { reload(); showToast('Распределение сохранено') }} />
      )}
    </>
  )
}

function ShiftModal({ types, onClose, onDone }: {
  types: Reference[]; onClose: () => void; onDone: (id: number) => void
}) {
  const [form, setForm] = useState({
    type_code: types[0]?.code ?? 'PUHTO',
    date_from: new Date().toISOString().slice(0, 10),
    supplier_name: '',
    resource_qty: '',
    resource_unit: 'HOUR',
    planned_cost: '',
    actual_cost: '',
    note: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: string) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    try {
      const res = await client.post('/api/equipment-shifts', {
        ...form,
        resource_qty: form.resource_qty || null,
        planned_cost: form.planned_cost || null,
        actual_cost: form.actual_cost || null,
      })
      onDone(Number((res.data.shift as Row).id))
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title="Привлечение спецтехники"
      size="large"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Создать</button>
        </>
      }
    >
      <div className="form-row">
        <Field label="Тип техники" required>
          <StyledSelect
            value={form.type_code}
            options={types.map(t => ({ value: t.code, label: t.name }))}
            onChange={v => set('type_code', String(v))}
          />
        </Field>
        <Field label="Дата" required>
          <input type="date" value={form.date_from} onChange={e => set('date_from', e.target.value)} />
        </Field>
      </div>
      <Field label="Поставщик">
        <input value={form.supplier_name} onChange={e => set('supplier_name', e.target.value)} />
      </Field>
      <div className="form-row three">
        <Field label="Ресурс" hint="часы, рейсы, смены">
          <input type="number" value={form.resource_qty} onChange={e => set('resource_qty', e.target.value)} />
        </Field>
        <Field label="Плановая стоимость, ₽">
          <input type="number" value={form.planned_cost} onChange={e => set('planned_cost', e.target.value)} />
        </Field>
        <Field label="Фактическая стоимость, ₽">
          <input type="number" value={form.actual_cost} onChange={e => set('actual_cost', e.target.value)} />
        </Field>
      </div>
      <Field label="Примечание">
        <input value={form.note} onChange={e => set('note', e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

/** Распределение стоимости смены между адресами: суммой, процентом или часами. */
function ShiftCard({ shiftId, onClose, onChanged }: {
  shiftId: number; onClose: () => void; onChanged: () => void
}) {
  const { showToast } = useToast()
  const { data, reload } = useApi<{
    shift: Row; allocations: Row[]; allocated_amount: number; unallocated_amount: number
  }>(`/api/equipment-shifts/${shiftId}`, [shiftId])
  const { data: projects } = useApi<Project[]>('/api/projects')
  const [rows, setRows] = useState<Row[] | null>(null)
  const [search, setSearch] = useState('')
  const { data: buildings } = useApi<PageResponse<{ id: number; address: string }>>(
    search.trim().length >= 2 ? `/api/buildings?q=${encodeURIComponent(search)}&size=10` : null, [search])

  if (!data) return null
  const current = rows ?? data.allocations

  const save = async () => {
    try {
      await client.put(`/api/equipment-shifts/${shiftId}/allocations`, current)
      setRows(null)
      reload()
      onChanged()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  return (
    <Modal
      title="Распределение стоимости"
      size="wide"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Закрыть</button>
          <button className="btn-primary" onClick={save}>Сохранить распределение</button>
        </>
      }
    >
      <div className="tiles">
        <div className="tile static">
          <div className="tile-value" style={{ fontSize: 18 }}>
            {formatRub(data.shift.actual_cost ?? data.shift.planned_cost)}
          </div>
          <div className="tile-label">Стоимость смены</div>
        </div>
        <div className="tile static">
          <div className="tile-value" style={{ fontSize: 18 }}>{formatRub(data.allocated_amount)}</div>
          <div className="tile-label">Распределено</div>
        </div>
        <div className={`tile static${Number(data.unallocated_amount) > 0 ? ' warn' : ''}`}>
          <div className="tile-value" style={{ fontSize: 18 }}>{formatRub(data.unallocated_amount)}</div>
          <div className="tile-label">Не распределено</div>
        </div>
      </div>

      <table>
        <thead>
          <tr>
            <th>Адрес / проект</th>
            <th style={{ width: 140 }}>Основание</th>
            <th style={{ width: 120 }}>Значение</th>
            <th style={{ width: 140 }}>Сумма, ₽</th>
            <th style={{ width: 60 }} />
          </tr>
        </thead>
        <tbody>
          {current.map((a, i) => (
            <tr key={i}>
              <td>
                {a.building_address || projects?.find(p => p.id === Number(a.project_id))?.name || '—'}
              </td>
              <td>
                <StyledSelect
                  value={String(a.basis ?? 'AMOUNT')}
                  options={[
                    { value: 'AMOUNT', label: 'Сумма' },
                    { value: 'PERCENT', label: 'Процент' },
                    { value: 'HOURS', label: 'Часы' },
                    { value: 'RUNS', label: 'Рейсы' },
                  ]}
                  onChange={v => {
                    const next = [...current]; next[i] = { ...a, basis: v }; setRows(next)
                  }}
                />
              </td>
              <td>
                <input type="number" value={a.basis_value ?? ''} onChange={e => {
                  const next = [...current]; next[i] = { ...a, basis_value: e.target.value, amount: null }
                  setRows(next)
                }} />
              </td>
              <td>
                <input type="number" value={a.amount ?? ''} onChange={e => {
                  const next = [...current]; next[i] = { ...a, amount: e.target.value }; setRows(next)
                }} />
              </td>
              <td>
                <button className="btn-secondary btn-sm"
                        onClick={() => setRows(current.filter((_, j) => j !== i))}>×</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="filters" style={{ marginTop: 12 }}>
        <div className="form-group" style={{ minWidth: 320 }}>
          <label>Добавить адрес</label>
          <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Поиск дома" />
        </div>
      </div>
      {buildings?.items.map(b => (
        <div key={b.id}
             onClick={() => {
               setRows([...current, {
                 building_id: b.id, building_address: b.address, basis: 'AMOUNT',
                 basis_value: null, amount: null,
               }])
               setSearch('')
             }}
             style={{ padding: '6px 10px', cursor: 'pointer', borderBottom: '1px solid var(--c-border)' }}>
          {b.address}
        </div>
      ))}
    </Modal>
  )
}
