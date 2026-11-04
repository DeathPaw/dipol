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
import { daysAgo, formatDate, formatDateTime, today } from '../utils/format'
import type { Employee, Reference, Row } from '../types'

type Tab = 'permits' | 'documents' | 'journals'

const PERMIT_STATUS: Record<string, string> = {
  DRAFT: 'Черновик', ISSUED: 'Оформлен', CLOSED: 'Закрыт', CANCELLED: 'Аннулирован',
}

/** Контур ТБ: наряды-допуски, документы и электронные журналы (эпик P). */
export default function SafetyPage() {
  const { showToast } = useToast()
  const [tab, setTab] = useState<Tab>('permits')
  const [from, setFrom] = useState(daysAgo(30))
  const [create, setCreate] = useState(false)
  const [openPermit, setOpenPermit] = useState<number | null>(null)

  const { data: permits, loading, reload } = useApi<Row[]>(`/api/safety/permits?from=${from}`, [from])
  const { data: documents, reload: reloadDocs } = useApi<Row[]>('/api/safety/documents')
  const { data: expiring } = useApi<Row[]>('/api/safety/documents/expiring?days=30')
  const { data: journals } = useApi<Row[]>('/api/safety/journals')
  const { data: employees } = useApi<Employee[]>('/api/employees')
  const { data: docTypes } = useApi<Reference[]>('/api/references/safety-doc-types')

  const permitColumns: Column<Row>[] = [
    { key: 'number', title: 'Номер', width: 150, render: p => <b>{p.number}</b> },
    { key: 'permit_date', title: 'Дата', width: 120, render: p => formatDate(p.permit_date) },
    { key: 'building_address', title: 'Объект', render: p => p.building_address || '—' },
    { key: 'work_kinds', title: 'Виды работ', width: 250, render: p => p.work_kinds || '—' },
    { key: 'acknowledged', title: 'Ознакомлены', width: 150, align: 'right',
      render: p => {
        const ok = Number(p.acknowledged_count) === Number(p.employees_count)
        return (
          <span className={`badge ${ok ? 'badge-ok' : 'badge-warn'}`}>
            {p.acknowledged_count} из {p.employees_count}
          </span>
        )
      } },
    { key: 'status', title: 'Статус', width: 140,
      render: p => PERMIT_STATUS[p.status] || p.status },
  ]

  return (
    <>
      <PageHead
        title="Техника безопасности"
        filters={<input type="date" value={from} onChange={e => setFrom(e.target.value)}
                        style={{ width: 150 }} title="Наряды с даты" />}
        right={
          <button className="btn-primary" style={pageActionBtn} onClick={() => setCreate(true)}>
            + Наряд-допуск
          </button>
        }
      />

      {expiring && expiring.length > 0 && (
        <div className="notice notice-warning">
          Документов ТБ с истекающим сроком (30 дней): <b>{expiring.length}</b> —{' '}
          {expiring.slice(0, 3).map(d => `${d.title} до ${formatDate(d.valid_to)}`).join('; ')}
          {expiring.length > 3 && ' …'}
        </div>
      )}

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'permits', label: 'Наряды-допуски', hint: permits?.length ?? 0 },
          { key: 'documents', label: 'Документы', hint: documents?.length ?? 0 },
          { key: 'journals', label: 'Журналы', hint: journals?.length ?? 0 },
        ]}
      />

      {tab === 'permits' && (
        <DataTable columns={permitColumns} rows={permits || []} loading={loading}
                   empty="Нарядов за период нет"
                   onRowClick={p => setOpenPermit(Number(p.id))} />
      )}

      {tab === 'documents' && (
        <DocumentsTab documents={documents || []} docTypes={docTypes || []}
                      employees={employees || []}
                      onChanged={() => { reloadDocs(); showToast('Сохранено') }} />
      )}

      {tab === 'journals' && <JournalsTab journals={journals || []} employees={employees || []} />}

      {create && (
        <PermitModal
          employees={employees || []}
          onClose={() => setCreate(false)}
          onDone={id => { setCreate(false); reload(); setOpenPermit(id) }}
        />
      )}

      {openPermit && (
        <PermitCard permitId={openPermit} onClose={() => setOpenPermit(null)} onChanged={reload} />
      )}
    </>
  )
}

