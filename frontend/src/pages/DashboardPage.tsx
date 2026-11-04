import { useNavigate } from 'react-router-dom'
import PageHead from '../components/PageHead'
import StatusBadge from '../components/StatusBadge'
import { useApi } from '../hooks/useApi'
import { formatDate } from '../utils/format'
import type { Row } from '../types'

interface Dashboard {
  new_requests: number
  unplanned: number
  overdue: number
  in_progress: number
  awaiting_acceptance: number
  without_act: number
  emergency_open: number
  by_status: Row[]
  upcoming_trips: Row[]
  open_problems: Row[]
}

/**
 * Операционный дашборд (US-M1). Каждый показатель кликабелен и ведёт в список
 * с уже наложенным фильтром: иначе цифра остаётся цифрой, а не задачей.
 */
export default function DashboardPage() {
  const navigate = useNavigate()
  const { data, loading } = useApi<Dashboard>('/api/analytics/dashboard')

  const go = (query: string) => navigate(`/requests?${query}`)

  return (
    <>
      <PageHead title="Главная" />

      {loading && <div className="loading">Загрузка…</div>}

      {data && (
        <>
          <div className="tiles">
            <div className="tile" onClick={() => go('statuses=CREATED')}>
              <div className="tile-value">{data.new_requests}</div>
              <div className="tile-label">Новые заявки</div>
            </div>
            <div className={`tile${data.overdue ? ' alert' : ''}`} onClick={() => go('overdue=1')}>
              <div className="tile-value">{data.overdue}</div>
              <div className="tile-label">Просрочены по плану</div>
            </div>
            <div className={`tile${data.emergency_open ? ' warn' : ''}`} onClick={() => go('urgency=EMERGENCY')}>
              <div className="tile-value">{data.emergency_open}</div>
              <div className="tile-label">Аварийные в работе</div>
            </div>
            <div className="tile" onClick={() => go('unplanned=1')}>
              <div className="tile-value">{data.unplanned}</div>
              <div className="tile-label">Без плана выполнения</div>
            </div>
            <div className="tile" onClick={() => go('statuses=IN_PROGRESS')}>
              <div className="tile-value">{data.in_progress}</div>
              <div className="tile-label">В работе</div>
            </div>
            <div className="tile" onClick={() => go('statuses=DONE')}>
              <div className="tile-value">{data.awaiting_acceptance}</div>
              <div className="tile-label">Ждут приёмки</div>
            </div>
            <div className="tile" onClick={() => go('statuses=CUSTOMER_ACCEPTED')}>
              <div className="tile-value">{data.without_act}</div>
              <div className="tile-label">Без акта</div>
            </div>
          </div>

          <div className="detail-layout">
            <div>
              <div className="card">
                <h2>Заявки по статусам</h2>
                {data.by_status.length === 0
                  ? <div className="empty">Заявок пока нет</div>
                  : (
                    <table>
                      <thead>
                        <tr><th>Статус</th><th style={{ width: 120 }} className="num">Количество</th></tr>
                      </thead>
                      <tbody>
                        {data.by_status.map(s => (
                          <tr key={s.status} style={{ cursor: 'pointer' }}
                              onClick={() => go(`statuses=${s.status}`)}>
                            <td><StatusBadge status={s.status} name={s.status_name} /></td>
                            <td className="num">{s.count}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
              </div>

              <div className="card">
                <h2>Открытые проблемы</h2>
                {data.open_problems.length === 0
                  ? <div className="empty">Открытых проблем нет</div>
                  : (
                    <table>
                      <thead>
                        <tr><th>Категория</th><th>Описание</th><th style={{ width: 130 }}>Заявка</th></tr>
                      </thead>
                      <tbody>
                        {data.open_problems.map(p => (
                          <tr key={p.id}>
                            <td className="nowrap">{p.category_name}</td>
                            <td>{p.description}</td>
                            <td className="nowrap">{p.request_number || '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
              </div>
            </div>

            <div className="card">
              <h2>Ближайшие выезды</h2>
              {data.upcoming_trips.length === 0
                ? <div className="empty">Выездов не запланировано</div>
                : (
                  <div className="timeline">
                    {data.upcoming_trips.map(t => (
                      <div key={t.id} className="timeline-item" style={{ cursor: 'pointer' }}
                           onClick={() => navigate('/trips')}>
                        <div><b>{formatDate(t.trip_date)}</b> — {t.requests_count} заявок</div>
                        <div className="timeline-meta">
                          {t.plate ? `Автомобиль ${t.plate}` : 'Автомобиль не назначен'}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
            </div>
          </div>
        </>
      )}
    </>
  )
}
