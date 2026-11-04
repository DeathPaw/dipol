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
import { formatDate, formatQty, formatRub } from '../utils/format'
import type { Project, Row } from '../types'

type Tab = 'queue' | 'objects' | 'surveys'

/**
 * РК-3: очередь домов, дефектовка и объекты большого ремонта.
 *
 * Выплата вырубки считается как часы × базовая ставка × k, где k берётся из
 * версионируемых диапазонов. Подстановка p вместо k — ошибка действующей
 * таблицы — здесь невозможна (US-F6).
 */
export default function Rk3Page() {
  const { showToast } = useToast()
  const [tab, setTab] = useState<Tab>('queue')
  const [year, setYear] = useState(new Date().getFullYear())

  const { data: projects } = useApi<Project[]>('/api/projects')
  const rk3Project = projects?.find(p => p.code === 'RK3')

  const { data: queue, loading: queueLoading, reload: reloadQueue } =
    useApi<Row[]>(`/api/rk3/queue?year=${year}`, [year])
  const { data: objects, loading: objectsLoading, reload: reloadObjects } =
    useApi<Row[]>(`/api/rk3/objects?year=${year}`, [year])
  const { data: surveys } = useApi<Row[]>('/api/rk3/defect-surveys')

  const [openObject, setOpenObject] = useState<number | null>(null)

  const rebuild = async () => {
    try {
      const res = await client.post(
        `/api/rk3/queue/rebuild?year=${year}${rk3Project ? `&projectId=${rk3Project.id}` : ''}`)
      showToast(res.data.warning
        ? `Очередь пересчитана. ${res.data.warning}`
        : `Очередь пересчитана: ${res.data.updated} домов`,
        res.data.warning ? 'warning' : 'success')
      reloadQueue()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const queueColumns: Column<Row>[] = [
    { key: 'address', title: 'Адрес' },
    { key: 'zheu_name', title: 'ЖЭУ', width: 200, render: q => q.zheu_name || '—' },
    { key: 'entrances_count', title: 'Подъездов', width: 100, align: 'right' },
    { key: 'requests_total', title: 'Заявок', width: 90, align: 'right' },
    { key: 'requests_active', title: 'Активных', width: 100, align: 'right',
      render: q => <b>{q.requests_active}</b> },
    { key: 'suggested', title: 'Рекомендация', width: 140,
      render: q => q.suggested_entrances || <span className="muted">—</span> },
    { key: 'approved_entrances', title: 'Утверждено', width: 140,
      render: q => q.approved_entrances || <span className="muted">не утверждено</span> },
    { key: 'estimated_roof_m2', title: 'Объём, м²', width: 120, align: 'right',
      render: q => formatQty(q.estimated_roof_m2) },
    { key: 'status', title: 'Статус', width: 140, render: q => q.status || '—' },
  ]

  const objectColumns: Column<Row>[] = [
    { key: 'act_number', title: 'Номер акта', width: 140, render: o => <b>{o.act_number}</b> },
    { key: 'address', title: 'Адрес' },
    { key: 'defect_date', title: 'Дефектовка', width: 120, render: o => formatDate(o.defect_date) },
    { key: 'accepted_at', title: 'Приёмка', width: 120, render: o => formatDate(o.accepted_at) },
    { key: 'accepted_demolition_m2', title: 'Вырубка, м²', width: 130, align: 'right',
      render: o => formatQty(o.accepted_demolition_m2) },
    { key: 'fact_demolition_man_hours', title: 'чел·ч', width: 100, align: 'right',
      render: o => o.fact_demolition_man_hours ?? '—' },
    { key: 'productivity', title: 'p', width: 90, align: 'right',
      render: o => o.productivity ?? '—' },
    { key: 'coefficient_k', title: 'k', width: 80, align: 'right',
      render: o => o.coefficient_k ?? '—' },
    { key: 'payroll_demolition', title: 'ФОТ вырубки', width: 150, align: 'right',
      render: o => formatRub(o.payroll_demolition) },
    { key: 'status', title: 'Статус', width: 130 },
  ]

  return (
    <>
      <PageHead
        title="РК-3"
        filters={
          <StyledSelect
            value={year}
            options={[year + 1, year, year - 1, year - 2].map(y => ({ value: y, label: String(y) }))}
            onChange={v => setYear(Number(v))}
            width={140}
          />
        }
        right={
          tab === 'queue'
            ? <button className="btn-primary" onClick={rebuild}>Пересчитать очередь</button>
            : null
        }
      />

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'queue', label: 'Очередь домов', hint: queue?.length ?? 0 },
          { key: 'objects', label: 'Объекты ремонта', hint: objects?.length ?? 0 },
          { key: 'surveys', label: 'Дефектовка', hint: surveys?.length ?? 0 },
        ]}
      />

      {tab === 'queue' && (
        <>
          <div className="notice notice-info">
            Очередь считается из первичного реестра заявок: активные обращения, повторы и
            история локремонтов по каждому дому. Рекомендация алгоритма хранится отдельно
            от утверждённого решения прораба.
          </div>
          <DataTable columns={queueColumns} rows={queue || []} loading={queueLoading}
                     empty="Очередь не рассчитана — нажмите «Пересчитать очередь»" />
        </>
      )}

      {tab === 'objects' && (
        <DataTable columns={objectColumns} rows={objects || []} loading={objectsLoading}
                   empty="Объектов за год нет"
                   onRowClick={o => setOpenObject(Number(o.id))} />
      )}

      {tab === 'surveys' && (
        <DataTable
          columns={[
            { key: 'survey_date', title: 'Дата', width: 120, render: s => formatDate(s.survey_date) },
            { key: 'address', title: 'Адрес' },
            { key: 'author_name', title: 'Автор', width: 200, render: s => s.author_name || '—' },
            { key: 'entrances', title: 'Подъезды', width: 140, render: s => s.entrances || '—' },
            { key: 'estimated_roof_m2', title: 'Кровля, м²', width: 130, align: 'right',
              render: s => formatQty(s.estimated_roof_m2) },
            { key: 'estimated_demolition_m2', title: 'Вырубка, м²', width: 130, align: 'right',
              render: s => formatQty(s.estimated_demolition_m2) },
            { key: 'status', title: 'Статус', width: 120 },
          ]}
          rows={surveys || []}
          empty="Дефектовок нет"
        />
      )}

      {openObject && (
        <ObjectCard objectId={openObject} onClose={() => setOpenObject(null)}
                    onChanged={reloadObjects} />
      )}
    </>
  )
}

