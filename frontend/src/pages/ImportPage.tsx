import { useState } from 'react'
import client, { apiError } from '../api/client'
import DataTable from '../components/DataTable'
import Field from '../components/Field'
import PageHead from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import Tabs from '../components/Tabs'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDateTime } from '../utils/format'
import type { Client, PageResponse, Project, Row } from '../types'

type Tab = 'upload' | 'batches' | 'reconciliation'

/**
 * Импорт заявок из выгрузки заказчика (US-B1): файл → сопоставление колонок →
 * предпросмотр новых, совпавших и спорных строк → применение.
 */
export default function ImportPage() {
  const [tab, setTab] = useState<Tab>('upload')
  return (
    <>
      <PageHead title="Импорт и миграция" />
      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'upload', label: 'Загрузка файла' },
          { key: 'batches', label: 'История импортов' },
          { key: 'reconciliation', label: 'Протокол сверки' },
        ]}
      />
      {tab === 'upload' && <UploadTab />}
      {tab === 'batches' && <BatchesTab />}
      {tab === 'reconciliation' && <ReconciliationTab />}
    </>
  )
}

const FIELDS = [
  { key: 'address', label: 'Адрес', required: true },
  { key: 'apartment', label: 'Квартира' },
  { key: 'external_number', label: 'Номер заявки заказчика' },
  { key: 'received_at', label: 'Дата регистрации' },
  { key: 'description', label: 'Содержание заявки' },
  { key: 'urgency', label: 'Аварийная' },
  { key: 'contact_name', label: 'Заявитель — ФИО' },
  { key: 'contact_phone', label: 'Заявитель — телефон' },
]

