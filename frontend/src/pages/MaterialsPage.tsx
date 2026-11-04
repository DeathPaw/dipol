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
import { daysAgo, formatDate, formatQty, formatRub, unitName } from '../utils/format'
import type { Project, Row, Unit } from '../types'

type Tab = 'stock' | 'movements' | 'catalog' | 'suppliers' | 'orders'

const MOVEMENT_LABELS: Record<string, string> = {
  RECEIPT: 'Поступление', ISSUE: 'Списание', RETURN: 'Возврат', ADJUST: 'Корректировка',
}

/** Материалы: номенклатура, поставщики, заказы, остатки и журнал движений. */
export default function MaterialsPage() {
  const { showToast } = useToast()
  const [tab, setTab] = useState<Tab>('stock')
  const [issueOpen, setIssueOpen] = useState(false)
  const [receiptOpen, setReceiptOpen] = useState(false)

  const { data: balances, loading: balancesLoading, reload: reloadBalances } =
    useApi<Row[]>('/api/materials/stock/balances')
  const { data: materials, reload: reloadMaterials } = useApi<Row[]>('/api/materials')
  const { data: suppliers, reload: reloadSuppliers } = useApi<Row[]>('/api/materials/suppliers')

  return (
    <>
      <PageHead
        title="Материалы и склад"
        right={
          <div className="actions">
            <button className="btn-secondary" onClick={() => setReceiptOpen(true)}>Поступление</button>
            <button className="btn-primary" style={pageActionBtn} onClick={() => setIssueOpen(true)}>
              Списание «с колёс»
            </button>
          </div>
        }
      />

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'stock', label: 'Остатки', hint: balances?.length ?? 0 },
          { key: 'movements', label: 'Движения' },
          { key: 'catalog', label: 'Номенклатура', hint: materials?.length ?? 0 },
          { key: 'suppliers', label: 'Поставщики', hint: suppliers?.length ?? 0 },
          { key: 'orders', label: 'Заказы поставщику' },
        ]}
      />

      {tab === 'stock' && (
        <>
          <div className="notice notice-info">
            Остаток считается из журнала движений: поступления минус списания плюс возвраты.
            Позиции с нулевым остатком не показываются — их видно во вкладке «Движения».
          </div>
          <DataTable
            columns={[
              { key: 'name', title: 'Материал' },
              { key: 'group_name', title: 'Группа', width: 200, render: r => r.group_name || '—' },
              { key: 'qty', title: 'Остаток', width: 140, align: 'right',
                render: r => formatQty(r.qty, r.unit_code) },
              { key: 'amount', title: 'Стоимость', width: 150, align: 'right',
                render: r => formatRub(r.amount) },
              { key: 'avg', title: 'Средняя цена', width: 140, align: 'right',
                render: r => Number(r.qty) > 0 ? formatRub(Number(r.amount) / Number(r.qty)) : '—' },
            ]}
            rows={balances || []}
            loading={balancesLoading}
            empty="Остатков нет: всё, что поступало, списано"
          />
        </>
      )}

      {tab === 'movements' && <MovementsTab onChanged={reloadBalances} />}

      {tab === 'catalog' && (
        <CatalogTab materials={materials || []} onChanged={() => { reloadMaterials(); showToast('Сохранено') }} />
      )}

      {tab === 'suppliers' && (
        <SuppliersTab suppliers={suppliers || []} onChanged={() => { reloadSuppliers(); showToast('Сохранено') }} />
      )}

      {tab === 'orders' && <OrdersTab />}

      {receiptOpen && (
        <MovementModal
          mode="receipt"
          materials={materials || []}
          suppliers={suppliers || []}
          onClose={() => setReceiptOpen(false)}
          onDone={() => { setReceiptOpen(false); showToast('Поступление проведено'); reloadBalances() }}
        />
      )}
      {issueOpen && (
        <MovementModal
          mode="on-wheels"
          materials={materials || []}
          suppliers={suppliers || []}
          onClose={() => setIssueOpen(false)}
          onDone={() => { setIssueOpen(false); showToast('Движение проведено'); reloadBalances() }}
        />
      )}
    </>
  )
}

