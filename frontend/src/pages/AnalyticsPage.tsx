import { useState } from 'react'
import DataTable from '../components/DataTable'
import PageHead from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import Tabs from '../components/Tabs'
import { useApi } from '../hooks/useApi'
import { formatDate, formatQty, formatRub, monthStart, today, unitName } from '../utils/format'
import type { Row, Sku } from '../types'

type Tab = 'plan-fact' | 'cost' | 'productivity' | 'period'

/** Аналитика: план-факт, себестоимость, производительность, проверка закрытия месяца. */
export default function AnalyticsPage() {
  const [tab, setTab] = useState<Tab>('plan-fact')
  const [from, setFrom] = useState(monthStart())
  const [to, setTo] = useState(today())

  return (
    <>
      <PageHead
        title="Отчёты и аналитика"
        filters={
          <>
            <input type="date" value={from} onChange={e => setFrom(e.target.value)} style={{ width: 150 }} />
            <input type="date" value={to} onChange={e => setTo(e.target.value)} style={{ width: 150 }} />
          </>
        }
      />

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'plan-fact', label: 'План-факт' },
          { key: 'cost', label: 'Производственная себестоимость' },
          { key: 'productivity', label: 'Производительность' },
          { key: 'period', label: 'Проверка закрытия месяца' },
        ]}
      />

      {tab === 'plan-fact' && <PlanFactTab from={from} to={to} />}
      {tab === 'cost' && <CostTab from={from} to={to} />}
      {tab === 'productivity' && <ProductivityTab from={from} to={to} />}
      {tab === 'period' && <PeriodTab month={from} />}
    </>
  )
}

function PlanFactTab({ from, to }: { from: string; to: string }) {
  const { data, loading, error } = useApi<Row[]>(
    `/api/analytics/plan-fact?from=${from}&to=${to}`, [from, to])

  if (error) return <div className="notice notice-warning">{error}</div>

  return (
    <>
      <div className="notice notice-info">
        Факт собирается из первичных начислений и списаний — введённые вручную итоги
        в расчёт не берутся.
      </div>
      <DataTable
        columns={[
          { key: 'code', title: 'Проект', width: 110 },
          { key: 'name', title: 'Название' },
          { key: 'period_month', title: 'Месяц', width: 110, render: r => formatDate(r.period_month) },
          { key: 'payroll_plan', title: 'План ФОТ', width: 140, align: 'right',
            render: r => formatRub(r.payroll_plan) },
          { key: 'payroll_fact', title: 'Факт ФОТ', width: 140, align: 'right',
            render: r => formatRub(r.payroll_fact) },
          { key: 'payroll_diff', title: 'Отклонение', width: 140, align: 'right',
            render: r => (
              <b style={{ color: Number(r.payroll_diff) > 0 ? 'var(--c-danger)' : undefined }}>
                {formatRub(r.payroll_diff)}
              </b>
            ) },
          { key: 'materials_plan', title: 'План материалов', width: 160, align: 'right',
            render: r => formatRub(r.materials_plan) },
          { key: 'materials_fact', title: 'Факт материалов', width: 160, align: 'right',
            render: r => formatRub(r.materials_fact) },
        ]}
        rows={data || []}
        loading={loading}
        empty="Данных за период нет"
        rowKey={(r, i) => `${r.project_id}-${r.period_month}-${i}`}
      />
    </>
  )
}

function CostTab({ from, to }: { from: string; to: string }) {
  const { data, loading, error } = useApi<Row[]>(
    `/api/analytics/cost/by-project?from=${from}&to=${to}`, [from, to])

  if (error) return <div className="notice notice-warning">{error}</div>

  return (
    <>
      <div className="notice notice-info">
        В производственную себестоимость входят материалы, ФОТ, спецтехника, транспорт и
        амортизация оборудования. Налоги, офис, управленцы и прибыль в стартовый контур
        не включаются.
      </div>
      <DataTable
        columns={[
          { key: 'code', title: 'Проект', width: 110 },
          { key: 'name', title: 'Название' },
          { key: 'materials', title: 'Материалы', width: 150, align: 'right',
            render: r => formatRub(r.materials) },
          { key: 'payroll', title: 'ФОТ', width: 150, align: 'right', render: r => formatRub(r.payroll) },
          { key: 'special_equipment', title: 'Спецтехника', width: 150, align: 'right',
            render: r => formatRub(r.special_equipment) },
          { key: 'total', title: 'Итого', width: 160, align: 'right',
            render: r => (
              <b>{formatRub(Number(r.materials) + Number(r.payroll) + Number(r.special_equipment))}</b>
            ) },
        ]}
        rows={data || []}
        loading={loading}
        empty="Данных за период нет"
      />
    </>
  )
}

