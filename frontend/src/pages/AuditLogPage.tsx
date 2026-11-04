import { useState } from 'react'
import DataTable from '../components/DataTable'
import PageHead, { FILTER_WIDTHS } from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import { useApi } from '../hooks/useApi'
import { formatDateTime } from '../utils/format'
import type { Row } from '../types'

const ACTION_LABELS: Record<string, string> = {
  CREATE: 'Создание', UPDATE: 'Изменение', DELETE: 'Удаление', STATUS: 'Смена статуса',
  REVERSE: 'Сторно', APPROVE: 'Утверждение', MERGE: 'Объединение',
}

/** Журнал действий. Записи не редактируются — только читаются (US-N2). */
export default function AuditLogPage() {
  const [entityType, setEntityType] = useState<string | null>(null)
  const [actor, setActor] = useState('')

  const params = new URLSearchParams({ limit: '300' })
  if (entityType) params.set('entityType', entityType)
  if (actor.trim()) params.set('actor', actor.trim())

  const { data, loading } = useApi<Row[]>(`/api/audit-log?${params.toString()}`, [params.toString()])
  const { data: types } = useApi<string[]>('/api/audit-log/entity-types')

  return (
    <>
      <PageHead
        title="Журнал действий"
        filters={
          <>
            <StyledSelect
              value={entityType}
              options={(types || []).map(t => ({ value: t, label: t }))}
              onChange={setEntityType}
              placeholder="Сущность: все"
              allowEmpty
              width={FILTER_WIDTHS.first + 40}
            />
            <input value={actor} onChange={e => setActor(e.target.value)}
                   placeholder="Пользователь" style={{ width: FILTER_WIDTHS.second + 60 }} />
          </>
        }
      />
      <DataTable
        columns={[
          { key: 'created_at', title: 'Когда', width: 160, render: r => formatDateTime(r.created_at) },
          { key: 'actor', title: 'Кто', width: 160 },
          { key: 'entity_type', title: 'Сущность', width: 180 },
          { key: 'entity_id', title: 'ID', width: 90, render: r => r.entity_id ?? '—' },
          { key: 'action', title: 'Действие', width: 150,
            render: r => ACTION_LABELS[r.action] || r.action },
          { key: 'field', title: 'Поле', width: 160, render: r => r.field || '—' },
          { key: 'change', title: 'Было → стало',
            render: r => r.old_value || r.new_value
              ? <span className="small">{r.old_value ?? '—'} → {r.new_value ?? '—'}</span>
              : <span className="small muted">{r.comment || '—'}</span> },
        ]}
        rows={data || []}
        loading={loading}
        empty="Записей нет"
      />
    </>
  )
}
