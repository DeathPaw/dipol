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
import { daysAgo, formatDate, formatRub, today } from '../utils/format'
import type { Project, Reference, Row } from '../types'

type Tab = 'transactions' | 'balances' | 'accounts' | 'debts'

const DIRECTION: Record<string, string> = {
  INCOME: 'Приход', EXPENSE: 'Расход', TRANSFER: 'Перемещение',
}

/**
 * Управленческие кассы. ID операции — UUID, а не строка, зависящая от номера
 * строки таблицы; проведённая операция не удаляется, только сторнируется (US-L6).
 */
export default function FinancePage() {
  const { showToast } = useToast()
  const [tab, setTab] = useState<Tab>('transactions')
  const [from, setFrom] = useState(daysAgo(30))
  const [to, setTo] = useState(today())
  const [circuit, setCircuit] = useState<string | null>(null)
  const [create, setCreate] = useState(false)

  const { data: circuits } = useApi<Reference[]>('/api/references/money-circuits')
  const { data: accounts, reload: reloadAccounts } = useApi<Row[]>('/api/finance/accounts')
  const { data: balances } = useApi<Row[]>(`/api/finance/balances?on=${to}`, [to])
  const { data: categories } = useApi<Reference[]>('/api/references/cash-categories')
  const { data: counterparties } = useApi<Row[]>('/api/finance/counterparties')
  const { data: projects } = useApi<Project[]>('/api/projects')
  const { data: unjustified } = useApi<Row[]>(`/api/finance/unjustified?from=${from}`, [from])

  const params = new URLSearchParams({ from, to })
  if (circuit) params.set('circuit', circuit)
  const { data: transactions, loading, reload } =
    useApi<Row[]>(`/api/finance/transactions?${params.toString()}`, [params.toString()])

  const reverse = async (id: number) => {
    const reason = window.prompt('Основание сторно операции')
    if (!reason) return
    try {
      await client.post(`/api/finance/transactions/${id}/reverse`, { reason })
      showToast('Проведено сторно')
      reload()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const columns: Column<Row>[] = [
    { key: 'occurred_on', title: 'Дата', width: 110, render: t => formatDate(t.occurred_on) },
    { key: 'direction', title: 'Тип', width: 130,
      render: t => (
        <span className={`badge ${t.direction === 'INCOME' ? 'badge-ok'
          : t.direction === 'EXPENSE' ? 'badge-alert' : 'badge-neutral'}`}>
          {DIRECTION[t.direction] || t.direction}
        </span>
      ) },
    { key: 'from_account_name', title: 'Откуда', width: 210, render: t => t.from_account_name || '—' },
    { key: 'to_account_name', title: 'Куда', width: 210, render: t => t.to_account_name || '—' },
    { key: 'amount', title: 'Сумма', width: 140, align: 'right',
      render: t => <b>{formatRub(t.amount)}</b> },
    { key: 'category_name', title: 'Статья', width: 170, render: t => t.category_name || '—' },
    { key: 'counterparty_name', title: 'Контрагент', width: 180,
      render: t => t.counterparty_name || '—' },
    { key: 'project_code', title: 'Проект', width: 100, render: t => t.project_code || '—' },
    { key: 'comment', title: 'Комментарий', render: t => <span className="small">{t.comment || '—'}</span> },
    { key: 'actions', title: '', width: 100,
      render: t => t.status === 'REVERSED'
        ? <span className="muted small">сторно</span>
        : <button className="btn-secondary btn-sm" onClick={() => reverse(Number(t.id))}>Сторно</button> },
  ]

  return (
    <>
      <PageHead
        title="Кассы"
        filters={
          <>
            <input type="date" value={from} onChange={e => setFrom(e.target.value)} style={{ width: 150 }} />
            <input type="date" value={to} onChange={e => setTo(e.target.value)} style={{ width: 150 }} />
            <StyledSelect
              value={circuit}
              options={(circuits || []).map(c => ({ value: c.code, label: c.name }))}
              onChange={setCircuit}
              placeholder="Контур: все"
              allowEmpty
              width={190}
            />
          </>
        }
        right={
          <button className="btn-primary" style={pageActionBtn} onClick={() => setCreate(true)}>
            + Операция
          </button>
        }
      />

      {unjustified && unjustified.length > 0 && (
        <div className="notice notice-warning">
          Расходов без основания и комментария: <b>{unjustified.length}</b>. Они попадают
          в контроль качества данных.
        </div>
      )}

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'transactions', label: 'Журнал операций' },
          { key: 'balances', label: 'Остатки', hint: balances?.length ?? 0 },
          { key: 'accounts', label: 'Счета и кассы', hint: accounts?.length ?? 0 },
          { key: 'debts', label: 'Взаиморасчёты контуров' },
        ]}
      />

      {tab === 'transactions' && (
        <DataTable columns={columns} rows={transactions || []} loading={loading}
                   empty="Операций за период нет" />
      )}

      {tab === 'balances' && (
        <>
          <div className="notice notice-info">
            Остаток считается из проводок на выбранную дату. Технические точки
            («Корректировка», «Рабочий») в остатки не входят.
          </div>
          <DataTable
            columns={[
              { key: 'name', title: 'Счёт' },
              { key: 'circuit_code', title: 'Контур', width: 140 },
              { key: 'account_type', title: 'Тип', width: 120 },
              { key: 'balance', title: `Остаток на ${formatDate(to)}`, width: 200, align: 'right',
                render: b => <b>{formatRub(b.balance)}</b> },
            ]}
            rows={balances || []}
            empty="Счетов нет"
          />
        </>
      )}

      {tab === 'accounts' && (
        <AccountsTab accounts={accounts || []} circuits={circuits || []}
                     onChanged={() => { reloadAccounts(); showToast('Сохранено') }} />
      )}

      {tab === 'debts' && <DebtsTab />}

      {create && (
        <TransactionModal
          accounts={accounts || []}
          categories={categories || []}
          counterparties={counterparties || []}
          projects={projects || []}
          circuits={circuits || []}
          onClose={() => setCreate(false)}
          onDone={() => { setCreate(false); showToast('Операция проведена'); reload() }}
        />
      )}
    </>
  )
}

