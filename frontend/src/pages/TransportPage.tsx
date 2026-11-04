import { useState } from 'react'
import client, { apiError } from '../api/client'
import DataTable, { type Column } from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead, { pageActionBtn } from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import Tabs from '../components/Tabs'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDate, formatRub, monthStart, today } from '../utils/format'
import type { Employee, Reference, Row } from '../types'

type Tab = 'vehicles' | 'equipment' | 'norms'

/** Автомобили, ТО и страховки, расходы, оборудование и амортизация (эпик Q). */
export default function TransportPage() {
  const { showToast } = useToast()
  const [tab, setTab] = useState<Tab>('vehicles')
  const [create, setCreate] = useState(false)
  const [openVehicle, setOpenVehicle] = useState<number | null>(null)

  const { data: vehicles, loading, reload } = useApi<Row[]>('/api/transport/vehicles')
  const { data: alerts } = useApi<Row[]>('/api/transport/vehicles/alerts?days=30')
  const { data: equipment, reload: reloadEquipment } = useApi<Row[]>('/api/transport/equipment')
  const { data: norms } = useApi<Row[]>('/api/transport/depreciation-norms')
  const { data: employees } = useApi<Employee[]>('/api/employees')

  const columns: Column<Row>[] = [
    { key: 'plate', title: 'Госномер', width: 140, render: v => <b>{v.plate}</b> },
    { key: 'brand', title: 'Марка и модель',
      render: v => `${v.brand || ''} ${v.model || ''}`.trim() || '—' },
    { key: 'assigned_name', title: 'Закреплён', width: 200, render: v => v.assigned_name || '—' },
    { key: 'mileage', title: 'Пробег', width: 120, align: 'right', render: v => v.mileage ?? '—' },
    { key: 'insurance_valid_to', title: 'Страховка до', width: 150,
      render: v => {
        if (!v.insurance_valid_to) return <span className="muted">—</span>
        const expired = new Date(v.insurance_valid_to) < new Date()
        return (
          <span className={`badge ${expired ? 'badge-alert' : 'badge-ok'}`}>
            {formatDate(v.insurance_valid_to)}
          </span>
        )
      } },
    { key: 'last_service_on', title: 'Последнее ТО', width: 140,
      render: v => formatDate(v.last_service_on) },
    { key: 'status', title: 'Статус', width: 120 },
  ]

  return (
    <>
      <PageHead
        title="Транспорт и оборудование"
        right={
          <button className="btn-primary" style={pageActionBtn} onClick={() => setCreate(true)}>
            + Автомобиль
          </button>
        }
      />

      {alerts && alerts.length > 0 && (
        <div className="notice notice-warning">
          Контрольные даты на исходе:{' '}
          {alerts.map(a => `${a.plate} — ${a.kind === 'INSURANCE' ? 'страховка'
            : a.kind === 'SERVICE' ? 'ТО' : a.kind} до ${formatDate(a.valid_to)}`).join('; ')}
        </div>
      )}

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'vehicles', label: 'Автомобили', hint: vehicles?.length ?? 0 },
          { key: 'equipment', label: 'Оборудование', hint: equipment?.length ?? 0 },
          { key: 'norms', label: 'Нормы амортизации', hint: norms?.length ?? 0 },
        ]}
      />

      {tab === 'vehicles' && (
        <DataTable columns={columns} rows={vehicles || []} loading={loading}
                   empty="Автомобилей нет"
                   onRowClick={v => setOpenVehicle(Number(v.id))} />
      )}

      {tab === 'equipment' && (
        <EquipmentTab equipment={equipment || []} employees={employees || []}
                      onChanged={() => { reloadEquipment(); showToast('Сохранено') }} />
      )}

      {tab === 'norms' && (
        <>
          <div className="notice notice-info">
            Норматив амортизации попадает в производственную себестоимость и не меняет
            бухгалтерский учёт основных средств.
          </div>
          <DataTable
            columns={[
              { key: 'equipment_name', title: 'Оборудование', render: n => n.equipment_name || 'любое' },
              { key: 'config_name', title: 'Конфигурация', width: 220,
                render: n => n.config_name || '—' },
              { key: 'sku_name', title: 'Работа', width: 220, render: n => n.sku_name || '—' },
              { key: 'basis', title: 'За', width: 110,
                render: n => n.basis === 'SHIFT' ? 'смену' : n.basis === 'HOUR' ? 'час' : 'единицу' },
              { key: 'amount', title: 'Сумма', width: 130, align: 'right',
                render: n => formatRub(n.amount) },
              { key: 'valid_from', title: 'С', width: 120, render: n => formatDate(n.valid_from) },
            ]}
            rows={norms || []}
            empty="Нормативы не заданы"
          />
        </>
      )}

      {create && (
        <VehicleModal employees={employees || []} onClose={() => setCreate(false)}
                      onDone={() => { setCreate(false); showToast('Автомобиль добавлен'); reload() }} />
      )}

      {openVehicle && (
        <VehicleCard vehicleId={openVehicle} onClose={() => setOpenVehicle(null)} onChanged={reload} />
      )}
    </>
  )
}