function ObjectCard({ objectId, onClose, onChanged }: {
  objectId: number; onClose: () => void; onChanged: () => void
}) {
  const { showToast } = useToast()
  const { data, reload } = useApi<{ object: Row; lines: Row[]; corrections: Row[] }>(
    `/api/rk3/objects/${objectId}`, [objectId])
  const [demolition, setDemolition] = useState('')
  const [hours, setHours] = useState('')
  const [calc, setCalc] = useState<Row | null>(null)

  if (!data) return null
  const o = data.object

  const saveFact = async () => {
    try {
      await client.put(`/api/rk3/objects/${objectId}`, {
        accepted_demolition_m2: demolition || o.accepted_demolition_m2,
        fact_demolition_man_hours: hours || o.fact_demolition_man_hours,
        accepted_at: o.accepted_at,
        status: o.status,
        correction_reason: o.status === 'CLOSED'
          ? window.prompt('Объект закрыт. Основание корректировки принятого объёма') : null,
      })
      showToast('Факт сохранён')
      reload()
      onChanged()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const runCalc = async () => {
    const res = await client.post(`/api/rk3/objects/${objectId}/calc-demolition`)
    setCalc(res.data)
    reload()
  }

  return (
    <Modal
      title={`РК-3: ${o.act_number} — ${o.address}`}
      size="wide"
      onClose={onClose}
      actions={<button className="btn-secondary" onClick={onClose}>Закрыть</button>}
    >
      <div className="detail-layout">
        <div>
          <div className="card">
            <h2>Снабжение и спецзатраты</h2>
            <dl className="kv">
              <dt>Дата отгрузки</dt><dd>{formatDate(o.shipment_date)}</dd>
              <dt>Заказ поставщику</dt><dd>{o.supplier_order_number || '—'}</dd>
              <dt>Доставка</dt><dd>{formatRub(o.delivery_cost)}</dd>
              <dt>Разгрузка</dt><dd>{formatRub(o.unload_cost)}</dd>
              <dt>Способ подъёма</dt><dd>{o.lift_method || '—'}</dd>
              <dt>Стоимость подъёма</dt><dd>{formatRub(o.lift_cost)}</dd>
              <dt>Вывоз мусора</dt><dd>{formatRub(o.waste_cost)}</dd>
              <dt>Бригада кровельщиков</dt><dd>{o.crew_roofers || '—'}</dd>
              <dt>Бригада демонтажа</dt><dd>{o.crew_demolition || '—'}</dd>
            </dl>
          </div>

          <div className="card">
            <h2>Объёмы</h2>
            <table>
              <thead>
                <tr><th>Вид</th><th>Слой / материал</th>
                    <th className="num">План</th><th className="num">Принято</th></tr>
              </thead>
              <tbody>
                {data.lines.map(l => (
                  <tr key={l.id}>
                    <td>{l.line_kind === 'DEMOLITION' ? 'Вырубка' : 'Монтаж'}</td>
                    <td>{l.layer_name || l.material_name || '—'}</td>
                    <td className="num">{formatQty(l.planned_qty, l.unit_code)}</td>
                    <td className="num"><b>{formatQty(l.accepted_qty, l.unit_code)}</b></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div>
          <div className="card">
            <h2>Расчёт вырубки</h2>
            <div className="notice notice-info">
              Выплата = часы × базовая ставка × k. Коэффициент k выбирается по
              производительности p = принятые м² / чел·ч из версионируемой таблицы диапазонов.
            </div>
            <Field label="Принятая вырубка, м²">
              <input type="number" value={demolition || o.accepted_demolition_m2 || ''}
                     onChange={e => setDemolition(e.target.value)} />
            </Field>
            <Field label="Фактические чел·ч">
              <input type="number" value={hours || o.fact_demolition_man_hours || ''}
                     onChange={e => setHours(e.target.value)} />
            </Field>
            <div className="actions">
              <button className="btn-secondary" onClick={saveFact}>Сохранить факт</button>
              <button className="btn-primary" onClick={runCalc}>Рассчитать</button>
            </div>

            {calc && (
              <div className={`notice ${calc.error ? 'notice-warning' : 'notice-success'}`}
                   style={{ marginTop: 12 }}>
                {calc.error ? calc.error : (
                  <>
                    p = {calc.productivity}, k = {calc.coefficient},
                    ставка {formatRub(calc.base_rate)}<br />
                    <b>{formatRub(calc.amount)}</b><br />
                    <span className="small">{calc.formula}</span>
                  </>
                )}
              </div>
            )}
          </div>

          {data.corrections.length > 0 && (
            <div className="card">
              <h2>Корректировки</h2>
              <div className="timeline">
                {data.corrections.map(c => (
                  <div key={c.id} className="timeline-item">
                    <div>{c.field}: {c.old_value} → {c.new_value}</div>
                    <div className="timeline-meta">{formatDate(c.created_at)} · {c.author} · {c.reason}</div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </Modal>
  )
}
