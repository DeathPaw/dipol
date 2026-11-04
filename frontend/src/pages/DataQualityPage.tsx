import PageHead from '../components/PageHead'
import { useApi } from '../hooks/useApi'
import type { Row } from '../types'

/**
 * Контроль качества данных (US-M4). Очередь собирается запросами, а не
 * ручным поиском ошибок перед закрытием месяца.
 */
export default function DataQualityPage() {
  const { data, loading } = useApi<Row[]>('/api/analytics/data-quality')
  const { data: issues } = useApi<Row[]>('/api/analytics/data-quality')

  const blocking = (data || []).filter(g => g.severity === 'BLOCKING')
  const warnings = (data || []).filter(g => g.severity !== 'BLOCKING')

  return (
    <>
      <PageHead title="Качество данных" />

      {loading && <div className="loading">Проверка…</div>}

      {!loading && (!issues || issues.length === 0) && (
        <div className="notice notice-success">
          Проблем не найдено: адреса сопоставлены, расходы разнесены, документы на месте.
        </div>
      )}

      {blocking.length > 0 && (
        <div className="notice notice-danger">
          Блокирующих проблем: <b>{blocking.length}</b>. Пока они не решены, закрытие периода
          даст неполные цифры.
        </div>
      )}

      {[...blocking, ...warnings].map(group => (
        <div key={group.issue_type} className="card">
          <div className="page-header" style={{ marginBottom: 10 }}>
            <h2 style={{ margin: 0 }}>{group.title}</h2>
            <span className={`badge ${group.severity === 'BLOCKING' ? 'badge-alert' : 'badge-warn'}`}>
              {group.count} шт.
            </span>
          </div>
          <div className="table-scroll" style={{ maxHeight: 260 }}>
            <table>
              <thead>
                <tr>
                  {Object.keys((group.items as Row[])[0] || {}).map(k => (
                    <th key={k}>{k}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {(group.items as Row[]).slice(0, 50).map((item, i) => (
                  <tr key={i}>
                    {Object.values(item).map((v, j) => (
                      <td key={j}>{v === null ? '—' : String(v)}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {(group.items as Row[]).length > 50 && (
            <div className="muted small" style={{ marginTop: 8 }}>
              Показаны первые 50 из {group.count}
            </div>
          )}
        </div>
      ))}
    </>
  )
}
