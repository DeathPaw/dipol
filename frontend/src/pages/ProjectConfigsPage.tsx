import { useState } from 'react'
import client, { apiError } from '../api/client'
import DataTable, { type Column } from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead, { pageActionBtn } from '../components/PageHead'
import StatusBadge, { STATUS_NAMES, STATUS_ORDER } from '../components/StatusBadge'
import StyledSelect from '../components/StyledSelect'
import Tabs from '../components/Tabs'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDate, unitName } from '../utils/format'
import type { ProjectConfig, Row, Sku } from '../types'

const PAY_SCHEMES = [
  { code: 'SHIFT', name: 'За смену' },
  { code: 'HOUR', name: 'Почасовая' },
  { code: 'PIECE', name: 'Сдельная по объёму' },
  { code: 'PIECE_KPI', name: 'Сдельная/KPI' },
  { code: 'FIX', name: 'Фиксированная' },
  { code: 'MIXED', name: 'Комбинированная' },
]

/**
 * Проектные конфигурации: правила направления настраиваются из интерфейса,
 * новое направление не требует доработки кода (US-A3, US-O3).
 */
export default function ProjectConfigsPage() {
  const { showToast } = useToast()
  const [editing, setEditing] = useState<ProjectConfig | 'new' | null>(null)
  const [openId, setOpenId] = useState<number | null>(null)
  const [cloneFrom, setCloneFrom] = useState<ProjectConfig | null>(null)

  const { data: configs, loading, reload } = useApi<ProjectConfig[]>('/api/project-configs?includeInactive=true')

  const columns: Column<ProjectConfig>[] = [
    { key: 'code', title: 'Код', width: 150, render: c => <b>{c.code}</b> },
    { key: 'name', title: 'Конфигурация' },
    { key: 'use_work_objects', title: 'Объект работ', width: 130,
      render: c => c.use_work_objects ? 'используется' : '—' },
    { key: 'allowed_pay_schemes', title: 'Схемы ФОТ', width: 250,
      render: c => c.allowed_pay_schemes
        .map(s => PAY_SCHEMES.find(p => p.code === s)?.name || s).join(', ') },
    { key: 'materials_mode', title: 'Материалы', width: 120,
      render: c => c.materials_mode === 'PLAN_FACT' ? 'план/факт' : 'факт' },
    { key: 'current_version', title: 'Версия', width: 80, align: 'right' },
    { key: 'projects_count', title: 'Проектов', width: 90, align: 'right' },
    { key: 'is_active', title: 'Статус', width: 110,
      render: c => c.is_active
        ? <span className="badge badge-ok">активна</span>
        : <span className="badge badge-neutral">черновик</span> },
    { key: 'actions', title: '', width: 180,
      render: c => (
        <div className="actions">
          <button className="btn-secondary btn-sm"
                  onClick={e => { e.stopPropagation(); setCloneFrom(c) }}>Клон</button>
          <button className="btn-secondary btn-sm"
                  onClick={e => { e.stopPropagation(); setEditing(c) }}>Правка</button>
        </div>
      ) },
  ]

  return (
    <>
      <PageHead
        title="Конфигурации проектов"
        right={
          <button className="btn-primary" style={pageActionBtn} onClick={() => setEditing('new')}>
            + Новая конфигурация
          </button>
        }
        extra={
          <div className="notice notice-info" style={{ marginBottom: 0 }}>
            Конфигурация — переиспользуемый набор правил направления: работы, статусная схема,
            обязательные поля, чек-лист закрытия и допустимые схемы оплаты. Конкретные проекты
            у клиентов создаются поверх неё.
          </div>
        }
      />

      <DataTable
        columns={columns}
        rows={configs || []}
        loading={loading}
        empty="Конфигураций нет"
        onRowClick={c => setOpenId(c.id)}
      />

      {editing && (
        <ConfigModal
          value={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); showToast('Сохранено'); reload() }}
        />
      )}

      {cloneFrom && (
        <CloneModal
          source={cloneFrom}
          onClose={() => setCloneFrom(null)}
          onDone={id => { setCloneFrom(null); reload(); setOpenId(id) }}
        />
      )}

      {openId && (
        <ConfigCard configId={openId} onClose={() => setOpenId(null)} onChanged={reload} />
      )}
    </>
  )
}