function AccountsTab({ accounts, circuits, onChanged }: {
  accounts: Row[]; circuits: Reference[]; onChanged: () => void
}) {
  const [create, setCreate] = useState(false)
  return (
    <>
      <button className="btn-secondary" style={{ marginBottom: 12 }} onClick={() => setCreate(true)}>
        + Счёт или касса
      </button>
      <DataTable
        columns={[
          { key: 'name', title: 'Название' },
          { key: 'circuit_name', title: 'Контур', width: 160 },
          { key: 'account_type', title: 'Тип', width: 130 },
          { key: 'is_real', title: 'В остатках', width: 130,
            render: a => a.is_real ? 'да' : <span className="muted">техническая точка</span> },
          { key: 'is_active', title: 'Активен', width: 110, render: a => a.is_active ? 'да' : 'нет' },
        ]}
        rows={accounts}
        empty="Счетов нет"
      />
      {create && (
        <AccountModal circuits={circuits} onClose={() => setCreate(false)}
                      onDone={() => { setCreate(false); onChanged() }} />
      )}
    </>
  )
}

function AccountModal({ circuits, onClose, onDone }: {
  circuits: Reference[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    name: '', account_type: 'CASH', circuit_code: circuits[0]?.code ?? 'DIPOL',
    owner: '', is_real: true,
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    try {
      await client.post('/api/finance/accounts', form)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Денежный счёт" onClose={onClose}
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
        <Field label="Тип">
          <StyledSelect
            value={form.account_type}
            options={[
              { value: 'CASH', label: 'Наличные' },
              { value: 'CARD', label: 'Карта' },
              { value: 'BANK', label: 'Расчётный счёт' },
              { value: 'VIRTUAL', label: 'Виртуальный' },
            ]}
            onChange={v => set('account_type', v)}
          />
        </Field>
        <Field label="Контур" required>
          <StyledSelect
            value={form.circuit_code}
            options={circuits.map(c => ({ value: c.code, label: c.name }))}
            onChange={v => set('circuit_code', v)}
          />
        </Field>
      </div>
      <label className="toggle-box">
        <input type="checkbox" checked={form.is_real} onChange={e => set('is_real', e.target.checked)} />
        Участвует в расчёте остатков
      </label>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function DebtsTab() {
  const { data, loading } = useApi<Row[]>('/api/finance/circuit-debts')
  return (
    <>
      <div className="notice notice-info">
        Взаимные обязательства между контурами «Диполь», «Ковры» и личными деньгами:
        временное финансирование не должно растворяться в общей кассе.
      </div>
      <DataTable
        columns={[
          { key: 'debtor_name', title: 'Должник', width: 200 },
          { key: 'creditor_name', title: 'Кредитор', width: 200 },
          { key: 'open_amount', title: 'Открытый долг', width: 170, align: 'right',
            render: d => <b>{formatRub(d.open_amount)}</b> },
          { key: 'total_amount', title: 'Всего за период', width: 170, align: 'right',
            render: d => formatRub(d.total_amount) },
        ]}
        rows={data || []}
        loading={loading}
        empty="Взаимных обязательств нет"
        rowKey={(r, i) => `${r.debtor_code}-${r.creditor_code}-${i}`}
      />
    </>
  )
}

function TransactionModal({ accounts, categories, counterparties, projects, circuits, onClose, onDone }: {
  accounts: Row[]; categories: Reference[]; counterparties: Row[]
  projects: Project[]; circuits: Reference[]
  onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    occurred_on: today(),
    direction: 'EXPENSE',
    from_account_id: null as number | null,
    to_account_id: null as number | null,
    amount: '',
    category_code: null as string | null,
    counterparty_id: null as number | null,
    project_id: null as number | null,
    circuit_code: circuits[0]?.code ?? 'DIPOL',
    period_from: '',
    period_to: '',
    comment: '',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))

  const submit = async () => {
    if (!form.amount) { setError('Укажите сумму'); return }
    try {
      await client.post('/api/finance/transactions', {
        ...form,
        amount: Number(form.amount),
        period_from: form.period_from || null,
        period_to: form.period_to || null,
      })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  const accountOptions = accounts.map(a => ({ value: Number(a.id), label: String(a.name) }))

  return (
    <Modal title="Кассовая операция" size="large" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Провести</button>
             </>
           }>
      <div className="form-row three">
        <Field label="Дата" required>
          <input type="date" value={form.occurred_on} onChange={e => set('occurred_on', e.target.value)} />
        </Field>
        <Field label="Тип" required>
          <StyledSelect
            value={form.direction}
            options={Object.entries(DIRECTION).map(([value, label]) => ({ value, label }))}
            onChange={v => set('direction', v)}
          />
        </Field>
        <Field label="Сумма, ₽" required>
          <input type="number" value={form.amount} onChange={e => set('amount', e.target.value)} />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Откуда" required={form.direction !== 'INCOME'}>
          <StyledSelect value={form.from_account_id} options={accountOptions}
                        onChange={v => set('from_account_id', v)} allowEmpty />
        </Field>
        <Field label="Куда" required={form.direction !== 'EXPENSE'}>
          <StyledSelect value={form.to_account_id} options={accountOptions}
                        onChange={v => set('to_account_id', v)} allowEmpty />
        </Field>
      </div>
      <div className="form-row three">
        <Field label="Статья">
          <StyledSelect
            value={form.category_code}
            options={categories.map(c => ({ value: c.code, label: c.name }))}
            onChange={v => set('category_code', v)}
            allowEmpty
          />
        </Field>
        <Field label="Контрагент">
          <StyledSelect
            value={form.counterparty_id}
            options={counterparties.map(c => ({ value: Number(c.id), label: String(c.name) }))}
            onChange={v => set('counterparty_id', v)}
            allowEmpty
          />
        </Field>
        <Field label="Проект">
          <StyledSelect
            value={form.project_id}
            options={projects.map(p => ({ value: p.id, label: p.code }))}
            onChange={v => set('project_id', v)}
            allowEmpty
          />
        </Field>
      </div>
      <div className="form-row">
        <Field label="Расчётный период с"
               hint="Дата выдачи денег и период начисления — разные вещи">
          <input type="date" value={form.period_from} onChange={e => set('period_from', e.target.value)} />
        </Field>
        <Field label="по">
          <input type="date" value={form.period_to} onChange={e => set('period_to', e.target.value)} />
        </Field>
      </div>
      <Field label="Комментарий">
        <input value={form.comment} onChange={e => set('comment', e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
