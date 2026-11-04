import { useState } from 'react'
import client from '../api/client'
import DataTable from '../components/DataTable'
import PageHead from '../components/PageHead'
import Tabs from '../components/Tabs'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDate, formatRub, monthStart, today } from '../utils/format'
import type { Row } from '../types'

interface Bridge {
  period_from: string
  period_to: string
  default_white_limit: number | null
  by_employee_project: Row[]
  by_project: Row[]
  rule_note: string
}

/**
 * Отчёт «Мост» — раскладка начисленного ФОТ на официальную и кэш-часть
 * с разнесением по проектам за произвольный период (US-J6).
 */
export default function BridgePage() {
  const { showToast } = useToast()
  const [from, setFrom] = useState(monthStart())
  const [to, setTo] = useState(today())
  const [tab, setTab] = useState<'projects' | 'employees'>('projects')
  const [detailsFor, setDetailsFor] = useState<Row | null>(null)

  const { data, loading } = useApi<Bridge>(
    `/api/bridge?from=${from}&to=${to}&includeMaterials=true`, [from, to])

  const save = async () => {
    await client.post(`/api/bridge/save?from=${from}&to=${to}`)
    showToast('Снимок отчёта сохранён')
  }

  const totals = (data?.by_project || []).reduce((acc, p) => ({
    accrued: acc.accrued + Number(p.accrued || 0),
    official: acc.official + Number(p.official || 0),
    cash: acc.cash + Number(p.cash || 0),
    materials: acc.materials + Number(p.materials || 0),
    total: acc.total + Number(p.total || 0),
  }), { accrued: 0, official: 0, cash: 0, materials: 0, total: 0 })

  return (
    <>
      <PageHead
        title="Отчёт «Мост»"
        filters={
          <>
            <input type="date" value={from} onChange={e => setFrom(e.target.value)} style={{ width: 150 }} />
            <input type="date" value={to} onChange={e => setTo(e.target.value)} style={{ width: 150 }} />
          </>
        }
        right={<button className="btn-secondary" onClick={save}>Сохранить снимок</button>}
      />

      <div className="notice notice-warning">
        {data?.rule_note || 'Правило распределения официальной и кэш-части требует утверждения.'}
        {data?.default_white_limit !== null && data?.default_white_limit !== undefined && (
          <> Текущий лимит официальной части: <b>{formatRub(data.default_white_limit)}</b> в месяц.</>
        )}
      </div>

      <div className="tiles">
        <div className="tile static">
          <div className="tile-value" style={{ fontSize: 18 }}>{formatRub(totals.accrued)}</div>
          <div className="tile-label">Начислено ФОТ</div>
        </div>
        <div className="tile static">
          <div className="tile-value" style={{ fontSize: 18 }}>{formatRub(totals.official)}</div>
          <div className="tile-label">Официальная часть</div>
        </div>
        <div className="tile static">
          <div className="tile-value" style={{ fontSize: 18 }}>{formatRub(totals.cash)}</div>
          <div className="tile-label">Кэш-часть</div>
        </div>
        <div className="tile static">
          <div className="tile-value" style={{ fontSize: 18 }}>{formatRub(totals.materials)}</div>
          <div className="tile-label">Материалы</div>
        </div>
        <div className="tile static">
          <div className="tile-value" style={{ fontSize: 18 }}>{formatRub(totals.total)}</div>
          <div className="tile-label">Итого</div>
        </div>
      </div>

      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'projects', label: 'По проектам', hint: data?.by_project.length ?? 0 },
          { key: 'employees', label: 'По сотрудникам', hint: data?.by_employee_project.length ?? 0 },
        ]}
      />

      {tab === 'projects' && (
        <DataTable
          columns={[
            { key: 'project_code', title: 'Проект', width: 120 },
            { key: 'project_name', title: 'Название' },
            { key: 'accrued', title: 'Начислено', width: 140, align: 'right',
              render: p => formatRub(p.accrued) },
            { key: 'official', title: 'Белая часть', width: 140, align: 'right',
              render: p => formatRub(p.official) },
            { key: 'cash', title: 'Кэш', width: 140, align: 'right', render: p => formatRub(p.cash) },
            { key: 'materials', title: 'Материалы', width: 140, align: 'right',
              render: p => formatRub(p.materials) },
            { key: 'total', title: 'Итого', width: 150, align: 'right',
              render: p => <b>{formatRub(p.total)}</b> },
          ]}
          rows={data?.by_project || []}
          loading={loading}
          empty="За период начислений нет"
          rowKey={(r, i) => String(r.project_id ?? i)}
          onRowClick={setDetailsFor}
        />
      )}

      {tab === 'employees' && (
        <DataTable
          columns={[
            { key: 'employee_name', title: 'Сотрудник', width: 220 },
            { key: 'project_code', title: 'Проект', width: 120 },
            { key: 'accrued', title: 'Начислено', width: 140, align: 'right',
              render: p => formatRub(p.accrued) },
            { key: 'official', title: 'Белая часть', width: 140, align: 'right',
              render: p => formatRub(p.official) },
            { key: 'cash', title: 'Кэш', width: 140, align: 'right', render: p => formatRub(p.cash) },
          ]}
          rows={data?.by_employee_project || []}
          loading={loading}
          empty="За период начислений нет"
          rowKey={(r, i) => `${r.employee_id}-${r.project_id}-${i}`}
          onRowClick={setDetailsFor}
        />
      )}

      {detailsFor && (
        <DetailsModal row={detailsFor} from={from} to={to} onClose={() => setDetailsFor(null)} />
      )}
    </>
  )
}

/** Раскрытие строки до конкретных начислений — иначе итог не проверить. */
function DetailsModal({ row, from, to, onClose }: {
  row: Row; from: string; to: string; onClose: () => void
}) {
  const params = new URLSearchParams({ from, to })
  if (row.employee_id) params.set('employeeId', String(row.employee_id))
  if (row.project_id) params.set('projectId', String(row.project_id))
  const { data } = useApi<Row[]>(`/api/bridge/details?${params.toString()}`, [params.toString()])

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal wide" onClick={e => e.stopPropagation()}>
        <h2>Начисления: {row.employee_name || row.project_name}</h2>
        <DataTable
          columns={[
            { key: 'entry_date', title: 'Дата', width: 110, render: e => formatDate(e.entry_date) },
            { key: 'employee_name', title: 'Сотрудник', width: 200 },
            { key: 'project_code', title: 'Проект', width: 100 },
            { key: 'sku_name', title: 'Работа', render: e => e.sku_name || '—' },
            { key: 'request_number', title: 'Заявка', width: 130, render: e => e.request_number || '—' },
            { key: 'amount', title: 'Сумма', width: 130, align: 'right', render: e => formatRub(e.amount) },
            { key: 'formula', title: 'Расчёт', render: e => <span className="small muted">{e.formula}</span> },
          ]}
          rows={data || []}
          empty="Начислений нет"
        />
        <div className="modal-actions">
          <button className="btn-secondary" onClick={onClose}>Закрыть</button>
        </div>
      </div>
    </div>
  )
}