function MovementsTab({ onChanged }: { onChanged: () => void }) {
  const { showToast } = useToast()
  const [from, setFrom] = useState(daysAgo(30))
  const [projectId, setProjectId] = useState<number | null>(null)
  const { data: projects } = useApi<Project[]>('/api/projects')

  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (projectId) params.set('projectId', String(projectId))

  const { data, loading, reload } =
    useApi<Row[]>(`/api/materials/stock/movements?${params.toString()}`, [params.toString()])

  const reverse = async (id: number) => {
    const reason = window.prompt('Основание отмены движения')
    if (!reason) return
    try {
      await client.post(`/api/materials/stock/movements/${id}/reverse`, { reason })
      showToast('Проведено обратное движение')
      reload()
      onChanged()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const columns: Column<Row>[] = [
    { key: 'moved_at', title: 'Дата', width: 110, render: m => formatDate(m.moved_at) },
    { key: 'movement_type', title: 'Тип', width: 130,
      render: m => (
        <span className={`badge ${m.movement_type === 'RECEIPT' ? 'badge-ok'
          : m.movement_type === 'ISSUE' ? 'badge-info' : 'badge-warn'}`}>
          {MOVEMENT_LABELS[m.movement_type] || m.movement_type}
        </span>
      ) },
    { key: 'material_name', title: 'Материал' },
    { key: 'qty', title: 'Количество', width: 130, align: 'right',
      render: m => formatQty(m.qty, m.unit_code) },
    { key: 'amount', title: 'Сумма', width: 130, align: 'right', render: m => formatRub(m.amount) },
    { key: 'project_code', title: 'Проект', width: 100, render: m => m.project_code || '—' },
    { key: 'request_number', title: 'Заявка', width: 120, render: m => m.request_number || '—' },
    { key: 'on_wheels', title: '', width: 90,
      render: m => m.on_wheels ? <span className="badge badge-neutral">с колёс</span> : null },
    { key: 'actions', title: '', width: 90,
      render: m => m.reverses_id ? <span className="muted small">сторно</span> : (
        <button className="btn-secondary btn-sm" onClick={() => reverse(Number(m.id))}>Отменить</button>
      ) },
  ]

  return (
    <>
      <div className="filters">
        <div className="form-group">
          <label>Период с</label>
          <input type="date" value={from} onChange={e => setFrom(e.target.value)} />
        </div>
        <div className="form-group" style={{ minWidth: 220 }}>
          <label>Проект</label>
          <StyledSelect
            value={projectId}
            options={(projects || []).map(p => ({ value: p.id, label: p.code }))}
            onChange={v => setProjectId(v as number | null)}
            placeholder="Все проекты"
            allowEmpty
          />
        </div>
      </div>
      <div className="notice notice-info">
        Отмена движения оформляется обратной записью — исходная операция остаётся в журнале,
        иначе остаток и себестоимость перестанут быть воспроизводимыми.
      </div>
      <DataTable columns={columns} rows={data || []} loading={loading} empty="Движений за период нет" />
    </>
  )
}

function CatalogTab({ materials, onChanged }: { materials: Row[]; onChanged: () => void }) {
  const [editing, setEditing] = useState<Row | 'new' | null>(null)
  const { data: units } = useApi<Unit[]>('/api/references/units')

  return (
    <>
      <button className="btn-secondary" style={{ marginBottom: 12 }} onClick={() => setEditing('new')}>
        + Материал
      </button>
      <DataTable
        columns={[
          { key: 'name', title: 'Материал' },
          { key: 'group_name', title: 'Группа', width: 200, render: m => m.group_name || '—' },
          { key: 'unit_code', title: 'Единица', width: 110, render: m => unitName(m.unit_code) },
          { key: 'external_1c_code', title: 'Код 1С', width: 140, render: m => m.external_1c_code || '—' },
          { key: 'is_active', title: 'Активен', width: 100, render: m => m.is_active ? 'да' : 'нет' },
          { key: 'actions', title: '', width: 100,
            render: m => (
              <button className="btn-secondary btn-sm"
                      onClick={e => { e.stopPropagation(); setEditing(m) }}>Правка</button>
            ) },
        ]}
        rows={materials}
        empty="Номенклатура пуста"
      />
      {editing && (
        <MaterialModal
          value={editing === 'new' ? null : editing}
          units={units || []}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); onChanged() }}
        />
      )}
    </>
  )
}

