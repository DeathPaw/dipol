import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import client, { apiError } from '../api/client'
import DataTable, { type Column } from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead from '../components/PageHead'
import StatusBadge from '../components/StatusBadge'
import Tabs from '../components/Tabs'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDate } from '../utils/format'
import type { Building, BuildingEntrance, PageResponse, Request, Row, Zheu } from '../types'
import { BuildingModal } from './BuildingsPage'

type Tab = 'requests' | 'entrances' | 'aliases'

/** Карточка дома: история работ, матрица подъездов и алиасы адреса. */
export default function BuildingDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { showToast } = useToast()
  const [tab, setTab] = useState<Tab>('requests')
  const [edit, setEdit] = useState(false)
  const [mergeOpen, setMergeOpen] = useState(false)

  const { data: building, reload } = useApi<Building>(`/api/buildings/${id}`, [id])
  const { data: summary } = useApi<Row>(`/api/buildings/${id}/summary`, [id])
  const { data: zheu } = useApi<Zheu[]>('/api/zheu')
  const { data: requests } = useApi<PageResponse<Request>>(
    `/api/requests?buildingId=${id}&size=200`, [id])
  const { data: entrances, reload: reloadEntrances } =
    useApi<BuildingEntrance[]>(`/api/buildings/${id}/entrances`, [id])
  const { data: aliases, reload: reloadAliases } = useApi<Row[]>(`/api/buildings/${id}/aliases`, [id])

  if (!building) return <div className="loading">Загрузка…</div>

  const requestColumns: Column<Request>[] = [
    { key: 'number', title: '№', width: 130, nowrap: true },
    { key: 'received_at', title: 'Поступила', width: 105, render: r => formatDate(r.received_at) },
    { key: 'project_code', title: 'Проект', width: 90 },
    { key: 'apartment', title: 'Кв.', width: 70, render: r => r.apartment || '—' },
    { key: 'description', title: 'Описание' },
    { key: 'status', title: 'Статус', width: 150,
      render: r => <StatusBadge status={r.status} name={r.status_name} /> },
    { key: 'done_at', title: 'Выполнена', width: 105, render: r => formatDate(r.done_at) },
  ]

  return (
    <>
      <PageHead
        title={building.address}
        right={
          <div className="actions">
            <button className="btn-secondary btn-sm" onClick={() => setMergeOpen(true)}>
              Объединить с дублем
            </button>
            <button className="btn-primary btn-sm" onClick={() => setEdit(true)}>Редактировать</button>
          </div>
        }
        extra={
          <div className="muted small">
            ID дома: <b>{building.legacy_id ?? building.id}</b>
            {building.zheu_name && <> · ЖЭУ: {building.zheu_name}</>}
            {building.roof_type && <> · кровля: {building.roof_type}</>}
            {building.floors && <> · этажей: {building.floors}</>}
            {building.entrances_count && <> · подъездов: {building.entrances_count}</>}
            {building.apartments_count && <> · квартир: {building.apartments_count}</>}
          </div>
        }
      />

      {!building.is_active && (
        <div className="notice notice-warning">
          Дом объединён с другим{building.merged_into_id ? ` (id=${building.merged_into_id})` : ''}.
          Карточка сохранена для истории.
        </div>
      )}

      <div className="tiles">
        <div className="tile static">
          <div className="tile-value">{summary?.requests_total ?? 0}</div>
          <div className="tile-label">Всего заявок</div>
        </div>
        <div className="tile static">
          <div className="tile-value">{summary?.requests_active ?? 0}</div>
          <div className="tile-label">Активных</div>
        </div>
        <div className="tile static">
          <div className="tile-value">{summary?.warranty_count ?? 0}</div>
          <div className="tile-label">Гарантийных</div>
        </div>
        <div className="tile static">
          <div className="tile-value">{summary?.rk3_count ?? 0}</div>
          <div className="tile-label">Объектов РК-3</div>
        </div>
        <div className="tile static">
          <div className="tile-value" style={{ fontSize: 16 }}>
            {formatDate(summary?.last_done_at)}
          </div>
          <div className="tile-label">Последняя работа</div>
        </div>
      </div>

      {(building.roof_access || building.attic_access || building.access_note) && (
        <div className="card">
          <h2>Доступы</h2>
          <dl className="kv">
            <dt>Выход на кровлю</dt><dd>{building.roof_access || '—'}</dd>
            <dt>Чердак</dt><dd>{building.attic_access || '—'}</dd>
            <dt>Подвал</dt><dd>{building.basement_access || '—'}</dd>
            <dt>Особенности</dt><dd>{building.access_note || '—'}</dd>
          </dl>
        </div>
      )}

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'requests', label: 'История заявок', hint: requests?.total ?? 0 },
          { key: 'entrances', label: 'Подъезды и квартиры', hint: entrances?.length ?? 0 },
          { key: 'aliases', label: 'Написания адреса', hint: aliases?.length ?? 0 },
        ]}
      />

      {tab === 'requests' && (
        <DataTable
          columns={requestColumns}
          rows={requests?.items || []}
          empty="Заявок по этому дому нет"
          onRowClick={r => navigate(`/requests/${r.id}`)}
        />
      )}

      {tab === 'entrances' && (
        <EntrancesTab
          buildingId={Number(id)}
          entrances={entrances || []}
          onChanged={() => { reloadEntrances(); showToast('Подъезды обновлены') }}
        />
      )}

      {tab === 'aliases' && (
        <AliasesTab
          buildingId={Number(id)}
          aliases={aliases || []}
          onChanged={reloadAliases}
        />
      )}

      {edit && (
        <BuildingModal
          building={building}
          zheu={zheu || []}
          onClose={() => setEdit(false)}
          onDone={() => { setEdit(false); showToast('Карточка сохранена'); reload() }}
        />
      )}

      {mergeOpen && (
        <MergeModal
          buildingId={Number(id)}
          onClose={() => setMergeOpen(false)}
          onDone={targetId => { setMergeOpen(false); navigate(`/buildings/${targetId}`) }}
        />
      )}
    </>
  )
}

