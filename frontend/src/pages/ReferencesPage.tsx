import { useState } from 'react'
import client, { apiError } from '../api/client'
import DataTable from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import Tabs from '../components/Tabs'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDate, today, unitName } from '../utils/format'
import type { Reference, Row, Sku, Unit, Zheu } from '../types'

type Tab = 'sku' | 'zheu' | 'parameters' | 'coefficients' | 'dictionaries'

const DICT_LABELS: Record<string, string> = {
  'request-sources': 'Источники заявок',
  'financial-classes': 'Финансовые классы',
  'wait-reasons': 'Причины ожидания',
  'reschedule-reasons': 'Причины переноса',
  'problem-categories': 'Категории проблем',
  'employee-roles': 'Роли сотрудников',
  'cash-categories': 'Статьи кассы',
  'money-circuits': 'Денежные контуры',
  'special-equipment-types': 'Типы спецтехники',
  'vehicle-expense-categories': 'Категории расходов на транспорт',
  'safety-doc-types': 'Типы документов ТБ',
  'units': 'Единицы измерения',
  'request-statuses': 'Статусы заявки',
  'pay-scheme-types': 'Схемы оплаты',
}

/** Справочники: работы, ЖЭУ, версионируемые нормативы и простые словари. */
export default function ReferencesPage() {
  const [tab, setTab] = useState<Tab>('sku')
  return (
    <>
      <PageHead title="Справочники" />
      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'sku', label: 'Работы / SKU' },
          { key: 'zheu', label: 'ЖЭУ' },
          { key: 'parameters', label: 'Нормативы' },
          { key: 'coefficients', label: 'Коэффициенты' },
          { key: 'dictionaries', label: 'Прочие справочники' },
        ]}
      />
      {tab === 'sku' && <SkuTab />}
      {tab === 'zheu' && <ZheuTab />}
      {tab === 'parameters' && <ParametersTab />}
      {tab === 'coefficients' && <CoefficientsTab />}
      {tab === 'dictionaries' && <DictionariesTab />}
    </>
  )
}

function SkuTab() {
  const { showToast } = useToast()
  const [editing, setEditing] = useState<Sku | 'new' | null>(null)
  const { data, loading, reload } = useApi<Sku[]>('/api/sku?includeInactive=true')
  const { data: units } = useApi<Unit[]>('/api/references/units')
  const { data: groups } = useApi<Row[]>('/api/sku/groups')

  return (
    <>
      <div className="notice notice-info">
        Тарифы и расчёты ссылаются на ID работы, а не на её название: одинаковое название
        с разными ставками — источник ошибок в действующих таблицах.
      </div>
      <button className="btn-secondary" style={{ marginBottom: 12 }} onClick={() => setEditing('new')}>
        + Работа
      </button>
      <DataTable
        columns={[
          { key: 'code', title: 'Код', width: 180 },
          { key: 'name', title: 'Название' },
          { key: 'group_name', title: 'Группа', width: 220, render: s => s.group_name || '—' },
          { key: 'unit_code', title: 'Единица', width: 110, render: s => unitName(s.unit_code) },
          { key: 'act_wording', title: 'Формулировка в акте', width: 250,
            render: s => s.act_wording || '—' },
          { key: 'is_active', title: 'Активна', width: 100, render: s => s.is_active ? 'да' : 'нет' },
          { key: 'actions', title: '', width: 100,
            render: s => (
              <button className="btn-secondary btn-sm" onClick={() => setEditing(s)}>Правка</button>
            ) },
        ]}
        rows={data || []}
        loading={loading}
        empty="Работы не заведены"
      />
      {editing && (
        <SkuModal
          value={editing === 'new' ? null : editing}
          units={units || []}
          groups={groups || []}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); showToast('Сохранено'); reload() }}
        />
      )}
    </>
  )
}

