import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import client, { apiError } from '../api/client'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead from '../components/PageHead'
import StatusBadge, { ITEM_STATUS_NAMES, STATUS_NAMES } from '../components/StatusBadge'
import StyledSelect from '../components/StyledSelect'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDate, formatDateTime, formatQty, unitName } from '../utils/format'
import type { Employee, Reference, RequestDetail, RequestItem, Row, Sku } from '../types'

/**
 * Карточка заявки: факт, чек-лист закрытия, документы и лента событий.
 *
 * Статус, приёмка объёма и закрытие разнесены по разным действиям — в
 * legacy-таблице всё это жило в одной колонке «Статус» (US-B3).
 */
export default function RequestDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { showToast } = useToast()
  const { data, loading, reload } = useApi<RequestDetail>(`/api/requests/${id}`, [id])

  const [statusModal, setStatusModal] = useState(false)
  const [waitModal, setWaitModal] = useState(false)
  const [itemModal, setItemModal] = useState<RequestItem | 'new' | null>(null)
  const [acceptItem, setAcceptItem] = useState<RequestItem | null>(null)
  const [linkedModal, setLinkedModal] = useState(false)
  const [comment, setComment] = useState('')

  const { data: skus } = useApi<Sku[]>(
    data ? `/api/sku?projectId=${data.request.project_id}` : null, [data?.request.project_id])
  const { data: employees } = useApi<Employee[]>('/api/employees')

  if (loading) return <div className="loading">Загрузка…</div>
  if (!data) return <div className="empty">Заявка не найдена</div>

  const r = data.request

  const addComment = async () => {
    if (!comment.trim()) return
    await client.post(`/api/requests/${id}/comments`, { text: comment.trim() })
    setComment('')
    reload()
  }

  const generateDoc = async (templateCode: string) => {
    try {
      const res = await client.post('/api/documents/generate', {
        request_id: Number(id), template_code: templateCode,
      })
      showToast('Документ сформирован')
      const w = window.open('', '_blank')
      if (w) {
        w.document.write(res.data.doc_type === 'MESSAGE'
          ? `<pre style="font: 14px/1.5 system-ui; white-space: pre-wrap">${res.data.body}</pre>`
          : res.data.body)
        w.document.close()
      }
      reload()
    } catch (e) {
      showToast(apiError(e, 'Не удалось сформировать документ'), 'error')
    }
  }

  const closeRequest = async () => {
    try {
      const res = await client.post(`/api/requests/${id}/close`)
      if (res.data.status === 'BLOCKED') {
        showToast('Не закрыто: ' + (res.data.pending as string[]).join('; '), 'warning')
      } else {
        showToast('Заявка закрыта операционно')
      }
      reload()
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const toggleChecklist = async (itemCode: string, isDone: boolean) => {
    await client.put(`/api/requests/${id}/checklist/${itemCode}`, { is_done: isDone })
    reload()
  }

  return (
    <>
      <PageHead
        title={`Заявка ${r.number}`}
        right={
          <div className="actions">
            <button className="btn-secondary btn-sm" onClick={() => generateDoc('ACT')}>Акт</button>
            <button className="btn-secondary btn-sm" onClick={() => generateDoc('MAX_MESSAGE')}>
              Сообщение MAX
            </button>
            <button className="btn-secondary btn-sm" onClick={() => generateDoc('WORK_ORDER')}>Наряд</button>
            <span className="actions-divider" />
            <button className="btn-secondary btn-sm" onClick={() => setWaitModal(true)}>Ожидание</button>
            <button className="btn-secondary btn-sm" onClick={() => setLinkedModal(true)}>
              Повторная / гарантия
            </button>
            <button className="btn-primary btn-sm" onClick={() => setStatusModal(true)}>
              Сменить статус
            </button>
          </div>
        }
      />

      {data.warnings.length > 0 && (
        <div className="notice notice-warning">
          {data.warnings.map((w, i) => <div key={i}>{w}</div>)}
        </div>
      )}

      <div className="detail-layout">
        <div>
          <div className="card">
            <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 12 }}>
              <StatusBadge status={r.status} name={r.status_name} />
              {r.urgency === 'EMERGENCY' && <span className="badge badge-alert">Аварийная</span>}
              {r.is_warranty && <span className="badge badge-warn">Гарантийная</span>}
              {r.customer_closed && <span className="badge badge-ok">Закрыта у заказчика</span>}
              {r.is_closed && <span className="badge badge-info">Закрыта операционно</span>}
            </div>
            <dl className="kv">
              <dt>Адрес</dt>
              <dd>
                <a href={`/buildings/${r.building_id}`}
                   onClick={e => { e.preventDefault(); navigate(`/buildings/${r.building_id}`) }}>
                  {r.building_address}
                </a>
                {r.apartment && <> · кв. {r.apartment}</>}
                {r.entrance && (
                  <> · подъезд {r.entrance}
                    {!r.entrance_is_manual && <span className="muted small"> (рассчитан)</span>}
                  </>
                )}
              </dd>
              <dt>Клиент и проект</dt>
              <dd>{r.client_name} · {r.project_name}</dd>
              <dt>ЖЭУ</dt><dd>{r.zheu_name || '—'}</dd>
              <dt>Номер заказчика</dt><dd>{r.external_number || '—'}</dd>
              <dt>Поступила</dt>
              <dd>
                {formatDateTime(r.received_at)}
                {r.received_at_customer && (
                  <span className="muted"> · у заказчика {formatDate(r.received_at_customer)}</span>
                )}
              </dd>
              <dt>План / выполнение</dt>
              <dd>{formatDate(r.planned_date)} → {formatDate(r.done_at)}</dd>
              <dt>Приёмка заказчиком</dt><dd>{formatDate(r.customer_accepted_at)}</dd>
              <dt>Ответственный</dt><dd>{r.responsible_name || '—'}</dd>
              <dt>Описание</dt><dd>{r.description || '—'}</dd>
              {r.internal_comment && (<><dt>Комментарий</dt><dd>{r.internal_comment}</dd></>)}
              {r.cancel_reason && (<><dt>Причина отмены</dt><dd>{r.cancel_reason}</dd></>)}
            </dl>
          </div>

          <div className="card">
            <div className="page-header" style={{ marginBottom: 12 }}>
              <h2 style={{ margin: 0 }}>Работы</h2>
              <button className="btn-secondary btn-sm" onClick={() => setItemModal('new')}>
                + Добавить работу
              </button>
            </div>
            {data.items.length === 0 ? (
              <div className="empty">Работы не внесены</div>
            ) : (
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>Работа</th>
                      <th className="num" style={{ width: 110 }}>Заявлено</th>
                      <th className="num" style={{ width: 110 }}>Факт</th>
                      <th className="num" style={{ width: 110 }}>Принято</th>
                      <th className="num" style={{ width: 90 }}>чел·ч</th>
                      <th style={{ width: 130 }}>Статус</th>
                      <th style={{ width: 150 }}>Исполнители</th>
                      <th style={{ width: 140 }}></th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map(it => (
                      <tr key={it.id}>
                        <td>
                          {it.sku_name}
                          {it.reference_qty !== null && (
                            <div className="small muted">
                              справочно: {formatQty(it.reference_qty, it.reference_unit_code)}
                            </div>
                          )}
                        </td>
                        <td className="num">{formatQty(it.declared_qty, it.unit_code)}</td>
                        <td className="num">{formatQty(it.fact_qty, it.unit_code)}</td>
                        <td className="num"><b>{formatQty(it.accepted_qty, it.unit_code)}</b></td>
                        <td className="num">{it.man_hours ?? '—'}</td>
                        <td>
                          <span className={`badge ${it.status === 'ACCEPTED' ? 'badge-ok'
                            : it.status === 'REWORK' ? 'badge-alert' : 'badge-neutral'}`}>
                            {ITEM_STATUS_NAMES[it.status] || it.status}
                          </span>
                        </td>
                        <td className="small">
                          {it.assignee_ids.length
                            ? it.assignee_ids
                                .map(eid => employees?.find(e => e.id === eid)?.short_name
                                  || employees?.find(e => e.id === eid)?.full_name || eid)
                                .join(', ')
                            : <span className="muted">не назначены</span>}
                        </td>
                        <td>
                          <div className="actions">
                            <button className="btn-secondary btn-sm" onClick={() => setItemModal(it)}>
                              Правка
                            </button>
                            {it.status !== 'ACCEPTED' && (
                              <button className="btn-success btn-sm" onClick={() => setAcceptItem(it)}>
                                Принять
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="card">
            <h2>Лента событий</h2>
            <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              <input
                value={comment}
                onChange={e => setComment(e.target.value)}
                placeholder="Комментарий к заявке"
                onKeyDown={e => e.key === 'Enter' && addComment()}
              />
              <button className="btn-secondary" onClick={addComment} disabled={!comment.trim()}>
                Добавить
              </button>
            </div>
            <div className="timeline">
              {data.events.map(ev => (
                <div key={ev.id} className={`timeline-item type-${ev.event_type}`}>
                  <div>{ev.text || ev.event_type}</div>
                  <div className="timeline-meta">
                    {formatDateTime(ev.created_at)} · {ev.actor || 'система'}
                    {ev.wait_reason && <> · причина: {ev.wait_reason}</>}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div>
          <div className="card">
            <h2>Чек-лист закрытия</h2>
            {data.checklist.length === 0 ? (
              <div className="empty">Чек-лист не настроен в конфигурации проекта</div>
            ) : (
              <>
                {data.checklist.map(c => (
                  <label key={c.id} className="toggle-box" style={{ width: '100%', marginBottom: 6 }}>
                    <input
                      type="checkbox"
                      checked={c.is_done}
                      onChange={e => toggleChecklist(c.item_code, e.target.checked)}
                    />
                    <span style={{ flex: 1 }}>
                      {c.name}
                      {!c.is_required && <span className="muted small"> (не обязателен)</span>}
                    </span>
                  </label>
                ))}
                <button className="btn-primary" style={{ width: '100%', marginTop: 8 }}
                        onClick={closeRequest} disabled={r.is_closed}>
                  {r.is_closed ? 'Заявка закрыта' : 'Закрыть заявку'}
                </button>
              </>
            )}
          </div>

          {data.linked.length > 0 && (
            <div className="card">
              <h2>Связанные заявки</h2>
              {data.linked.map(l => (
                <div key={l.id} style={{ marginBottom: 6 }}>
                  <a href={`/requests/${l.id}`}
                     onClick={e => { e.preventDefault(); navigate(`/requests/${l.id}`) }}>
                    {l.number}
                  </a>
                  <span className="muted small"> · {l.link_type === 'WARRANTY' ? 'гарантийная' : 'повторная'}</span>
                </div>
              ))}
            </div>
          )}

          {data.trips.length > 0 && (
            <div className="card">
              <h2>Выезды</h2>
              {(data.trips as Row[]).map(t => (
                <div key={t.id} style={{ marginBottom: 6 }}>
                  {formatDate(t.trip_date)} · {t.plate || 'без автомобиля'}
                  <span className="muted small"> · {t.responsible_name || '—'}</span>
                </div>
              ))}
            </div>
          )}

          <div className="card">
            <h2>История статусов</h2>
            <div className="timeline">
              {(data.status_history as Row[]).map(h => (
                <div key={h.id} className="timeline-item type-STATUS">
                  <div>
                    {h.from_status ? `${STATUS_NAMES[h.from_status] || h.from_status} → ` : ''}
                    {STATUS_NAMES[h.to_status] || h.to_status}
                    {h.is_override && <span className="badge badge-warn" style={{ marginLeft: 6 }}>обход</span>}
                  </div>
                  <div className="timeline-meta">
                    {formatDateTime(h.created_at)} · {h.actor}
                    {h.reason && <> · {h.reason}</>}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {statusModal && (
        <StatusModal requestId={Number(id)} current={r.status}
                     onClose={() => setStatusModal(false)}
                     onDone={() => { setStatusModal(false); reload() }} />
      )}
      {waitModal && (
        <WaitModal requestId={Number(id)}
                   onClose={() => setWaitModal(false)}
                   onDone={() => { setWaitModal(false); reload() }} />
      )}
      {itemModal && (
        <ItemModal
          requestId={Number(id)}
          item={itemModal === 'new' ? null : itemModal}
          skus={skus || []}
          employees={employees || []}
          onClose={() => setItemModal(null)}
          onDone={() => { setItemModal(null); reload() }}
        />
      )}
      {acceptItem && (
        <AcceptModal
          item={acceptItem}
          onClose={() => setAcceptItem(null)}
          onDone={msg => { setAcceptItem(null); showToast(msg); reload() }}
        />
      )}
      {linkedModal && (
        <LinkedModal requestId={Number(id)}
                     onClose={() => setLinkedModal(false)}
                     onDone={newId => { setLinkedModal(false); navigate(`/requests/${newId}`) }} />
      )}
    </>
  )
}

function StatusModal({ requestId, current, onClose, onDone }: {
  requestId: number; current: string; onClose: () => void; onDone: () => void
}) {
  const [status, setStatus] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [override, setOverride] = useState(false)
  const [error, setError] = useState('')

  const submit = async () => {
    if (!status) { setError('Выберите статус'); return }
    try {
      await client.post(`/api/requests/${requestId}/status`, { status, reason, override })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title="Смена статуса"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Применить</button>
        </>
      }
    >
      <div className="notice notice-info">
        Текущий статус: <b>{STATUS_NAMES[current] || current}</b>. Допустимые переходы задаёт
        конфигурация проекта.
      </div>
      <Field label="Новый статус" required>
        <StyledSelect
          value={status}
          options={Object.entries(STATUS_NAMES).map(([value, label]) => ({ value, label }))}
          onChange={setStatus}
        />
      </Field>
      <Field label="Причина" hint="Обязательна для отмены и для обхода правила перехода">
        <input value={reason} onChange={e => setReason(e.target.value)} />
      </Field>
      <label className="toggle-box">
        <input type="checkbox" checked={override} onChange={e => setOverride(e.target.checked)} />
        Обойти правило перехода (требуется право и причина)
      </label>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function WaitModal({ requestId, onClose, onDone }: {
  requestId: number; onClose: () => void; onDone: () => void
}) {
  const { data: reasons } = useApi<Reference[]>('/api/references/wait-reasons')
  const [code, setCode] = useState<string | null>(null)
  const [comment, setComment] = useState('')
  const [error, setError] = useState('')

  const submit = async () => {
    if (!code) { setError('Выберите причину'); return }
    try {
      await client.post(`/api/requests/${requestId}/wait`, { reason_code: code, comment })
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title="Зафиксировать ожидание"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Зафиксировать</button>
        </>
      }
    >
      <div className="notice notice-info">
        Ожидание погоды, аванса, материала или доступа не меняет статус заявки — оно
        записывается событием и попадает в аналитику причин простоя.
      </div>
      <Field label="Причина" required>
        <StyledSelect
          value={code}
          options={(reasons || []).map(r => ({ value: r.code, label: r.name }))}
          onChange={setCode}
        />
      </Field>
      <Field label="Комментарий">
        <textarea rows={2} value={comment} onChange={e => setComment(e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function ItemModal({ requestId, item, skus, employees, onClose, onDone }: {
  requestId: number
  item: RequestItem | null
  skus: Sku[]
  employees: Employee[]
  onClose: () => void
  onDone: () => void
}) {
  const [skuId, setSkuId] = useState<number | null>(item?.sku_id ?? skus[0]?.id ?? null)
  const [declared, setDeclared] = useState(item?.declared_qty?.toString() ?? '')
  const [fact, setFact] = useState(item?.fact_qty?.toString() ?? '')
  const [hours, setHours] = useState(item?.man_hours?.toString() ?? '')
  const [note, setNote] = useState(item?.note ?? '')
  const [assignees, setAssignees] = useState<number[]>(item?.assignee_ids ?? [])
  const [error, setError] = useState('')

  const sku = skus.find(s => s.id === skuId)

  const submit = async () => {
    if (!skuId || !sku) { setError('Выберите работу'); return }
    const body = {
      sku_id: skuId,
      unit_code: sku.unit_code,
      declared_qty: declared ? Number(declared) : null,
      fact_qty: fact ? Number(fact) : null,
      man_hours: hours ? Number(hours) : null,
      note: note || null,
      assignee_ids: assignees,
      status: item?.status ?? 'PLANNED',
    }
    try {
      if (item) await client.put(`/api/requests/items/${item.id}`, body)
      else await client.post(`/api/requests/${requestId}/items`, body)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  const remove = async () => {
    if (!item) return
    await client.delete(`/api/requests/items/${item.id}`)
    onDone()
  }

  return (
    <Modal
      title={item ? 'Работа по заявке' : 'Добавить работу'}
      size="large"
      onClose={onClose}
      actions={
        <>
          {item && <button className="btn-danger" onClick={remove}>Удалить</button>}
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Сохранить</button>
        </>
      }
    >
      <Field label="Работа" required hint="Список ограничен конфигурацией проекта">
        <StyledSelect
          value={skuId}
          options={skus.map(s => ({ value: s.id, label: s.name, hint: unitName(s.unit_code) }))}
          onChange={v => setSkuId(v as number)}
        />
      </Field>
      <div className="form-row three">
        <Field label={`Заявлено, ${unitName(sku?.unit_code)}`}>
          <input type="number" step="0.001" value={declared} onChange={e => setDeclared(e.target.value)} />
        </Field>
        <Field label={`Выполнено, ${unitName(sku?.unit_code)}`}>
          <input type="number" step="0.001" value={fact} onChange={e => setFact(e.target.value)} />
        </Field>
        <Field label="Человеко-часы">
          <input type="number" step="0.01" value={hours} onChange={e => setHours(e.target.value)} />
        </Field>
      </div>
      <Field label="Исполнители" hint="Первый в списке считается ответственным за работу">
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {employees.filter(e => e.is_active).map(e => {
            const on = assignees.includes(e.id)
            return (
              <button
                key={e.id}
                type="button"
                className={`chip badge ${on ? 'badge-info' : 'badge-neutral'}`}
                onClick={() => setAssignees(on
                  ? assignees.filter(x => x !== e.id)
                  : [...assignees, e.id])}
              >
                {e.short_name || e.full_name}
              </button>
            )
          })}
        </div>
      </Field>
      <Field label="Примечание">
        <input value={note} onChange={e => setNote(e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

/** Приёмка объёма: из принятого факта считается ФОТ, поэтому тут же виден расчёт. */
function AcceptModal({ item, onClose, onDone }: {
  item: RequestItem; onClose: () => void; onDone: (message: string) => void
}) {
  const [qty, setQty] = useState((item.accepted_qty ?? item.fact_qty ?? '').toString())
  const [comment, setComment] = useState('')
  const [error, setError] = useState('')
  const [result, setResult] = useState<Row[] | null>(null)

  const submit = async () => {
    try {
      const res = await client.post(`/api/execution/items/${item.id}/accept`, {
        accepted_qty: qty ? Number(qty) : null,
        comment: comment || null,
      })
      setResult(res.data.payroll_entries as Row[])
    } catch (e) {
      setError(apiError(e))
    }
  }

  if (result) {
    return (
      <Modal
        title="Объём принят"
        onClose={() => onDone('Объём принят')}
        actions={<button className="btn-primary" onClick={() => onDone('Объём принят')}>Закрыть</button>}
      >
        <h3>Начисления ФОТ</h3>
        {result.length === 0 && <div className="empty">Начислений нет</div>}
        {result.map((a, i) => (
          <div key={i} className="notice" style={{ marginBottom: 6 }}>
            <b>{a.employee_name || '—'}</b>
            {a.skipped
              ? <div className="muted">{a.skipped}</div>
              : <div>{a.amount} ₽ — {a.formula}</div>}
          </div>
        ))}
      </Modal>
    )
  }

  return (
    <Modal
      title={`Приёмка: ${item.sku_name}`}
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-success" onClick={submit}>Принять</button>
        </>
      }
    >
      <Field label={`Принимаемый объём, ${unitName(item.unit_code)}`} required>
        <input type="number" step="0.001" value={qty} onChange={e => setQty(e.target.value)} />
      </Field>
      <Field label="Причина корректировки"
             hint="Обязательна, если принимаемый объём отличается от выполненного">
        <input value={comment} onChange={e => setComment(e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}

function LinkedModal({ requestId, onClose, onDone }: {
  requestId: number; onClose: () => void; onDone: (id: number) => void
}) {
  const [linkType, setLinkType] = useState<string | null>('REPEAT')
  const [reason, setReason] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState('')

  const submit = async () => {
    try {
      const res = await client.post(`/api/requests/${requestId}/linked`, {
        link_type: linkType, reason, description,
      })
      onDone(res.data.id)
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal
      title="Повторная или гарантийная заявка"
      onClose={onClose}
      actions={
        <>
          <button className="btn-secondary" onClick={onClose}>Отмена</button>
          <button className="btn-primary" onClick={submit}>Создать</button>
        </>
      }
    >
      <div className="notice notice-info">
        Создаётся новая заявка со ссылкой на исходную. Закрытый факт исходной заявки
        не переписывается.
      </div>
      <Field label="Тип связи" required>
        <StyledSelect
          value={linkType}
          options={[
            { value: 'REPEAT', label: 'Повторное обращение' },
            { value: 'WARRANTY', label: 'Гарантийная' },
            { value: 'DUPLICATE', label: 'Дубль' },
          ]}
          onChange={setLinkType}
        />
      </Field>
      <Field label="Основание">
        <input value={reason} onChange={e => setReason(e.target.value)} />
      </Field>
      <Field label="Описание новой заявки">
        <textarea rows={3} value={description} onChange={e => setDescription(e.target.value)} />
      </Field>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