function VehicleModal({ employees, onClose, onDone }: {
  employees: Employee[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    brand: '', model: '', plate: '', status: 'ACTIVE',
    assigned_employee_id: null as number | null, mileage: '', note: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.plate.trim() || !form.brand.trim()) { setError('Марка и госномер обязательны'); return }
    try {
      await client.post('/api/transport/vehicles', {
        ...form, mileage: form.mileage ? Number(form.mileage) : null,
      })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Автомобиль" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <div className="form-row">
        <Field label="Марка" required>
          <input value={form.brand} onChange={e => set('brand', e.target.value)} />
        </Field>
        <Field label="Модель"><input value={form.model} onChange={e => set('model', e.target.value)} /></Field>
      </div>
      <div className="form-row">
        <Field label="Госномер" required>
          <input value={form.plate} onChange={e => set('plate', e.target.value)} />
        </Field>
        <Field label="Пробег">
          <input type="number" value={form.mileage} onChange={e => set('mileage', e.target.value)} />
        </Field>
      </div>
      <Field label="Закреплён за">
        <StyledSelect value={form.assigned_employee_id}
                      options={employees.map(e => ({ value: e.id, label: e.full_name }))}
                      onChange={v => set('assigned_employee_id', v)} allowEmpty />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function VehicleCard({ vehicleId, onClose, onChanged }: {
  vehicleId: number; onClose: () => void; onChanged: () => void
}) {
  const { showToast } = useToast()
  const { data, reload } = useApi<{
    vehicle: Row; maintenance: Row[]; expenses: Row[]; trips: Row[]
  }>(`/api/transport/vehicles/${vehicleId}`, [vehicleId])
  const { data: categories } = useApi<Reference[]>('/api/references/vehicle-expense-categories')
  const [addMaintenance, setAddMaintenance] = useState(false)
  const [addExpense, setAddExpense] = useState(false)
  const { data: report } = useApi<Row[]>(
    `/api/transport/vehicles/${vehicleId}/expense-report?from=${monthStart()}&to=${today()}`,
    [vehicleId])

  if (!data) return null

  return (
    <Modal title={`Автомобиль ${data.vehicle.plate}`} size="wide" onClose={onClose}
           actions={<button className="btn-secondary" onClick={onClose}>Закрыть</button>}>
      <div className="detail-layout">
        <div>
          <div className="card">
            <div className="page-header" style={{ marginBottom: 12 }}>
              <h2 style={{ margin: 0 }}>ТО, ремонты и страховка</h2>
              <button className="btn-secondary btn-sm" onClick={() => setAddMaintenance(true)}>
                + Запись
              </button>
            </div>
            <table>
              <thead>
                <tr><th style={{ width: 120 }}>Дата</th><th style={{ width: 130 }}>Вид</th>
                    <th>Работы</th><th className="num" style={{ width: 120 }}>Стоимость</th>
                    <th style={{ width: 130 }}>Действует до</th></tr>
              </thead>
              <tbody>
                {data.maintenance.map(m => (
                  <tr key={m.id}>
                    <td>{formatDate(m.occurred_on)}</td>
                    <td>{m.kind === 'SERVICE' ? 'ТО' : m.kind === 'REPAIR' ? 'Ремонт'
                      : m.kind === 'INSURANCE' ? 'Страховка' : 'Осмотр'}</td>
                    <td>{m.works || '—'}</td>
                    <td className="num">{formatRub(m.cost)}</td>
                    <td>{formatDate(m.valid_to)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="card">
            <div className="page-header" style={{ marginBottom: 12 }}>
              <h2 style={{ margin: 0 }}>Расходы</h2>
              <button className="btn-secondary btn-sm" onClick={() => setAddExpense(true)}>
                + Расход
              </button>
            </div>
            <table>
              <thead>
                <tr><th style={{ width: 120 }}>Дата</th><th style={{ width: 160 }}>Категория</th>
                    <th className="num" style={{ width: 130 }}>Сумма</th><th>Примечание</th></tr>
              </thead>
              <tbody>
                {data.expenses.map(e => (
                  <tr key={e.id}>
                    <td>{formatDate(e.spent_on)}</td>
                    <td>{e.category_name}</td>
                    <td className="num">{formatRub(e.amount)}</td>
                    <td>{e.note || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div>
          <div className="card">
            <h2>Расходы за текущий месяц</h2>
            {report && report.length > 0 ? (
              <table>
                <thead><tr><th>Категория</th><th className="num">Сумма</th></tr></thead>
                <tbody>
                  {report.map((r, i) => (
                    <tr key={i}><td>{r.category_name}</td><td className="num">{formatRub(r.total)}</td></tr>
                  ))}
                </tbody>
              </table>
            ) : <div className="empty">Расходов нет</div>}
          </div>

          <div className="card">
            <h2>Последние выезды</h2>
            {data.trips.length === 0 ? <div className="empty">Выездов нет</div> : (
              <div className="timeline">
                {data.trips.slice(0, 10).map(t => (
                  <div key={t.id} className="timeline-item">
                    <div>{formatDate(t.trip_date)}</div>
                    <div className="timeline-meta">
                      {t.mileage_total ? `${t.mileage_total} км` : 'пробег не внесён'}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {addMaintenance && (
        <MaintenanceModal vehicleId={vehicleId} onClose={() => setAddMaintenance(false)}
                          onDone={() => { setAddMaintenance(false); reload(); onChanged() }} />
      )}
      {addExpense && (
        <ExpenseModal vehicleId={vehicleId} categories={categories || []}
                      onClose={() => setAddExpense(false)}
                      onDone={() => { setAddExpense(false); showToast('Расход добавлен'); reload() }} />
      )}
    </Modal>
  )
}

function MaintenanceModal({ vehicleId, onClose, onDone }: {
  vehicleId: number; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    kind: 'SERVICE', occurred_on: today(), mileage: '', works: '', cost: '',
    provider: '', valid_from: '', valid_to: '', note: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    try {
      await client.post(`/api/transport/vehicles/${vehicleId}/maintenance`, {
        ...form,
        mileage: form.mileage ? Number(form.mileage) : null,
        cost: form.cost ? Number(form.cost) : null,
        valid_from: form.valid_from || null,
        valid_to: form.valid_to || null,
      })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="ТО, ремонт или страховка" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <div className="form-row">
        <Field label="Вид" required>
          <StyledSelect value={form.kind} options={[
            { value: 'SERVICE', label: 'ТО' },
            { value: 'REPAIR', label: 'Ремонт' },
            { value: 'INSURANCE', label: 'Страховка' },
            { value: 'INSPECTION', label: 'Осмотр' },
          ]} onChange={v => set('kind', v)} />
        </Field>
        <Field label="Дата" required>
          <input type="date" value={form.occurred_on} onChange={e => set('occurred_on', e.target.value)} />
        </Field>
      </div>
      <Field label="Работы / описание">
        <input value={form.works} onChange={e => set('works', e.target.value)} />
      </Field>
      <div className="form-row three">
        <Field label="Пробег">
          <input type="number" value={form.mileage} onChange={e => set('mileage', e.target.value)} />
        </Field>
        <Field label="Стоимость, ₽">
          <input type="number" value={form.cost} onChange={e => set('cost', e.target.value)} />
        </Field>
        <Field label="Исполнитель">
          <input value={form.provider} onChange={e => set('provider', e.target.value)} />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Действует с">
          <input type="date" value={form.valid_from} onChange={e => set('valid_from', e.target.value)} />
        </Field>
        <Field label="по" hint="Для страховки и периодических проверок">
          <input type="date" value={form.valid_to} onChange={e => set('valid_to', e.target.value)} />
        </Field>
      </div>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function ExpenseModal({ vehicleId, categories, onClose, onDone }: {
  vehicleId: number; categories: Reference[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    category_code: categories[0]?.code ?? 'FUEL', spent_on: today(), amount: '', note: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.amount) { setError('Укажите сумму'); return }
    try {
      await client.post(`/api/transport/vehicles/${vehicleId}/expenses`,
        { ...form, amount: Number(form.amount) })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Расход по автомобилю" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <div className="form-row">
        <Field label="Категория" required>
          <StyledSelect value={form.category_code}
                        options={categories.map(c => ({ value: c.code, label: c.name }))}
                        onChange={v => set('category_code', v)} />
        </Field>
        <Field label="Дата" required>
          <input type="date" value={form.spent_on} onChange={e => set('spent_on', e.target.value)} />
        </Field>
      </div>
      <Field label="Сумма, ₽" required>
        <input type="number" value={form.amount} onChange={e => set('amount', e.target.value)} />
      </Field>
      <Field label="Примечание">
        <input value={form.note} onChange={e => set('note', e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function EquipmentTab({ equipment, employees, onChanged }: {
  equipment: Row[]; employees: Employee[]; onChanged: () => void
}) {
  const [create, setCreate] = useState(false)
  return (
    <>
      <button className="btn-secondary" style={{ marginBottom: 12 }} onClick={() => setCreate(true)}>
        + Оборудование
      </button>
      <DataTable
        columns={[
          { key: 'name', title: 'Наименование' },
          { key: 'inventory_no', title: 'Инв. номер', width: 160,
            render: e => e.inventory_no || '—' },
          { key: 'equipment_type', title: 'Тип', width: 180, render: e => e.equipment_type || '—' },
          { key: 'assigned_name', title: 'Закреплено', width: 200, render: e => e.assigned_name || '—' },
          { key: 'status', title: 'Статус', width: 130 },
        ]}
        rows={equipment}
        empty="Оборудование не заведено"
      />
      {create && (
        <EquipmentModal employees={employees} onClose={() => setCreate(false)}
                        onDone={() => { setCreate(false); onChanged() }} />
      )}
    </>
  )
}

function EquipmentModal({ employees, onClose, onDone }: {
  employees: Employee[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    name: '', inventory_no: '', equipment_type: '',
    assigned_employee_id: null as number | null, purchase_cost: '', purchased_on: '', note: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.name.trim()) { setError('Наименование обязательно'); return }
    try {
      await client.post('/api/transport/equipment', {
        ...form,
        purchase_cost: form.purchase_cost ? Number(form.purchase_cost) : null,
        purchased_on: form.purchased_on || null,
      })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Оборудование" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <Field label="Наименование" required>
        <input value={form.name} onChange={e => set('name', e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="Инвентарный номер">
          <input value={form.inventory_no} onChange={e => set('inventory_no', e.target.value)} />
        </Field>
        <Field label="Тип">
          <input value={form.equipment_type} onChange={e => set('equipment_type', e.target.value)} />
        </Field>
      </div>
      <Field label="Закреплено за">
        <StyledSelect value={form.assigned_employee_id}
                      options={employees.map(e => ({ value: e.id, label: e.full_name }))}
                      onChange={v => set('assigned_employee_id', v)} allowEmpty />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