function SkuModal({ value, units, groups, onClose, onDone }: {
  value: Sku | null; units: Unit[]; groups: Row[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    code: value?.code ?? '',
    name: value?.name ?? '',
    group_id: value?.group_id ?? null,
    unit_code: value?.unit_code ?? 'M2',
    act_wording: value?.act_wording ?? '',
    requires_photo: value?.requires_photo ?? false,
    is_active: value?.is_active ?? true,
    sort_order: value?.sort_order ?? 100,
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.code.trim() || !form.name.trim()) { setError('Код и название обязательны'); return }
    try {
      if (value) await client.put(`/api/sku/${value.id}`, form)
      else await client.post('/api/sku', form)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title={value ? 'Работа' : 'Новая работа'} onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <div className="form-row">
        <Field label="Код" required>
          <input value={form.code} onChange={e => set('code', e.target.value)} />
        </Field>
        <Field label="Единица" required
               hint="Разные единицы нельзя складывать в общий объём">
          <StyledSelect value={form.unit_code}
                        options={units.map(u => ({ value: u.code, label: u.name }))}
                        onChange={v => set('unit_code', v)} />
        </Field>
      </div>
      <Field label="Название" required>
        <input value={form.name} onChange={e => set('name', e.target.value)} />
      </Field>
      <Field label="Группа">
        <StyledSelect value={form.group_id as number | null}
                      options={groups.map(g => ({ value: Number(g.id), label: String(g.name) }))}
                      onChange={v => set('group_id', v)} allowEmpty />
      </Field>
      <Field label="Формулировка для акта">
        <input value={form.act_wording ?? ''} onChange={e => set('act_wording', e.target.value)} />
      </Field>
      <label className="toggle-box" style={{ marginRight: 8 }}>
        <input type="checkbox" checked={form.requires_photo}
               onChange={e => set('requires_photo', e.target.checked)} />
        Требует фото при завершении
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

function ZheuTab() {
  const { showToast } = useToast()
  const [editing, setEditing] = useState<Zheu | 'new' | null>(null)
  const { data, loading, reload } = useApi<Zheu[]>('/api/zheu?includeInactive=true')

  return (
    <>
      <button className="btn-secondary" style={{ marginBottom: 12 }} onClick={() => setEditing('new')}>
        + ЖЭУ
      </button>
      <DataTable
        columns={[
          { key: 'legacy_id', title: 'ID', width: 70, render: z => z.legacy_id ?? '—' },
          { key: 'name', title: 'Наименование' },
          { key: 'head_name', title: 'Начальник', width: 250, render: z => z.head_name || '—' },
          { key: 'address', title: 'Адрес', width: 250, render: z => z.address || '—' },
          { key: 'settlements', title: 'Территория',
            render: z => <span className="small">{z.settlements || '—'}</span> },
          { key: 'actions', title: '', width: 100,
            render: z => (
              <button className="btn-secondary btn-sm" onClick={() => setEditing(z)}>Правка</button>
            ) },
        ]}
        rows={data || []}
        loading={loading}
        empty="ЖЭУ не заведены"
      />
      {editing && (
        <ZheuModal value={editing === 'new' ? null : editing}
                   onClose={() => setEditing(null)}
                   onDone={() => { setEditing(null); showToast('Сохранено'); reload() }} />
      )}
    </>
  )
}

function ZheuModal({ value, onClose, onDone }: {
  value: Zheu | null; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    name: value?.name ?? '', head_name: value?.head_name ?? '', address: value?.address ?? '',
    phone: value?.phone ?? '', email: value?.email ?? '', settlements: value?.settlements ?? '',
    is_active: value?.is_active ?? true,
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.name.trim()) { setError('Наименование обязательно'); return }
    try {
      if (value) await client.put(`/api/zheu/${value.id}`, form)
      else await client.post('/api/zheu', form)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title={value ? 'ЖЭУ' : 'Новое ЖЭУ'} onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <Field label="Наименование" required>
        <input value={form.name} onChange={e => set('name', e.target.value)} />
      </Field>
      <Field label="ФИО начальника" hint="Подставляется в акты и сообщения">
        <input value={form.head_name ?? ''} onChange={e => set('head_name', e.target.value)} />
      </Field>
      <Field label="Адрес">
        <input value={form.address ?? ''} onChange={e => set('address', e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="Телефон"><input value={form.phone ?? ''} onChange={e => set('phone', e.target.value)} /></Field>
        <Field label="E-mail"><input value={form.email ?? ''} onChange={e => set('email', e.target.value)} /></Field>
      </div>
      <Field label="Территория обслуживания">
        <textarea rows={2} value={form.settlements ?? ''}
                  onChange={e => set('settlements', e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

/** Нормативы вместо «магических чисел»: каждое значение имеет дату действия. */
function ParametersTab() {
  const { showToast } = useToast()
  const { data, loading, reload } = useApi<Row[]>('/api/parameters')
  const [editing, setEditing] = useState<Row | null>(null)

  return (
    <>
      <div className="notice notice-info">
        Новое значение норматива не переписывает прошлые расчёты — оно создаёт новую
        версию с датой действия.
      </div>
      <DataTable
        columns={[
          { key: 'code', title: 'Код', width: 240 },
          { key: 'name', title: 'Норматив' },
          { key: 'current_value_num', title: 'Текущее значение', width: 170, align: 'right',
            render: p => p.current_value_num ?? (
              <span className="badge badge-warn">не утверждён</span>
            ) },
          { key: 'current_valid_from', title: 'Действует с', width: 140,
            render: p => formatDate(p.current_valid_from) },
          { key: 'description', title: 'Комментарий',
            render: p => <span className="small muted">{p.description || '—'}</span> },
          { key: 'actions', title: '', width: 150,
            render: p => (
              <button className="btn-secondary btn-sm" onClick={() => setEditing(p)}>
                Новое значение
              </button>
            ) },
        ]}
        rows={data || []}
        loading={loading}
        empty="Нормативов нет"
      />
      {editing && (
        <ParameterModal parameter={editing} onClose={() => setEditing(null)}
                        onDone={() => { setEditing(null); showToast('Версия добавлена'); reload() }} />
      )}
    </>
  )
}

function ParameterModal({ parameter, onClose, onDone }: {
  parameter: Row; onClose: () => void; onDone: () => void
}) {
  const [value, setValue] = useState('')
  const [validFrom, setValidFrom] = useState(today())
  const [comment, setComment] = useState('')
  const [error, setError] = useState('')
  const { data: versions } = useApi<Row[]>(`/api/parameters/${parameter.id}/versions`, [parameter.id])

  const submit = async () => {
    if (!value) { setError('Укажите значение'); return }
    try {
      await client.post(`/api/parameters/${parameter.id}/versions`, {
        value_num: Number(value), valid_from: validFrom, comment,
      })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title={parameter.name as string} onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Добавить версию</button>
             </>
           }>
      <div className="form-row">
        <Field label="Значение" required>
          <input type="number" step="0.0001" value={value} onChange={e => setValue(e.target.value)} />
        </Field>
        <Field label="Действует с" required>
          <input type="date" value={validFrom} onChange={e => setValidFrom(e.target.value)} />
        </Field>
      </div>
      <Field label="Комментарий" hint="Чем обосновано значение">
        <input value={comment} onChange={e => setComment(e.target.value)} />
      </Field>
      <h3>История значений</h3>
      <table>
        <thead><tr><th>Значение</th><th>С</th><th>По</th><th>Автор</th><th>Комментарий</th></tr></thead>
        <tbody>
          {(versions || []).map(v => (
            <tr key={v.id}>
              <td><b>{v.value_num ?? v.value_text}</b></td>
              <td>{formatDate(v.valid_from)}</td>
              <td>{formatDate(v.valid_to)}</td>
              <td>{v.author}</td>
              <td className="small">{v.comment || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

/** Диапазонные коэффициенты: границы включения заданы явно. */
function CoefficientsTab() {
  const [code, setCode] = useState('RK3_DEMOLITION_K')
  const [check, setCheck] = useState('')
  const [result, setResult] = useState<Row | null>(null)
  const { data } = useApi<Row[]>(`/api/parameters/coefficients/${code}`, [code])

  const resolve = async () => {
    const res = await client.get(`/api/parameters/coefficients/${code}/resolve?value=${check}`)
    setResult(res.data)
  }

  return (
    <>
      <div className="notice notice-info">
        Границы диапазонов заданы явно: значение p = 1,10 попадает ровно в один диапазон.
        Выплата вырубки считается как часы × базовая ставка × k — подстановка p вместо k
        конструкцией не предусмотрена.
      </div>
      <div className="filters">
        <div className="form-group">
          <label>Код набора</label>
          <input value={code} onChange={e => setCode(e.target.value)} style={{ width: 260 }} />
        </div>
        <div className="form-group">
          <label>Проверить значение p</label>
          <input type="number" step="0.01" value={check} onChange={e => setCheck(e.target.value)} />
        </div>
        <button className="btn-secondary btn-control-h" onClick={resolve} disabled={!check}>
          Какой k?
        </button>
        {result && (
          <span className={`badge ${result.k ? 'badge-ok' : 'badge-warn'}`}
                style={{ alignSelf: 'center' }}>
            {result.k ? `k = ${result.k}` : result.error}
          </span>
        )}
      </div>
      <DataTable
        columns={[
          { key: 'from_value', title: 'От', width: 120, align: 'right',
            render: r => r.from_value === null ? '−∞' : `${r.from_value} ${r.from_inclusive ? '≤' : '<'}` },
          { key: 'to_value', title: 'До', width: 120, align: 'right',
            render: r => r.to_value === null ? '+∞' : `${r.to_inclusive ? '≤' : '<'} ${r.to_value}` },
          { key: 'k', title: 'k', width: 100, align: 'right', render: r => <b>{r.k}</b> },
          { key: 'valid_from', title: 'Действует с', width: 140, render: r => formatDate(r.valid_from) },
          { key: 'comment', title: 'Комментарий',
            render: r => <span className="small muted">{r.comment || '—'}</span> },
        ]}
        rows={data || []}
        empty="Диапазоны не заданы"
      />
    </>
  )
}

function DictionariesTab() {
  const { showToast } = useToast()
  const [key, setKey] = useState('request-sources')
  const { data, loading, reload } = useApi<Row[]>(`/api/references/${key}`, [key])
  const [adding, setAdding] = useState(false)
  const [form, setForm] = useState({ code: '', name: '' })

  const readonly = ['units', 'request-statuses', 'pay-scheme-types'].includes(key)

  const add = async () => {
    try {
      await client.post(`/api/references/${key}`, form)
      setAdding(false)
      setForm({ code: '', name: '' })
      showToast('Добавлено')
      reload()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  return (
    <>
      <div className="filters">
        <div className="form-group" style={{ minWidth: 330 }}>
          <label>Справочник</label>
          <StyledSelect
            value={key}
            options={Object.entries(DICT_LABELS).map(([value, label]) => ({ value, label }))}
            onChange={v => setKey(String(v))}
          />
        </div>
        {!readonly && (
          <button className="btn-secondary btn-control-h" onClick={() => setAdding(true)}>
            + Значение
          </button>
        )}
      </div>
      {readonly && (
        <div className="notice notice-info">
          Системный справочник: на его кодах держится логика расчётов и статусов, поэтому
          он только для чтения.
        </div>
      )}
      <DataTable
        columns={[
          { key: 'code', title: 'Код', width: 240 },
          { key: 'name', title: 'Название' },
          { key: 'sort_order', title: 'Порядок', width: 110, align: 'right',
            render: r => r.sort_order ?? '—' },
        ]}
        rows={data || []}
        loading={loading}
        empty="Значений нет"
        rowKey={r => String(r.code)}
      />
      {adding && (
        <Modal title="Новое значение" onClose={() => setAdding(false)}
               actions={
                 <>
                   <button className="btn-secondary" onClick={() => setAdding(false)}>Отмена</button>
                   <button className="btn-primary" onClick={add}>Добавить</button>
                 </>
               }>
          <Field label="Код" required hint="Латиницей, без пробелов — используется в данных">
            <input value={form.code} onChange={e => setForm({ ...form, code: e.target.value })} />
          </Field>
          <Field label="Название" required>
            <input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} />
          </Field>
        </Modal>
      )}
    </>
  )
}

export type { Reference }
