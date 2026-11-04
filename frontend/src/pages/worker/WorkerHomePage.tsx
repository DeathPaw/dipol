import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import client, { apiError } from '../../api/client'
import Modal from '../../components/Modal'
import Field from '../../components/Field'
import { useToast } from '../../components/Toast'
import { useApi } from '../../hooks/useApi'
import { formatQty, formatRub, monthStart, today, unitName } from '../../utils/format'
import type { Row } from '../../types'
import '../../components/Layout.css'

/**
 * Мобильный кабинет исполнителя (эпик D). Показываются только свои работы;
 * чужие задачи и чужие суммы не видны. Работа открывается в одно-два действия.
 */
export default function WorkerHomePage() {
  const navigate = useNavigate()
  const { showToast } = useToast()
  const employeeId = sessionStorage.getItem('worker_employee_id')
  const name = sessionStorage.getItem('worker_name') || 'Исполнитель'
  const [tab, setTab] = useState<'tasks' | 'payroll' | 'safety'>('tasks')
  const [finishing, setFinishing] = useState<Row | null>(null)

  const { data: tasks, loading, reload } =
    useApi<Row[]>(`/api/worker/tasks?employeeId=${employeeId}&date=${today()}`, [employeeId])
  const { data: payroll } = useApi<Row>(
    `/api/worker/payroll?employeeId=${employeeId}&from=${monthStart()}&to=${today()}`, [employeeId])
  const { data: safety } = useApi<{ documents: Row[]; permits: Row[] }>(
    `/api/safety/for-employee/${employeeId}`, [employeeId])

  const logout = () => {
    sessionStorage.removeItem('worker_employee_id')
    sessionStorage.removeItem('worker_name')
    navigate('/worker-login')
  }

  const start = async (itemId: number) => {
    try {
      await client.post(`/api/execution/items/${itemId}/start`, { employee_id: Number(employeeId) })
      showToast('Работа начата')
      reload()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const acknowledge = async (permitId: number) => {
    await client.post(`/api/safety/permits/${permitId}/acknowledge`, { employee_id: Number(employeeId) })
    showToast('Ознакомление подтверждено')
  }

  return (
    <div className="worker-shell">
      <div className="worker-head">
        <div>
          <b>{name}</b>
          <div className="small" style={{ opacity: 0.8 }}>Задачи на сегодня</div>
        </div>
        <button className="btn-secondary btn-sm" onClick={logout}>Выход</button>
      </div>

      <div className="segmented" style={{ width: '100%', marginBottom: 12 }}>
        <button className={tab === 'tasks' ? 'active' : ''} onClick={() => setTab('tasks')}
                style={{ flex: 1 }}>Работы</button>
        <button className={tab === 'payroll' ? 'active' : ''} onClick={() => setTab('payroll')}
                style={{ flex: 1 }}>Мой ФОТ</button>
        <button className={tab === 'safety' ? 'active' : ''} onClick={() => setTab('safety')}
                style={{ flex: 1 }}>ТБ</button>
      </div>

      {tab === 'tasks' && (
        <>
          {loading && <div className="loading">Загрузка…</div>}
          {!loading && (!tasks || tasks.length === 0) && (
            <div className="empty">На сегодня задач нет</div>
          )}
          {(tasks || []).map(t => (
            <div key={t.item_id} className="worker-task">
              <h3>{t.sku_name}</h3>
              <div>{t.address}</div>
              <div className="small muted">
                {t.apartment ? `кв. ${t.apartment}` : ''}
                {t.entrance ? ` · подъезд ${t.entrance}` : ''}
                {t.place_note ? ` · ${t.place_note}` : ''}
              </div>
              {t.description && <div className="small" style={{ marginTop: 6 }}>{t.description}</div>}
              {t.planned_qty && (
                <div className="small" style={{ marginTop: 6 }}>
                  План: {formatQty(t.planned_qty, t.unit_code)}
                </div>
              )}
              {(t.roof_access || t.access_note) && (
                <div className="notice notice-info" style={{ marginTop: 8, marginBottom: 0 }}>
                  Доступ: {[t.roof_access, t.access_note].filter(Boolean).join('; ')}
                </div>
              )}
              <div className="actions" style={{ marginTop: 10 }}>
                {t.item_status === 'IN_PROGRESS' ? (
                  <button className="btn-success" onClick={() => setFinishing(t)}>Завершить</button>
                ) : (
                  <button className="btn-primary" onClick={() => start(Number(t.item_id))}>
                    Начать работу
                  </button>
                )}
                {t.urgency === 'EMERGENCY' && <span className="badge badge-alert">аварийная</span>}
              </div>
            </div>
          ))}
        </>
      )}

      {tab === 'payroll' && (
        <>
          <div className="worker-task">
            <h3>Начислено за месяц</h3>
            <div style={{ fontSize: 26, fontWeight: 700 }}>{formatRub(payroll?.accrued)}</div>
          </div>
          {((payroll?.entries as Row[]) || []).map((e, i) => (
            <div key={i} className="worker-task">
              <div><b>{formatRub(e.amount)}</b> · {e.sku_name || e.project_name}</div>
              <div className="small muted">{e.formula}</div>
            </div>
          ))}
        </>
      )}

      {tab === 'safety' && (
        <>
          <h3>Наряды-допуски</h3>
          {(safety?.permits || []).length === 0 && <div className="empty">Нарядов нет</div>}
          {(safety?.permits || []).map(p => (
            <div key={p.id} className="worker-task">
              <div><b>{p.number}</b> · {p.work_kinds || 'виды работ не указаны'}</div>
              <div className="small muted">{p.address || 'объект не указан'}</div>
              {p.acknowledged_at
                ? <span className="badge badge-ok">ознакомлен</span>
                : (
                  <button className="btn-primary btn-sm" style={{ marginTop: 8 }}
                          onClick={() => acknowledge(Number(p.id))}>
                    Подтвердить ознакомление
                  </button>
                )}
            </div>
          ))}
          <h3 style={{ marginTop: 16 }}>Мои документы ТБ</h3>
          {(safety?.documents || []).length === 0 && <div className="empty">Документов нет</div>}
          {(safety?.documents || []).map(d => (
            <div key={d.id} className="worker-task">
              <div>{d.title}</div>
              <div className="small muted">{d.type_name}</div>
            </div>
          ))}
        </>
      )}

      {finishing && (
        <FinishModal task={finishing} employeeId={Number(employeeId)}
                     onClose={() => setFinishing(null)}
                     onDone={() => { setFinishing(null); showToast('Работа завершена'); reload() }} />
      )}
    </div>
  )
}

function FinishModal({ task, employeeId, onClose, onDone }: {
  task: Row; employeeId: number; onClose: () => void; onDone: () => void
}) {
  const [qty, setQty] = useState(String(task.fact_qty ?? task.planned_qty ?? ''))
  const [hours, setHours] = useState('')
  const [comment, setComment] = useState('')
  const [error, setError] = useState('')

  const submit = async () => {
    try {
      // Сначала фиксируем фактический объём позиции, затем закрываем сессию:
      // объём — это факт работы, часы — факт времени, их вводят раздельно.
      await client.put(`/api/requests/items/${task.item_id}`, {
        sku_id: task.sku_id ?? undefined,
        unit_code: task.unit_code,
        fact_qty: qty ? Number(qty) : null,
        man_hours: hours ? Number(hours) : null,
        status: 'ON_REVIEW',
        assignee_ids: [employeeId],
      })
      const sessions = await client.get(`/api/execution/items/${task.item_id}/sessions`)
      const open = (sessions.data as Row[]).find(s => !s.finished_at)
      if (open) {
        await client.post(`/api/execution/sessions/${open.id}/finish`, {
          man_hours: hours ? Number(hours) : null, comment,
        })
      }
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Завершение работы" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-success" onClick={submit}>Завершить</button>
             </>
           }>
      <div className="small muted" style={{ marginBottom: 10 }}>
        {task.sku_name} · {task.address}
      </div>
      <Field label={`Выполненный объём, ${unitName(task.unit_code)}`} required>
        <input type="number" step="0.001" value={qty} onChange={e => setQty(e.target.value)}
               style={{ height: 44, fontSize: 18 }} />
      </Field>
      <Field label="Человеко-часы">
        <input type="number" step="0.5" value={hours} onChange={e => setHours(e.target.value)}
               style={{ height: 44, fontSize: 18 }} />
      </Field>
      <Field label="Комментарий">
        <textarea rows={2} value={comment} onChange={e => setComment(e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
