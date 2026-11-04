import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import client, { apiError } from '../api/client'
import DataTable, { type Column } from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead, { FILTER_WIDTHS, pageActionBtn } from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import Tabs from '../components/Tabs'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDate } from '../utils/format'
import type { Client, ClientContact, PageResponse, Row } from '../types'

const TYPE_LABELS: Record<string, string> = {
  LEGAL: 'Юрлицо', IP: 'ИП', PERSON: 'Физлицо',
}

/** Клиенты B2B и B2C в одном реестре: история отношений не дробится (US-O1). */
export default function ClientsPage() {
  const { showToast } = useToast()
  const [q, setQ] = useState('')
  const [type, setType] = useState<string | null>(null)
  const [editing, setEditing] = useState<Client | 'new' | null>(null)
  const [selected, setSelected] = useState<Client | null>(null)

  const params = new URLSearchParams()
  if (q.trim()) params.set('q', q.trim())
  if (type) params.set('type', type)
  params.set('size', '200')

  const { data, loading, reload } = useApi<PageResponse<Client>>(
    `/api/clients?${params.toString()}`, [params.toString()])

  const columns: Column<Client>[] = [
    { key: 'name', title: 'Клиент', render: c => <b>{c.name}</b> },
    { key: 'client_type', title: 'Тип', width: 100, render: c => TYPE_LABELS[c.client_type] },
    { key: 'inn', title: 'ИНН', width: 140, render: c => c.inn || '—' },
    { key: 'phone', title: 'Телефон', width: 160, render: c => c.phone || '—' },
    { key: 'comment', title: 'Комментарий', render: c => <span className="small">{c.comment || '—'}</span> },
    { key: 'actions', title: '', width: 100,
      render: c => (
        <button className="btn-secondary btn-sm"
                onClick={e => { e.stopPropagation(); setEditing(c) }}>Правка</button>
      ) },
  ]

  return (
    <>
      <PageHead
        title="Клиенты"
        filters={
          <>
            <StyledSelect
              value={type}
              options={Object.entries(TYPE_LABELS).map(([value, label]) => ({ value, label }))}
              onChange={setType}
              placeholder="Тип: все"
              allowEmpty
              width={FILTER_WIDTHS.first}
            />
            <input value={q} onChange={e => setQ(e.target.value)}
                   placeholder="Название, ИНН, телефон" style={{ width: FILTER_WIDTHS.third }} />
          </>
        }
        right={
          <button className="btn-primary" style={pageActionBtn} onClick={() => setEditing('new')}>
            + Новый клиент
          </button>
        }
      />

      <DataTable
        columns={columns}
        rows={data?.items || []}
        loading={loading}
        empty="Клиентов нет"
        onRowClick={setSelected}
      />

      {editing && (
        <ClientModal
          value={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); showToast('Сохранено'); reload() }}
        />
      )}

      {selected && (
        <ClientCard client={selected} onClose={() => setSelected(null)} />
      )}
    </>
  )
}