function ProductivityTab({ from, to }: { from: string; to: string }) {
  const [skuId, setSkuId] = useState<number | null>(null)
  const { data: skus } = useApi<Sku[]>('/api/sku')
  const { data, loading } = useApi<Row[]>(
    `/api/analytics/productivity?from=${from}&to=${to}${skuId ? `&skuId=${skuId}` : ''}`,
    [from, to, skuId])

  return (
    <>
      <div className="filters">
        <div className="form-group" style={{ minWidth: 300 }}>
          <label>Работа</label>
          <StyledSelect
            value={skuId}
            options={(skus || []).map(s => ({ value: s.id, label: s.name, hint: unitName(s.unit_code) }))}
            onChange={v => setSkuId(v as number | null)}
            placeholder="Все работы"
            allowEmpty
          />
        </div>
      </div>
      <div className="notice notice-info">
        Показатель считается только по совместимым единицам. Изменение норматива —
        отдельное управленческое решение, автоматически на ФОТ оно не влияет.
      </div>
      <DataTable
        columns={[
          { key: 'sku_name', title: 'Работа' },
          { key: 'full_name', title: 'Сотрудник', width: 220 },
          { key: 'qty', title: 'Объём', width: 130, align: 'right',
            render: r => formatQty(r.qty, r.unit_code) },
          { key: 'man_hours', title: 'чел·ч', width: 110, align: 'right',
            render: r => r.man_hours ?? '—' },
          { key: 'per_hour', title: 'На чел·ч', width: 140, align: 'right',
            render: r => r.per_hour ? <b>{formatQty(r.per_hour, r.unit_code)}</b> : '—' },
        ]}
        rows={data || []}
        loading={loading}
        empty="Данных за период нет"
        rowKey={(r, i) => `${r.sku_id}-${r.employee_id}-${i}`}
      />
    </>
  )
}

function PeriodTab({ month }: { month: string }) {
  const { data, loading } = useApi<{
    period_from: string; period_to: string
    open_requests: Row[]; missing_acts: Row[]; draft_payroll: Row[]; unallocated_purchases: Row[]
  }>(`/api/analytics/period-check?month=${month}`, [month])

  if (loading) return <div className="loading">Загрузка…</div>
  if (!data) return null

  const blocks = [
    { title: 'Незакрытые заявки периода', rows: data.open_requests, key: 'number' },
    { title: 'Выполненные заявки без акта', rows: data.missing_acts, key: 'number' },
    { title: 'Черновые начисления ФОТ', rows: data.draft_payroll, key: 'id' },
    { title: 'Нераспределённые закупки', rows: data.unallocated_purchases, key: 'id' },
  ]

  return (
    <>
      <div className="notice notice-info">
        Проверка за период {formatDate(data.period_from)} — {formatDate(data.period_to)}.
        После блокировки периода исправления выполняются корректировками с журналом.
      </div>
      <div className="tiles">
        {blocks.map(b => (
          <div key={b.title} className={`tile static${b.rows.length ? ' warn' : ''}`}>
            <div className="tile-value">{b.rows.length}</div>
            <div className="tile-label">{b.title}</div>
          </div>
        ))}
      </div>
      {blocks.filter(b => b.rows.length > 0).map(b => (
        <div key={b.title} className="card">
          <h2>{b.title}</h2>
          <div className="small">
            {b.rows.slice(0, 50).map((r, i) => (
              <span key={i} style={{ marginRight: 10 }}>
                {String(r[b.key])}{r.status ? ` (${r.status})` : ''}
              </span>
            ))}
            {b.rows.length > 50 && <span className="muted"> … и ещё {b.rows.length - 50}</span>}
          </div>
        </div>
      ))}
    </>
  )
}