/**
 * Матрица квартир по подъездам. Пока она не заполнена, подъезд заявки
 * вычисляется равномерным делением и на нестандартных домах ошибается (US-F2).
 */
function EntrancesTab({ buildingId, entrances, onChanged }: {
  buildingId: number
  entrances: BuildingEntrance[]
  onChanged: () => void
}) {
  const [rows, setRows] = useState<BuildingEntrance[]>(entrances)
  const [check, setCheck] = useState('')
  const [checkResult, setCheckResult] = useState<Row | null>(null)

  const addRow = () => setRows([...rows, {
    id: null, building_id: buildingId,
    number: rows.length + 1, apartment_from: null, apartment_to: null, floors: null, note: null,
  }])

  const save = async () => {
    await client.put(`/api/buildings/${buildingId}/entrances`, rows)
    onChanged()
  }

  const checkApartment = async () => {
    const res = await client.get(`/api/buildings/${buildingId}/entrance-for?apartment=${check}`)
    setCheckResult(res.data)
  }

  return (
    <div className="card">
      <div className="notice notice-info">
        Матрица квартир — источник точного подъезда в заявке. Если она не заполнена,
        система предлагает подъезд равномерным делением и помечает результат как неточный.
      </div>
      <table>
        <thead>
          <tr>
            <th style={{ width: 100 }}>Подъезд</th>
            <th style={{ width: 130 }}>Квартиры с</th>
            <th style={{ width: 130 }}>по</th>
            <th>Примечание</th>
            <th style={{ width: 60 }}></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((e, i) => (
            <tr key={i}>
              <td><input type="number" value={e.number ?? ''} onChange={ev => {
                const v = [...rows]; v[i] = { ...e, number: Number(ev.target.value) }; setRows(v)
              }} /></td>
              <td><input type="number" value={e.apartment_from ?? ''} onChange={ev => {
                const v = [...rows]; v[i] = { ...e, apartment_from: Number(ev.target.value) }; setRows(v)
              }} /></td>
              <td><input type="number" value={e.apartment_to ?? ''} onChange={ev => {
                const v = [...rows]; v[i] = { ...e, apartment_to: Number(ev.target.value) }; setRows(v)
              }} /></td>
              <td><input value={e.note ?? ''} onChange={ev => {
                const v = [...rows]; v[i] = { ...e, note: ev.target.value }; setRows(v)
              }} /></td>
              <td>
                <button className="btn-secondary btn-sm"
                        onClick={() => setRows(rows.filter((_, j) => j !== i))}>×</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="actions" style={{ marginTop: 12 }}>
        <button className="btn-secondary" onClick={addRow}>+ Подъезд</button>
        <button className="btn-primary" onClick={save}>Сохранить</button>
        <span className="actions-divider" />
        <input value={check} onChange={e => setCheck(e.target.value)}
               placeholder="Проверить квартиру" style={{ width: 170 }} />
        <button className="btn-secondary" onClick={checkApartment} disabled={!check}>Проверить</button>
        {checkResult && (
          <span className={`badge ${checkResult.exact ? 'badge-ok' : 'badge-warn'}`}>
            подъезд {checkResult.entrance ?? '—'} ·{' '}
            {checkResult.source === 'MATRIX' ? 'по матрице'
              : checkResult.source === 'UNIFORM_SPLIT' ? 'приблизительно' : 'не определён'}
          </span>
        )}
      </div>
    </div>
  )
}

function AliasesTab({ buildingId, aliases, onChanged }: {
  buildingId: number
  aliases: Row[]
  onChanged: () => void
}) {
  const [alias, setAlias] = useState('')

  const add = async () => {
    await client.post(`/api/buildings/${buildingId}/aliases`, { alias, source: 'MANUAL' })
    setAlias('')
    onChanged()
  }

  return (
    <div className="card">
      <div className="notice notice-info">
        Каждое написание адреса, встреченное во внешней системе, запоминается за домом —
        следующий импорт сопоставит его автоматически.
      </div>
      <table>
        <thead>
          <tr><th>Написание</th><th style={{ width: 150 }}>Источник</th>
              <th style={{ width: 150 }}>Добавлено</th><th style={{ width: 60 }}></th></tr>
        </thead>
        <tbody>
          {aliases.map(a => (
            <tr key={a.id}>
              <td>{a.alias}</td>
              <td>{a.source}</td>
              <td className="small muted">{formatDate(a.created_at)}</td>
              <td>
                <button className="btn-secondary btn-sm" onClick={async () => {
                  await client.delete(`/api/buildings/aliases/${a.id}`)
                  onChanged()
                }}>×</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="actions" style={{ marginTop: 12 }}>
        <input value={alias} onChange={e => setAlias(e.target.value)}
               placeholder="Добавить написание адреса" style={{ width: 400 }} />
        <button className="btn-primary" onClick={add} disabled={!alias.trim()}>Добавить</button>
      </div>
    </div>
  )
}

function MergeModal({ buildingId, onClose, onDone }: {
  buildingId: number; onClose: () => void; onDone: (targetId: number) => void
}) {
  const [q, setQ] = useState('')
  const [targetId, setTargetId] = useState<number | null>(null)
  const [error, setError] = useState('')
  const { data } = useApi<PageResponse<Building>>(
    q.trim().length >= 2 ? `/api/buildings?q=${encodeURIComponent(q)}&size=20` : null, [q])

  const submit = async () => {
    if (!targetId) { setError('Выберите дом, в который переносим историю'); return }
    try {
      await client.post(`/api/buildings/${buildingId}/merge`, { target_id: targetId })
      onDone(targetId)
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title="Объединение дублей"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-danger" onClick={submit}>Объединить</button>
        </>
      }
    >
      <div className="notice notice-warning">
        Заявки, работы РК-3, списания и алиасы текущего дома перейдут на выбранный.
        Карточка останется в истории со ссылкой на целевой дом — физического удаления нет.
      </div>
      <Field label="Целевой дом" required>
        <input value={q} onChange={e => { setQ(e.target.value); setTargetId(null) }}
               placeholder="Поиск по адресу" />
      </Field>
      {data && !targetId && data.items.filter(b => b.id !== buildingId).map(b => (
        <div key={b.id} onClick={() => { setTargetId(b.id); setQ(b.address) }}
             style={{ padding: '7px 10px', cursor: 'pointer', borderBottom: '1px solid var(--c-border)' }}>
          {b.address} <span className="muted small">· ID {b.legacy_id ?? b.id}</span>
        </div>
      ))}
      {targetId && <div className="notice notice-success">Выбран дом: {q}</div>}
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