function UploadTab() {
  const { showToast } = useToast()
  const [file, setFile] = useState<File | null>(null)
  const [inspect, setInspect] = useState<Row | null>(null)
  const [sheet, setSheet] = useState<string | null>(null)
  const [mapping, setMapping] = useState<Record<string, string>>({})
  const [preview, setPreview] = useState<Row | null>(null)
  const [clientId, setClientId] = useState<number | null>(null)
  const [projectId, setProjectId] = useState<number | null>(null)

  const { data: clients } = useApi<PageResponse<Client>>('/api/clients?size=200')
  const { data: projects } = useApi<Project[]>('/api/projects?status=ACTIVE')

  const doInspect = async () => {
    if (!file) return
    const form = new FormData()
    form.append('file', file)
    if (sheet) form.append('sheet', sheet)
    try {
      const res = await client.post('/api/import/inspect', form,
        { headers: { 'Content-Type': 'multipart/form-data' } })
      setInspect(res.data)
      setSheet(res.data.sheet)
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const doPreview = async () => {
    if (!file || !sheet) return
    if (!mapping.address) { showToast('Укажите колонку с адресом', 'warning'); return }
    const form = new FormData()
    form.append('file', file)
    form.append('sheet', sheet)
    form.append('mapping', JSON.stringify(mapping))
    try {
      const res = await client.post('/api/import/preview', form,
        { headers: { 'Content-Type': 'multipart/form-data' } })
      setPreview(res.data)
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  const apply = async () => {
    if (!preview || !clientId || !projectId) {
      showToast('Выберите клиента и проект', 'warning')
      return
    }
    try {
      const res = await client.post(`/api/import/batches/${preview.batch_id}/apply`,
        { client_id: clientId, project_id: projectId })
      showToast(`Создано ${res.data.created}, обновлено ${res.data.updated}, ошибок ${res.data.failed}`)
      setPreview(null)
    } catch (e) {
      showToast(apiError(e), 'error')
    }
  }

  return (
    <>
      <div className="card">
        <h2>1. Файл</h2>
        <div className="filters">
          <input type="file" accept=".xlsx,.xls"
                 onChange={e => { setFile(e.target.files?.[0] ?? null); setInspect(null) }}
                 style={{ width: 380 }} />
          <button className="btn-primary btn-control-h" onClick={doInspect} disabled={!file}>
            Прочитать
          </button>
        </div>
      </div>

      {inspect && (
        <div className="card">
          <h2>2. Сопоставление колонок</h2>
          <div className="filters">
            <div className="form-group" style={{ minWidth: 280 }}>
              <label>Лист</label>
              <StyledSelect value={sheet}
                            options={(inspect.sheets as string[]).map(s => ({ value: s, label: s }))}
                            onChange={v => setSheet(String(v))} />
            </div>
          </div>
          <div className="form-row">
            {FIELDS.map(f => (
              <Field key={f.key} label={f.label} required={f.required}>
                <StyledSelect
                  value={mapping[f.key] ?? null}
                  options={(inspect.headers as string[]).map(h => ({ value: h, label: h }))}
                  onChange={v => setMapping(m => ({ ...m, [f.key]: String(v ?? '') }))}
                  allowEmpty
                  placeholder="— не переносить —"
                />
              </Field>
            ))}
          </div>
          <button className="btn-primary" onClick={doPreview}>Предпросмотр</button>
        </div>
      )}

      {preview && (
        <div className="card">
          <h2>3. Предпросмотр и применение</h2>
          <div className="tiles">
            <div className="tile static">
              <div className="tile-value">{preview.new as number}</div>
              <div className="tile-label">Новых</div>
            </div>
            <div className="tile static">
              <div className="tile-value">{preview.matched as number}</div>
              <div className="tile-label">Адрес сопоставлен</div>
            </div>
            <div className={`tile static${Number(preview.ambiguous) ? ' warn' : ''}`}>
              <div className="tile-value">{preview.ambiguous as number}</div>
              <div className="tile-label">Спорных адресов</div>
            </div>
            <div className={`tile static${Number(preview.errors) ? ' alert' : ''}`}>
              <div className="tile-value">{preview.errors as number}</div>
              <div className="tile-label">Ошибок</div>
            </div>
          </div>
          <div className="filters">
            <div className="form-group" style={{ minWidth: 260 }}>
              <label>Клиент</label>
              <StyledSelect value={clientId}
                            options={(clients?.items || []).map(c => ({ value: c.id, label: c.name }))}
                            onChange={v => setClientId(v as number | null)} />
            </div>
            <div className="form-group" style={{ minWidth: 260 }}>
              <label>Проект</label>
              <StyledSelect value={projectId}
                            options={(projects || []).map(p => ({ value: p.id, label: `${p.code} — ${p.name}` }))}
                            onChange={v => setProjectId(v as number | null)} />
            </div>
            <button className="btn-success btn-control-h" onClick={apply}>
              Применить импорт
            </button>
          </div>
          <div className="notice notice-info">
            Повторная загрузка того же внешнего номера обновит заявку, а не создаст дубль.
            Спорные адреса нужно разобрать вручную — после этого выбранный вариант написания
            запомнится за домом.
          </div>
        </div>
      )}
    </>
  )
}

function BatchesTab() {
  const { data, loading } = useApi<Row[]>('/api/import/batches')
  return (
    <DataTable
      columns={[
        { key: 'created_at', title: 'Когда', width: 170, render: b => formatDateTime(b.created_at) },
        { key: 'filename', title: 'Файл' },
        { key: 'source', title: 'Источник', width: 160 },
        { key: 'total_rows', title: 'Строк', width: 90, align: 'right' },
        { key: 'new_rows', title: 'Новых', width: 90, align: 'right' },
        { key: 'matched_rows', title: 'Сопоставлено', width: 130, align: 'right' },
        { key: 'conflict_rows', title: 'Спорных', width: 110, align: 'right' },
        { key: 'status', title: 'Статус', width: 130 },
        { key: 'created_by', title: 'Автор', width: 150 },
      ]}
      rows={data || []}
      loading={loading}
      empty="Импортов не было"
    />
  )
}

function ReconciliationTab() {
  const { data, loading } = useApi<Row[]>('/api/import/reconciliation')
  return (
    <>
      <div className="notice notice-info">
        Протокол контрольной сверки миграции: что и в каком объёме перенесено из
        действующих таблиц (US-N5).
      </div>
      <DataTable
        columns={[
          { key: 'source_book', title: 'Книга', width: 180 },
          { key: 'source_sheet', title: 'Лист', width: 160 },
          { key: 'metric', title: 'Показатель' },
          { key: 'source_value', title: 'Значение', width: 170, align: 'right',
            render: r => r.source_value ?? r.note },
        ]}
        rows={data || []}
        loading={loading}
        empty="Протокол пуст"
      />
    </>
  )
}