function ClientModal({ value, onClose, onDone }: {
  value: Client | null; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    client_type: value?.client_type ?? 'LEGAL',
    name: value?.name ?? '',
    short_name: value?.short_name ?? '',
    inn: value?.inn ?? '',
    kpp: value?.kpp ?? '',
    ogrn: value?.ogrn ?? '',
    legal_address: value?.legal_address ?? '',
    actual_address: value?.actual_address ?? '',
    phone: value?.phone ?? '',
    email: value?.email ?? '',
    comment: value?.comment ?? '',
    is_active: value?.is_active ?? true,
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))
  const isCompany = form.client_type !== 'PERSON'

  const submit = async () => {
    if (!form.name.trim()) { setError('Наименование обязательно'); return }
    try {
      if (value) await client.put(`/api/clients/${value.id}`, form)
      else await client.post('/api/clients', form)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title={value ? 'Карточка клиента' : 'Новый клиент'}
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
        <Field label="Тип клиента" required>
          <StyledSelect
            value={form.client_type}
            options={Object.entries(TYPE_LABELS).map(([value2, label]) => ({ value: value2, label }))}
            onChange={v => set('client_type', v)}
          />
        </Field>
        <Field label={isCompany ? 'Наименование' : 'ФИО'} required>
          <input value={form.name} onChange={e => set('name', e.target.value)} />
        </Field>
      </div>
      {/* Реквизиты юрлица физлицу не показываем: в форме частного заказчика
          они только мешают. */}
      {isCompany && (
        <div className="form-row three">
          <Field label="ИНН"><input value={form.inn} onChange={e => set('inn', e.target.value)} /></Field>
          <Field label="КПП"><input value={form.kpp} onChange={e => set('kpp', e.target.value)} /></Field>
          <Field label="ОГРН"><input value={form.ogrn} onChange={e => set('ogrn', e.target.value)} /></Field>
        </div>
      )}
      <div className="form-row">
        <Field label="Телефон"><input value={form.phone} onChange={e => set('phone', e.target.value)} /></Field>
        <Field label="E-mail"><input value={form.email} onChange={e => set('email', e.target.value)} /></Field>
      </div>
      {isCompany && (
        <>
          <Field label="Юридический адрес">
            <input value={form.legal_address} onChange={e => set('legal_address', e.target.value)} />
          </Field>
          <Field label="Фактический адрес">
            <input value={form.actual_address} onChange={e => set('actual_address', e.target.value)} />
          </Field>
        </>
      )}
      <Field label="Комментарий">
        <textarea rows={2} value={form.comment} onChange={e => set('comment', e.target.value)} />
      </Field>
      <label className="toggle-box">
        <input type="checkbox" checked={form.is_active} onChange={e => set('is_active', e.target.checked)} />
        Активен
      </label>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

/** История клиента: проекты, объекты, заявки и контактные лица (US-O6). */
function ClientCard({ client: c, onClose }: { client: Client; onClose: () => void }) {
  const navigate = useNavigate()
  const [tab, setTab] = useState<'projects' | 'contacts'>('projects')
  const { data: summary } = useApi<Row>(`/api/clients/${c.id}/summary`, [c.id])
  const { data: projects } = useApi<Row[]>(`/api/clients/${c.id}/projects`, [c.id])
  const { data: contacts, reload } = useApi<ClientContact[]>(`/api/clients/${c.id}/contacts`, [c.id])
  const [addContact, setAddContact] = useState(false)

  return (
    <Modal title={c.name} size="wide" onClose={onClose}
           actions={<button className="btn-secondary" onClick={onClose}>Закрыть</button>}>
      <div className="tiles">
        <div className="tile static">
          <div className="tile-value">{summary?.projects_count ?? 0}</div>
          <div className="tile-label">Проектов</div>
        </div>
        <div className="tile static">
          <div className="tile-value">{summary?.requests_total ?? 0}</div>
          <div className="tile-label">Заявок всего</div>
        </div>
        <div className="tile static">
          <div className="tile-value">{summary?.requests_active ?? 0}</div>
          <div className="tile-label">Активных заявок</div>
        </div>
        <div className="tile static">
          <div className="tile-value">{summary?.buildings_count ?? 0}</div>
          <div className="tile-label">Объектов</div>
        </div>
      </div>

      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'projects', label: 'Проекты', hint: projects?.length ?? 0 },
          { key: 'contacts', label: 'Контактные лица', hint: contacts?.length ?? 0 },
        ]}
      />

      {tab === 'projects' && (
        <table>
          <thead>
            <tr><th>Код</th><th>Проект</th><th>Конфигурация</th><th>Статус</th>
                <th className="num">Заявок</th><th>Начало</th></tr>
          </thead>
          <tbody>
            {(projects || []).map(p => (
              <tr key={p.id} style={{ cursor: 'pointer' }} onClick={() => navigate('/projects')}>
                <td><b>{p.code}</b></td>
                <td>{p.name}</td>
                <td className="small muted">{p.config_name}</td>
                <td>{p.status === 'ACTIVE' ? 'Активен' : p.status === 'CLOSED' ? 'Закрыт' : 'Приостановлен'}</td>
                <td className="num">{p.requests_count}</td>
                <td>{formatDate(p.started_on)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {tab === 'contacts' && (
        <>
          <table>
            <thead>
              <tr><th>ФИО</th><th>Должность</th><th>Роль</th><th>Телефон</th><th>E-mail</th></tr>
            </thead>
            <tbody>
              {(contacts || []).map(ct => (
                <tr key={ct.id}>
                  <td>{ct.full_name}</td>
                  <td>{ct.position || '—'}</td>
                  <td>{ct.contact_role || '—'}</td>
                  <td>{ct.phone || '—'}</td>
                  <td>{ct.email || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <button className="btn-secondary" style={{ marginTop: 12 }} onClick={() => setAddContact(true)}>
            + Контактное лицо
          </button>
          {addContact && (
            <ContactModal clientId={c.id} onClose={() => setAddContact(false)}
                          onDone={() => { setAddContact(false); reload() }} />
          )}
        </>
      )}
    </Modal>
  )
}

function ContactModal({ clientId, onClose, onDone }: {
  clientId: number; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    full_name: '', position: '', contact_role: '', phone: '', email: '', messenger: '', comment: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: string) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.full_name.trim()) { setError('ФИО обязательно'); return }
    try {
      await client.post(`/api/clients/${clientId}/contacts`, form)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title="Контактное лицо"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Сохранить</button>
        </>
      }
    >
      <Field label="ФИО" required>
        <input value={form.full_name} onChange={e => set('full_name', e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="Должность">
          <input value={form.position} onChange={e => set('position', e.target.value)} />
        </Field>
        <Field label="Роль" hint="производство, акты, доступы, оплата">
          <input value={form.contact_role} onChange={e => set('contact_role', e.target.value)} />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Телефон"><input value={form.phone} onChange={e => set('phone', e.target.value)} /></Field>
        <Field label="E-mail"><input value={form.email} onChange={e => set('email', e.target.value)} /></Field>
      </div>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
