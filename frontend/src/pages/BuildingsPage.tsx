import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import client, { apiError } from '../api/client'
import DataTable, { type Column } from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead, { FILTER_WIDTHS, pageActionBtn } from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import type { Building, PageResponse, Zheu } from '../types'

const PAGE_SIZE = 50

/** Реестр МКД. Ключ дома — внутренний ID, адрес ключом не является (US-A1). */
export default function BuildingsPage() {
  const navigate = useNavigate()
  const { showToast } = useToast()
  const [q, setQ] = useState('')
  const [zheuId, setZheuId] = useState<number | null>(null)
  const [includeInactive, setIncludeInactive] = useState(false)
  const [page, setPage] = useState(0)
  const [showCreate, setShowCreate] = useState(false)

  const { data: zheu } = useApi<Zheu[]>('/api/zheu')
  const query = new URLSearchParams()
  if (q.trim()) query.set('q', q.trim())
  if (zheuId) query.set('zheuId', String(zheuId))
  if (includeInactive) query.set('includeInactive', 'true')
  query.set('page', String(page))
  query.set('size', String(PAGE_SIZE))

  const { data, loading, reload } = useApi<PageResponse<Building>>(
    `/api/buildings?${query.toString()}`, [query.toString()])

  const columns: Column<Building>[] = [
    { key: 'legacy_id', title: 'ID', width: 70, nowrap: true,
      render: b => b.legacy_id ?? <span className="muted">—</span> },
    { key: 'address', title: 'Адрес' },
    { key: 'zheu_name', title: 'ЖЭУ', width: 230, render: b => b.zheu_name || '—' },
    { key: 'floors', title: 'Этажей', width: 80, align: 'right' },
    { key: 'entrances_count', title: 'Подъездов', width: 95, align: 'right' },
    { key: 'apartments_count', title: 'Квартир', width: 90, align: 'right' },
    { key: 'roof_type', title: 'Кровля', width: 110, render: b => b.roof_type || '—' },
    { key: 'is_active', title: '', width: 90,
      render: b => b.is_active ? null : <span className="badge badge-neutral">объединён</span> },
  ]

  const totalPages = data ? Math.ceil(data.total / PAGE_SIZE) : 0

  return (
    <>
      <PageHead
        title="Дома и адреса"
        filters={
          <>
            <StyledSelect
              value={zheuId}
              options={(zheu || []).map(z => ({ value: z.id, label: z.name }))}
              onChange={v => { setZheuId(v as number | null); setPage(0) }}
              placeholder="ЖЭУ: все"
              allowEmpty
              width={FILTER_WIDTHS.first + 60}
            />
            <input
              value={q}
              onChange={e => { setQ(e.target.value); setPage(0) }}
              placeholder="Адрес, ID дома или прежнее написание"
              style={{ width: FILTER_WIDTHS.third }}
            />
          </>
        }
        right={
          <>
            <label className="toggle-box">
              <input type="checkbox" checked={includeInactive}
                     onChange={e => setIncludeInactive(e.target.checked)} />
              Показать объединённые
            </label>
            <button className="btn-primary" style={pageActionBtn} onClick={() => setShowCreate(true)}>
              + Новый дом
            </button>
          </>
        }
        extra={<span className="muted small">Найдено домов: {data?.total ?? 0}</span>}
      />

      <DataTable
        columns={columns}
        rows={data?.items || []}
        loading={loading}
        empty="Домов по этим условиям нет"
        onRowClick={b => navigate(`/buildings/${b.id}`)}
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
        <BuildingModal
          zheu={zheu || []}
          onClose={() => setShowCreate(false)}
          onDone={() => { setShowCreate(false); showToast('Дом добавлен'); reload() }}
        />
      )}
    </>
  )
}

export function BuildingModal({ building, zheu, onClose, onDone }: {
  building?: Building
  zheu: Zheu[]
  onClose: () => void
  onDone: () => void
}) {
  const [form, setForm] = useState({
    address: building?.address ?? '',
    zheu_id: building?.zheu_id ?? null,
    floors: building?.floors ?? '',
    entrances_count: building?.entrances_count ?? '',
    apartments_count: building?.apartments_count ?? '',
    apartments_per_entrance: building?.apartments_per_entrance ?? '',
    roof_type: building?.roof_type ?? '',
    fias_id: building?.fias_id ?? '',
    roof_access: building?.roof_access ?? '',
    attic_access: building?.attic_access ?? '',
    access_note: building?.access_note ?? '',
    condition_note: building?.condition_note ?? '',
  })
  const [error, setError] = useState('')

  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.address.trim()) { setError('Адрес обязателен'); return }
    const body = {
      ...form,
      floors: form.floors === '' ? null : Number(form.floors),
      entrances_count: form.entrances_count === '' ? null : Number(form.entrances_count),
      apartments_count: form.apartments_count === '' ? null : Number(form.apartments_count),
      apartments_per_entrance:
        form.apartments_per_entrance === '' ? null : Number(form.apartments_per_entrance),
    }
    try {
      if (building) await client.put(`/api/buildings/${building.id}`, body)
      else await client.post('/api/buildings', body)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title={building ? 'Карточка дома' : 'Новый дом'}
      size="large"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Сохранить</button>
        </>
      }
    >
      <Field label="Адрес" required hint="Прежнее написание сохранится как алиас и продолжит находиться">
        <input value={form.address} onChange={e => set('address', e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="ЖЭУ">
          <StyledSelect
            value={form.zheu_id as number | null}
            options={zheu.map(z => ({ value: z.id, label: z.name }))}
            onChange={v => set('zheu_id', v)}
            allowEmpty
          />
        </Field>
        <Field label="ФИАС" hint="Внешний идентификатор, ключом дома не является">
          <input value={form.fias_id} onChange={e => set('fias_id', e.target.value)} />
        </Field>
      </div>
      <div className="form-row three">
        <Field label="Этажей">
          <input type="number" value={form.floors} onChange={e => set('floors', e.target.value)} />
        </Field>
        <Field label="Подъездов">
          <input type="number" value={form.entrances_count}
                 onChange={e => set('entrances_count', e.target.value)} />
        </Field>
        <Field label="Квартир">
          <input type="number" value={form.apartments_count}
                 onChange={e => set('apartments_count', e.target.value)} />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Квартир в подъезде"
               hint="Используется для подсказки подъезда, если матрица не заполнена">
          <input type="number" value={form.apartments_per_entrance}
                 onChange={e => set('apartments_per_entrance', e.target.value)} />
        </Field>
        <Field label="Тип кровли">
          <input value={form.roof_type} onChange={e => set('roof_type', e.target.value)} />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Выход на кровлю">
          <input value={form.roof_access} onChange={e => set('roof_access', e.target.value)} />
        </Field>
        <Field label="Чердак">
          <input value={form.attic_access} onChange={e => set('attic_access', e.target.value)} />
        </Field>
      </div>
      <Field label="Особенности доступа">
        <input value={form.access_note} onChange={e => set('access_note', e.target.value)} />
      </Field>
      <Field label="Состояние объекта">
        <textarea rows={2} value={form.condition_note}
                  onChange={e => set('condition_note', e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