function ConfigModal({ value, onClose, onDone }: {
  value: ProjectConfig | null; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    code: value?.code ?? '',
    name: value?.name ?? '',
    description: value?.description ?? '',
    use_work_objects: value?.use_work_objects ?? false,
    default_lifecycle: value?.default_lifecycle ?? 'PERMANENT',
    allowed_pay_schemes: value?.allowed_pay_schemes ?? ['SHIFT', 'PIECE_KPI'],
    materials_mode: value?.materials_mode ?? 'FACT',
    is_active: value?.is_active ?? false,
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.code.trim() || !form.name.trim()) { setError('Код и название обязательны'); return }
    try {
      if (value) await client.put(`/api/project-configs/${value.id}`, form)
      else await client.post('/api/project-configs', form)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title={value ? 'Конфигурация' : 'Новая конфигурация'}
      size="large"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Сохранить</button>
        </>
      }
    >
      <div className="form-row">
        <Field label="Код" required>
          <input value={form.code} onChange={e => set('code', e.target.value)} disabled={!!value} />
        </Field>
        <Field label="Название" required>
          <input value={form.name} onChange={e => set('name', e.target.value)} />
        </Field>
      </div>
      <Field label="Описание">
        <textarea rows={2} value={form.description ?? ''} onChange={e => set('description', e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="Жизненный цикл по умолчанию">
          <StyledSelect
            value={form.default_lifecycle}
            options={[
              { value: 'PERMANENT', label: 'Длительный' },
              { value: 'ONE_OFF', label: 'Разовый' },
            ]}
            onChange={v => set('default_lifecycle', v)}
          />
        </Field>
        <Field label="Материалы">
          <StyledSelect
            value={form.materials_mode}
            options={[
              { value: 'FACT', label: 'Только факт' },
              { value: 'PLAN_FACT', label: 'План и факт' },
            ]}
            onChange={v => set('materials_mode', v)}
          />
        </Field>
      </div>
      <Field label="Допустимые схемы оплаты"
             hint="Конфигурация задаёт только допустимые схемы; конкретную схему назначают сотруднику в проекте">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {PAY_SCHEMES.map(s => {
            const on = form.allowed_pay_schemes.includes(s.code)
            return (
              <button
                key={s.code}
                type="button"
                className={`chip badge ${on ? 'badge-info' : 'badge-neutral'}`}
                onClick={() => set('allowed_pay_schemes', on
                  ? form.allowed_pay_schemes.filter(x => x !== s.code)
                  : [...form.allowed_pay_schemes, s.code])}
              >
                {s.name}
              </button>
            )
          })}
        </div>
      </Field>
      <label className="toggle-box" style={{ marginRight: 8 }}>
        <input type="checkbox" checked={form.use_work_objects}
               onChange={e => set('use_work_objects', e.target.checked)} />
        Использовать уровень «объект работ»
      </label>
      <label className="toggle-box">
        <input type="checkbox" checked={form.is_active}
               onChange={e => set('is_active', e.target.checked)} />
        Активна
      </label>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function CloneModal({ source, onClose, onDone }: {
  source: ProjectConfig; onClose: () => void; onDone: (id: number) => void
}) {
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState('')

  const submit = async () => {
    try {
      const res = await client.post(`/api/project-configs/${source.id}/clone`, { code, name })
      onDone(res.data.id)
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title={`Клонировать «${source.name}»`}
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Клонировать</button>
        </>
      }
    >
      <div className="notice notice-info">
        Копируются работы, статусная схема, поля заявки и чек-лист. Клон создаётся
        неактивным: сначала правила проверяют, потом включают.
      </div>
      <Field label="Код новой конфигурации" required>
        <input value={code} onChange={e => setCode(e.target.value)} />
      </Field>
      <Field label="Название" required>
        <input value={name} onChange={e => setName(e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

type Tab = 'skus' | 'transitions' | 'fields' | 'checklist' | 'versions'

function ConfigCard({ configId, onClose, onChanged }: {
  configId: number; onClose: () => void; onChanged: () => void
}) {
  const { showToast } = useToast()
  const [tab, setTab] = useState<Tab>('skus')
  const { data, reload } = useApi<{
    config: ProjectConfig
    skus: Row[]
    transitions: Row[]
    fields: Row[]
    checklist: Row[]
    versions: Row[]
    issues: string[]
  }>(`/api/project-configs/${configId}`, [configId])
  const { data: allSkus } = useApi<Sku[]>('/api/sku')

  if (!data) return null

  const activate = async () => {
    try {
      await client.post(`/api/project-configs/${configId}/activate`)
      showToast('Конфигурация активирована')
      reload()
      onChanged()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const bumpVersion = async () => {
    const comment = window.prompt('Комментарий к новой версии правил')
    if (comment === null) return
    await client.post(`/api/project-configs/${configId}/versions`, { comment })
    showToast('Выпущена новая версия правил')
    reload()
    onChanged()
  }

  return (
    <Modal
      title={`${data.config.code} — ${data.config.name}`}
      size="wide"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={bumpVersion}>Новая версия правил</button>
          {!data.config.is_active && (
            <button className="btn-success" onClick={activate}>Активировать</button>
          )}
          <button className="btn-secondary" onClick={onClose}>Закрыть</button>
        </>
      }
    >
      {data.issues.length > 0 && (
        <div className="notice notice-warning">
          Конфигурация не готова к активации: {data.issues.join('; ')}
        </div>
      )}

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'skus', label: 'Работы', hint: data.skus.length },
          { key: 'transitions', label: 'Статусы', hint: data.transitions.length },
          { key: 'fields', label: 'Поля заявки', hint: data.fields.length },
          { key: 'checklist', label: 'Чек-лист', hint: data.checklist.length },
          { key: 'versions', label: 'Версии', hint: data.versions.length },
        ]}
      />

      {tab === 'skus' && (
        <SkuTab configId={configId} selected={data.skus} allSkus={allSkus || []}
                onSaved={() => { reload(); showToast('Состав работ обновлён') }} />
      )}

      {tab === 'transitions' && (
        <TransitionsTab configId={configId} transitions={data.transitions}
                        onSaved={() => { reload(); showToast('Статусная схема обновлена') }} />
      )}

      {tab === 'fields' && (
        <table>
          <thead><tr><th>Поле</th><th style={{ width: 130 }}>Видимо</th>
                     <th style={{ width: 150 }}>Обязательно</th></tr></thead>
          <tbody>
            {data.fields.map(f => (
              <tr key={f.field_code}>
                <td>{FIELD_LABELS[f.field_code] || f.field_code}</td>
                <td>{f.is_visible ? 'да' : 'нет'}</td>
                <td>{f.is_required ? <b>да</b> : 'нет'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {tab === 'checklist' && (
        <table>
          <thead><tr><th>Пункт</th><th style={{ width: 150 }}>Обязателен</th></tr></thead>
          <tbody>
            {data.checklist.map(c => (
              <tr key={c.item_code}>
                <td>{c.name}</td>
                <td>{c.is_required ? <b>да</b> : 'нет'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {tab === 'versions' && (
        <table>
          <thead><tr><th style={{ width: 90 }}>Версия</th><th style={{ width: 130 }}>Действует с</th>
                     <th style={{ width: 150 }}>Автор</th><th>Комментарий</th></tr></thead>
          <tbody>
            {data.versions.map(v => (
              <tr key={v.id}>
                <td><b>v{v.version}</b></td>
                <td>{formatDate(v.valid_from)}</td>
                <td>{v.author}</td>
                <td>{v.comment || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Modal>
  )
}

const FIELD_LABELS: Record<string, string> = {
  apartment: 'Квартира',
  entrance: 'Подъезд',
  external_number: 'Номер заявки заказчика',
  urgency: 'Срочность',
  description: 'Описание проблемы',
  responsible: 'Ответственный',
  photo: 'Фото',
}

function SkuTab({ configId, selected, allSkus, onSaved }: {
  configId: number; selected: Row[]; allSkus: Sku[]; onSaved: () => void
}) {
  const [ids, setIds] = useState<number[]>(selected.map(s => Number(s.id)))

  const save = async () => {
    await client.put(`/api/project-configs/${configId}/skus`, { sku_ids: ids })
    onSaved()
  }

  return (
    <>
      <div className="notice notice-info">
        В заявке этого направления оператор сможет выбрать только отмеченные работы.
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 12 }}>
        {allSkus.map(s => {
          const on = ids.includes(s.id)
          return (
            <button
              key={s.id}
              type="button"
              className={`chip badge ${on ? 'badge-info' : 'badge-neutral'}`}
              onClick={() => setIds(on ? ids.filter(x => x !== s.id) : [...ids, s.id])}
            >
              {s.name} <span className="muted"> · {unitName(s.unit_code)}</span>
            </button>
          )
        })}
      </div>
      <button className="btn-primary" onClick={save}>Сохранить состав работ</button>
    </>
  )
}

function TransitionsTab({ configId, transitions, onSaved }: {
  configId: number; transitions: Row[]; onSaved: () => void
}) {
  const [pairs, setPairs] = useState<Set<string>>(
    new Set(transitions.map(t => `${t.from_status}->${t.to_status}`)))

  const toggle = (from: string, to: string) => {
    const key = `${from}->${to}`
    const next = new Set(pairs)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    setPairs(next)
  }

  const save = async () => {
    const body = [...pairs].map(p => {
      const [from_status, to_status] = p.split('->')
      return { from_status, to_status }
    })
    await client.put(`/api/project-configs/${configId}/transitions`, body)
    onSaved()
  }

  return (
    <>
      <div className="notice notice-info">
        Отмечены разрешённые переходы: строка — текущий статус, столбец — целевой.
        Переход вне схемы возможен только с правом «Обход правил перехода».
      </div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Из \ В</th>
              {STATUS_ORDER.map(s => (
                <th key={s} style={{ fontSize: 11 }}>{STATUS_NAMES[s]}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {STATUS_ORDER.map(from => (
              <tr key={from}>
                <td className="nowrap"><StatusBadge status={from} /></td>
                {STATUS_ORDER.map(to => (
                  <td key={to} style={{ textAlign: 'center' }}>
                    {from === to ? <span className="muted">—</span> : (
                      <input
                        type="checkbox"
                        style={{ width: 'auto' }}
                        checked={pairs.has(`${from}->${to}`)}
                        onChange={() => toggle(from, to)}
                      />
                    )}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <button className="btn-primary" style={{ marginTop: 12 }} onClick={save}>
        Сохранить статусную схему
      </button>
    </>
  )
}
