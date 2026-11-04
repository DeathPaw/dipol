import client from '../api/client'
import DataTable from '../components/DataTable'
import PageHead from '../components/PageHead'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDateTime } from '../utils/format'
import type { Row } from '../types'

export default function ErrorLogPage() {
  const { showToast } = useToast()
  const { data, loading, reload } = useApi<Row[]>('/api/error-log?limit=200')

  const clear = async () => {
    await client.delete('/api/error-log')
    showToast('Лог очищен')
    reload()
  }

  return (
    <>
      <PageHead
        title="Лог ошибок"
        right={<button className="btn-danger" onClick={clear}>Очистить лог</button>}
      />
      <DataTable
        columns={[
          { key: 'created_at', title: 'Когда', width: 160, render: r => formatDateTime(r.created_at) },
          { key: 'error_type', title: 'Тип', width: 220 },
          { key: 'actor', title: 'Пользователь', width: 150, render: r => r.actor || '—' },
          { key: 'request_path', title: 'Путь', width: 240, render: r => r.request_path || '—' },
          { key: 'message', title: 'Сообщение',
            render: r => <span className="small">{r.message}</span> },
        ]}
        rows={data || []}
        loading={loading}
        empty="Ошибок нет"
      />
    </>
  )
}