function MaterialModal({ value, units, onClose, onDone }: {
  value: Row | null; units: Unit[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    name: value?.name ?? '',
    code: value?.code ?? '',
    unit_code: value?.unit_code ?? 'PCS',
    external_1c_code: value?.external_1c_code ?? '',
    area_per_unit: value?.area_per_unit ?? '',
    is_active: value?.is_active ?? true,
    note: value?.note ?? '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.name.trim()) { setError('Название обязательно'); return }
    try {
      if (value) await client.put(`/api/materials/${value.id}`, form)
      else await client.post('/api/materials', form)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title={value ? 'Материал' : 'Новый материал'}
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Сохранить</button>
        </>
      }
    >
      <Field label="Название" required>
        <input value={form.name} onChange={e => set('name', e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="Единица">
          <StyledSelect
            value={form.unit_code}
            options={units.map(u => ({ value: u.code, label: u.name }))}
            onChange={v => set('unit_code', v)}
          />
        </Field>
        <Field label="Код 1С" hint="Нужен для будущей синхронизации склада">
          <input value={form.external_1c_code ?? ''}
                 onChange={e => set('external_1c_code', e.target.value)} />
        </Field>
      </div>
      <Field label="Площадь в единице, м²" hint="Для рулонных материалов: считать цену за м²">
        <input type="number" step="0.001" value={form.area_per_unit ?? ''}
               onChange={e => set('area_per_unit', e.target.value)} />
      </Field>
      <label className="toggle-box">
        <input type="checkbox" checked={!!form.is_active} onChange={e => set('is_active', e.target.checked)} />
        Активен
      </label>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function SuppliersTab({ suppliers, onChanged }: { suppliers: Row[]; onChanged: () => void }) {
  const [editing, setEditing] = useState<Row | 'new' | null>(null)

  return (
    <>
      <button className="btn-secondary" style={{ marginBottom: 12 }} onClick={() => setEditing('new')}>
        + Поставщик
      </button>
      <DataTable
        columns={[
          { key: 'name', title: 'Поставщик' },
          { key: 'inn', title: 'ИНН', width: 150, render: s => s.inn || '—' },
          { key: 'phone', title: 'Телефон', width: 160, render: s => s.phone || '—' },
          { key: 'terms', title: 'Условия', render: s => s.terms || '—' },
          { key: 'is_active', title: 'Активен', width: 100, render: s => s.is_active ? 'да' : 'нет' },
          { key: 'actions', title: '', width: 100,
            render: s => (
              <button className="btn-secondary btn-sm" onClick={() => setEditing(s)}>Правка</button>
            ) },
        ]}
        rows={suppliers}
        empty="Поставщиков нет"
      />
      {editing && (
        <SupplierModal
          value={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); onChanged() }}
        />
      )}
    </>
  )
}

function SupplierModal({ value, onClose, onDone }: {
  value: Row | null; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    name: value?.name ?? '', inn: value?.inn ?? '', phone: value?.phone ?? '',
    email: value?.email ?? '', contact: value?.contact ?? '', terms: value?.terms ?? '',
    is_active: value?.is_active ?? true, note: value?.note ?? '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    try {
      if (value) await client.put(`/api/materials/suppliers/${value.id}`, form)
      else await client.post('/api/materials/suppliers', form)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title={value ? 'Поставщик' : 'Новый поставщик'} onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <Field label="Название" required>
        <input value={form.name} onChange={e => set('name', e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="ИНН"><input value={form.inn ?? ''} onChange={e => set('inn', e.target.value)} /></Field>
        <Field label="Телефон"><input value={form.phone ?? ''} onChange={e => set('phone', e.target.value)} /></Field>
      </div>
      <Field label="Условия">
        <input value={form.terms ?? ''} onChange={e => set('terms', e.target.value)} />
      </Field>
      <label className="toggle-box">
        <input type="checkbox" checked={!!form.is_active} onChange={e => set('is_active', e.target.checked)} />
        Активен
      </label>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function OrdersTab() {
  const [from, setFrom] = useState(daysAgo(90))
  const { data, loading } = useApi<Row[]>(`/api/materials/orders?from=${from}`, [from])

  return (
    <>
      <div className="filters">
        <div className="form-group">
          <label>Период с</label>
          <input type="date" value={from} onChange={e => setFrom(e.target.value)} />
        </div>
      </div>
      <div className="notice notice-info">
        Номер заказа больше не дублируется ради разнесения по проектам: строка заказа
        распределяется отдельными записями.
      </div>
      <DataTable
        columns={[
          { key: 'number', title: 'Номер', width: 200 },
          { key: 'supplier_name', title: 'Поставщик', width: 220, render: o => o.supplier_name || '—' },
          { key: 'ordered_at', title: 'Дата', width: 120, render: o => formatDate(o.ordered_at) },
          { key: 'lines_count', title: 'Позиций', width: 100, align: 'right' },
          { key: 'lines_total', title: 'Сумма', width: 150, align: 'right',
            render: o => formatRub(o.lines_total) },
          { key: 'status', title: 'Статус', width: 130 },
        ]}
        rows={data || []}
        loading={loading}
        empty="Заказов за период нет"
      />
    </>
  )
}

/** Поступление и «с колёс»: одна форма, разные конечные точки API. */
function MovementModal({ mode, materials, suppliers, onClose, onDone }: {
  mode: 'receipt' | 'on-wheels'
  materials: Row[]
  suppliers: Row[]
  onClose: () => void
  onDone: () => void
}) {
  const { data: projects } = useApi<Project[]>('/api/projects?status=ACTIVE')
  const [form, setForm] = useState({
    material_id: null as number | null,
    qty: '',
    amount: '',
    supplier_id: null as number | null,
    project_id: null as number | null,
    order_number: '',
    comment: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const material = materials.find(m => m.id === form.material_id)

  const submit = async () => {
    if (!form.material_id || !form.qty) { setError('Материал и количество обязательны'); return }
    try {
      await client.post(`/api/materials/stock/${mode}`, {
        ...form,
        unit_code: material?.unit_code ?? 'PCS',
        qty: Number(form.qty),
        amount: form.amount ? Number(form.amount) : null,
      })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title={mode === 'receipt' ? 'Поступление материала' : 'Материал «с колёс»'}
      size="large"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Провести</button>
        </>
      }
    >
      {mode === 'on-wheels' && (
        <div className="notice notice-info">
          Оформляется поступление и сразу связанное списание на проект — без физического
          хранения. Обе записи остаются в журнале движений.
        </div>
      )}
      <Field label="Материал" required>
        <StyledSelect
          value={form.material_id}
          options={materials.filter(m => m.is_active)
            .map(m => ({ value: Number(m.id), label: String(m.name), hint: unitName(m.unit_code) }))}
          onChange={v => set('material_id', v)}
        />
      </Field>
      <div className="form-row three">
        <Field label={`Количество, ${unitName(material?.unit_code)}`} required>
          <input type="number" step="0.001" value={form.qty} onChange={e => set('qty', e.target.value)} />
        </Field>
        <Field label="Сумма, ₽">
          <input type="number" step="0.01" value={form.amount} onChange={e => set('amount', e.target.value)} />
        </Field>
        <Field label="Номер заказа">
          <input value={form.order_number} onChange={e => set('order_number', e.target.value)} />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Поставщик">
          <StyledSelect
            value={form.supplier_id}
            options={suppliers.map(s => ({ value: Number(s.id), label: String(s.name) }))}
            onChange={v => set('supplier_id', v)}
            allowEmpty
          />
        </Field>
        <Field label="Проект" required={mode === 'on-wheels'}>
          <StyledSelect
            value={form.project_id}
            options={(projects || []).map(p => ({ value: p.id, label: p.code }))}
            onChange={v => set('project_id', v)}
            allowEmpty
          />
        </Field>
      </div>
      <Field label="Комментарий">
        <input value={form.comment} onChange={e => set('comment', e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