function PermitModal({ employees, onClose, onDone }: {
  employees: Employee[]; onClose: () => void; onDone: (id: number) => void
}) {
  const [form, setForm] = useState({
    number: '', permit_date: today(), work_kinds: '', note: '',
  })
  const [selected, setSelected] = useState<number[]>([])
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: string) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.number.trim()) { setError('Номер наряда обязателен'); return }
    try {
      const res = await client.post('/api/safety/permits', { ...form, employee_ids: selected })
      onDone(Number((res.data.permit as Row).id))
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Наряд-допуск" size="large" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Создать</button>
             </>
           }>
      <div className="form-row">
        <Field label="Номер" required>
          <input value={form.number} onChange={e => set('number', e.target.value)} />
        </Field>
        <Field label="Дата" required>
          <input type="date" value={form.permit_date} onChange={e => set('permit_date', e.target.value)} />
        </Field>
      </div>
      <Field label="Виды работ" hint="кровля, высота, альпинизм, горелка, электроинструмент">
        <input value={form.work_kinds} onChange={e => set('work_kinds', e.target.value)} />
      </Field>
      <Field label="Сотрудники">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {employees.filter(e => e.is_active).map(e => {
            const on = selected.includes(e.id)
            return (
              <button key={e.id} type="button"
                      className={`chip badge ${on ? 'badge-info' : 'badge-neutral'}`}
                      onClick={() => setSelected(on
                        ? selected.filter(x => x !== e.id) : [...selected, e.id])}>
                {e.short_name || e.full_name}
              </button>
            )
          })}
        </div>
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function PermitCard({ permitId, onClose, onChanged }: {
  permitId: number; onClose: () => void; onChanged: () => void
}) {
  const { showToast } = useToast()
  const { data, reload } = useApi<{ permit: Row; employees: Row[]; requests: Row[] }>(
    `/api/safety/permits/${permitId}`, [permitId])

  if (!data) return null

  const setStatus = async (status: string) => {
    const reason = status === 'CANCELLED' ? window.prompt('Причина аннулирования') : null
    if (status === 'CANCELLED' && !reason) return
    try {
      await client.post(`/api/safety/permits/${permitId}/status`, { status, reason })
      showToast('Статус наряда изменён')
      reload()
      onChanged()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const acknowledge = async (employeeId: number) => {
    await client.post(`/api/safety/permits/${permitId}/acknowledge`, { employee_id: employeeId })
    reload()
  }

  return (
    <Modal title={`Наряд ${data.permit.number}`} size="large" onClose={onClose}
           actions={
             <>
               {data.permit.status === 'DRAFT' && (
                 <button className="btn-success" onClick={() => setStatus('ISSUED')}>Оформить</button>
               )}
               {data.permit.status === 'ISSUED' && (
                 <button className="btn-secondary" onClick={() => setStatus('CLOSED')}>Закрыть</button>
               )}
               <button className="btn-danger" onClick={() => setStatus('CANCELLED')}>Аннулировать</button>
               <button className="btn-secondary" onClick={onClose}>Закрыть окно</button>
             </>
           }>
      <dl className="kv">
        <dt>Дата</dt><dd>{formatDate(data.permit.permit_date)}</dd>
        <dt>Виды работ</dt><dd>{data.permit.work_kinds || '—'}</dd>
        <dt>Статус</dt><dd>{PERMIT_STATUS[data.permit.status] || data.permit.status}</dd>
        <dt>Оформил</dt><dd>{data.permit.issued_by || '—'}</dd>
      </dl>

      <h3>Состав и подтверждения</h3>
      <table>
        <thead><tr><th>Сотрудник</th><th style={{ width: 200 }}>Ознакомлен</th><th style={{ width: 150 }} /></tr></thead>
        <tbody>
          {data.employees.map(e => (
            <tr key={e.employee_id}>
              <td>{e.full_name}</td>
              <td>{e.acknowledged_at ? formatDateTime(e.acknowledged_at)
                : <span className="badge badge-warn">не подтверждено</span>}</td>
              <td>
                {!e.acknowledged_at && (
                  <button className="btn-secondary btn-sm"
                          onClick={() => acknowledge(Number(e.employee_id))}>
                    Отметить
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {data.requests.length > 0 && (
        <>
          <h3 style={{ marginTop: 16 }}>Заявки</h3>
          <ul>
            {data.requests.map(r => <li key={r.id}>{r.number} — {r.address}</li>)}
          </ul>
        </>
      )}
    </Modal>
  )
}

function DocumentsTab({ documents, docTypes, employees, onChanged }: {
  documents: Row[]; docTypes: Reference[]; employees: Employee[]; onChanged: () => void
}) {
  const [create, setCreate] = useState(false)
  return (
    <>
      <button className="btn-secondary" style={{ marginBottom: 12 }} onClick={() => setCreate(true)}>
        + Документ ТБ
      </button>
      <DataTable
        columns={[
          { key: 'title', title: 'Документ' },
          { key: 'type_name', title: 'Тип', width: 200 },
          { key: 'doc_number', title: 'Номер', width: 140, render: d => d.doc_number || '—' },
          { key: 'employee_name', title: 'Сотрудник', width: 200, render: d => d.employee_name || '—' },
          { key: 'valid_from', title: 'Действует с', width: 130, render: d => formatDate(d.valid_from) },
          { key: 'valid_to', title: 'по', width: 130, render: d => formatDate(d.valid_to) },
        ]}
        rows={documents}
        empty="Документов нет"
      />
      {create && (
        <SafetyDocModal docTypes={docTypes} employees={employees}
                        onClose={() => setCreate(false)}
                        onDone={() => { setCreate(false); onChanged() }} />
      )}
    </>
  )
}

function SafetyDocModal({ docTypes, employees, onClose, onDone }: {
  docTypes: Reference[]; employees: Employee[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    type_code: docTypes[0]?.code ?? 'ORDER',
    title: '', doc_number: '', valid_from: today(), valid_to: '',
    employee_id: null as number | null, note: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.title.trim()) { setError('Название обязательно'); return }
    try {
      await client.post('/api/safety/documents', { ...form, valid_to: form.valid_to || null })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Документ ТБ" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <div className="form-row">
        <Field label="Тип" required>
          <StyledSelect value={form.type_code}
                        options={docTypes.map(t => ({ value: t.code, label: t.name }))}
                        onChange={v => set('type_code', v)} />
        </Field>
        <Field label="Номер">
          <input value={form.doc_number} onChange={e => set('doc_number', e.target.value)} />
        </Field>
      </div>
      <Field label="Название" required>
        <input value={form.title} onChange={e => set('title', e.target.value)} />
      </Field>
      <div className="form-row">
        <Field label="Действует с">
          <input type="date" value={form.valid_from} onChange={e => set('valid_from', e.target.value)} />
        </Field>
        <Field label="по" hint="Оставьте пустым для бессрочного документа">
          <input type="date" value={form.valid_to} onChange={e => set('valid_to', e.target.value)} />
        </Field>
      </div>
      <Field label="Сотрудник" hint="Для удостоверений и протоколов проверки знаний">
        <StyledSelect value={form.employee_id}
                      options={employees.map(e => ({ value: e.id, label: e.full_name }))}
                      onChange={v => set('employee_id', v)} allowEmpty />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function JournalsTab({ journals, employees }: { journals: Row[]; employees: Employee[] }) {
  const [journalId, setJournalId] = useState<number | null>(journals[0] ? Number(journals[0].id) : null)
  const [from, setFrom] = useState(daysAgo(90))
  const [add, setAdd] = useState(false)
  const { data, reload } = useApi<Row[]>(
    journalId ? `/api/safety/journals/${journalId}/entries?from=${from}` : null, [journalId, from])

  return (
    <>
      <div className="filters">
        <div className="form-group" style={{ minWidth: 320 }}>
          <label>Журнал</label>
          <StyledSelect
            value={journalId}
            options={journals.map(j => ({ value: Number(j.id), label: String(j.name) }))}
            onChange={v => setJournalId(v as number | null)}
          />
        </div>
        <div className="form-group">
          <label>Записи с</label>
          <input type="date" value={from} onChange={e => setFrom(e.target.value)} />
        </div>
        <button className="btn-secondary btn-control-h" onClick={() => setAdd(true)} disabled={!journalId}>
          + Запись
        </button>
        <button className="btn-secondary btn-control-h" onClick={() => window.print()}>
          Печать
        </button>
      </div>
      <DataTable
        columns={[
          { key: 'entry_date', title: 'Дата', width: 120, render: e => formatDate(e.entry_date) },
          { key: 'employee_name', title: 'Сотрудник', width: 220, render: e => e.employee_name || '—' },
          { key: 'event', title: 'Событие / инструктаж' },
          { key: 'responsible_name', title: 'Ответственный', width: 200,
            render: e => e.responsible_name || '—' },
          { key: 'note', title: 'Примечание', render: e => e.note || '—' },
        ]}
        rows={data || []}
        empty="Записей за период нет"
      />
      {add && journalId && (
        <JournalEntryModal journalId={journalId} employees={employees}
                           onClose={() => setAdd(false)}
                           onDone={() => { setAdd(false); reload() }} />
      )}
    </>
  )
}

function JournalEntryModal({ journalId, employees, onClose, onDone }: {
  journalId: number; employees: Employee[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    entry_date: today(), employee_id: null as number | null, event: '',
    responsible_employee_id: null as number | null, note: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.event.trim()) { setError('Опишите событие'); return }
    try {
      await client.post(`/api/safety/journals/${journalId}/entries`, form)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Запись журнала" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить</button>
             </>
           }>
      <div className="form-row">
        <Field label="Дата" required>
          <input type="date" value={form.entry_date} onChange={e => set('entry_date', e.target.value)} />
        </Field>
        <Field label="Сотрудник">
          <StyledSelect value={form.employee_id}
                        options={employees.map(e => ({ value: e.id, label: e.full_name }))}
                        onChange={v => set('employee_id', v)} allowEmpty />
        </Field>
      </div>
      <Field label="Событие / инструктаж" required>
        <input value={form.event} onChange={e => set('event', e.target.value)} />
      </Field>
      <Field label="Ответственный">
        <StyledSelect value={form.responsible_employee_id}
                      options={employees.map(e => ({ value: e.id, label: e.full_name }))}
                      onChange={v => set('responsible_employee_id', v)} allowEmpty />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
