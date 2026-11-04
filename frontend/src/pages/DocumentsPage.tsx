import { useState } from 'react'
import client, { apiError } from '../api/client'
import DataTable from '../components/DataTable'
import Field from '../components/Field'
import Modal from '../components/Modal'
import PageHead, { pageActionBtn } from '../components/PageHead'
import StyledSelect from '../components/StyledSelect'
import Tabs from '../components/Tabs'
import { useToast } from '../components/Toast'
import { useApi } from '../hooks/useApi'
import { formatDate, formatDateTime } from '../utils/format'
import type { ProjectConfig, Row } from '../types'

type Tab = 'documents' | 'templates'

const DOC_TYPES: Record<string, string> = {
  ACT: 'Акт выполненных работ',
  PHOTO_REPORT: 'Фотоотчёт',
  MESSAGE: 'Сообщение в MAX',
  WORK_ORDER: 'Внутренний наряд',
  PERMIT: 'Наряд-допуск',
  SPEC: 'Спецификация',
  REPORT: 'Отчёт',
}

/**
 * Документы формируются из принятого факта. Числовой объём в самом документе
 * не редактируется — исправление идёт через факт или корректировку (US-K2).
 */
export default function DocumentsPage() {
  const { showToast } = useToast()
  const [tab, setTab] = useState<Tab>('documents')
  const [docType, setDocType] = useState<string | null>(null)
  const [createTemplate, setCreateTemplate] = useState(false)
  const [preview, setPreview] = useState<Row | null>(null)

  const { data: documents, loading } =
    useApi<Row[]>(`/api/documents${docType ? `?docType=${docType}` : ''}`, [docType])
  const { data: templates, reload: reloadTemplates } = useApi<Row[]>('/api/documents/templates')
  const { data: configs } = useApi<ProjectConfig[]>('/api/project-configs')

  const open = async (id: number) => {
    const res = await client.get(`/api/documents/${id}`)
    setPreview(res.data)
  }

  return (
    <>
      <PageHead
        title="Документы"
        filters={
          <StyledSelect
            value={docType}
            options={Object.entries(DOC_TYPES).map(([value, label]) => ({ value, label }))}
            onChange={setDocType}
            placeholder="Тип: все"
            allowEmpty
            width={260}
          />
        }
        right={
          tab === 'templates'
            ? <button className="btn-primary" style={pageActionBtn}
                      onClick={() => setCreateTemplate(true)}>+ Версия шаблона</button>
            : null
        }
      />

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { key: 'documents', label: 'Сформированные', hint: documents?.length ?? 0 },
          { key: 'templates', label: 'Шаблоны', hint: templates?.length ?? 0 },
        ]}
      />

      {tab === 'documents' && (
        <DataTable
          columns={[
            { key: 'generated_at', title: 'Когда', width: 160,
              render: d => formatDateTime(d.generated_at) },
            { key: 'doc_type', title: 'Тип', width: 220, render: d => DOC_TYPES[d.doc_type] || d.doc_type },
            { key: 'number', title: 'Номер', width: 160, render: d => d.number || '—' },
            { key: 'template_name', title: 'Шаблон', render: d => d.template_name || '—' },
            { key: 'template_version', title: 'Версия', width: 90, align: 'right' },
            { key: 'status', title: 'Статус', width: 130 },
            { key: 'generated_by', title: 'Автор', width: 150 },
          ]}
          rows={documents || []}
          loading={loading}
          empty="Документов нет"
          onRowClick={d => open(Number(d.id))}
        />
      )}

      {tab === 'templates' && (
        <>
          <div className="notice notice-info">
            Шаблон использует именованные переменные ERP, а не адреса ячеек. Сформированный
            документ сохраняет версию шаблона: старый акт воспроизводится и после смены формы.
          </div>
          <DataTable
            columns={[
              { key: 'code', title: 'Код', width: 180 },
              { key: 'name', title: 'Шаблон' },
              { key: 'doc_type', title: 'Тип', width: 220,
                render: t => DOC_TYPES[t.doc_type] || t.doc_type },
              { key: 'config_name', title: 'Конфигурация', width: 220,
                render: t => t.config_name || 'все проекты' },
              { key: 'version', title: 'Версия', width: 90, align: 'right' },
              { key: 'valid_from', title: 'Действует с', width: 140,
                render: t => formatDate(t.valid_from) },
            ]}
            rows={templates || []}
            empty="Шаблонов нет"
          />
        </>
      )}

      {createTemplate && (
        <TemplateModal configs={configs || []} onClose={() => setCreateTemplate(false)}
                       onDone={() => { setCreateTemplate(false); showToast('Версия создана'); reloadTemplates() }} />
      )}

      {preview && (
        <Modal title={`Документ ${preview.number || ''}`} size="wide"
               onClose={() => setPreview(null)}
               actions={
                 <>
                   <button className="btn-secondary" onClick={() => {
                     const w = window.open('', '_blank')
                     if (w) { w.document.write(String(preview.body)); w.document.close(); w.print() }
                   }}>Печать</button>
                   <button className="btn-secondary" onClick={() => setPreview(null)}>Закрыть</button>
                 </>
               }>
          <div style={{ background: '#fff', padding: 16, border: '1px solid var(--c-border)' }}
               dangerouslySetInnerHTML={{ __html: String(preview.body) }} />
        </Modal>
      )}
    </>
  )
}

function TemplateModal({ configs, onClose, onDone }: {
  configs: ProjectConfig[]; onClose: () => void; onDone: () => void
}) {
  const [form, setForm] = useState({
    code: 'ACT', name: '', doc_type: 'ACT',
    project_config_id: null as number | null, body: '', output_format: 'HTML',
  })
  const [error, setError] = useState('')
  const set = (k: keyof typeof form, v: unknown) => setForm(f => ({ ...f, [k]: v }))
  const { data: variables } = useApi<Record<string, string>>('/api/documents/templates/variables')

  const submit = async () => {
    if (!form.body.trim()) { setError('Шаблон пуст'); return }
    try {
      await client.post('/api/documents/templates', form)
      onDone()
    } catch (e) {
      setError(apiError(e))
    }
  }

  return (
    <Modal title="Новая версия шаблона" size="wide" onClose={onClose}
           actions={
             <>
               <button className="btn-secondary" onClick={onClose}>Отмена</button>
               <button className="btn-primary" onClick={submit}>Сохранить версию</button>
             </>
           }>
      <div className="form-row three">
        <Field label="Код" required hint="Тот же код = новая версия существующего шаблона">
          <input value={form.code} onChange={e => set('code', e.target.value)} />
        </Field>
        <Field label="Тип документа" required>
          <StyledSelect value={form.doc_type}
                        options={Object.entries(DOC_TYPES).map(([v, label]) => ({ value: v, label }))}
                        onChange={v => set('doc_type', v)} />
        </Field>
        <Field label="Конфигурация" hint="Пусто — шаблон общий для всех проектов">
          <StyledSelect value={form.project_config_id}
                        options={configs.map(c => ({ value: c.id, label: c.name }))}
                        onChange={v => set('project_config_id', v)} allowEmpty />
        </Field>
      </div>
      <Field label="Название" required>
        <input value={form.name} onChange={e => set('name', e.target.value)} />
      </Field>
      <Field label="Тело шаблона" required>
        <textarea rows={12} value={form.body} onChange={e => set('body', e.target.value)}
                  style={{ fontFamily: 'monospace', fontSize: 13 }} />
      </Field>
      <div className="notice notice-info">
        Доступные переменные:{' '}
        {Object.keys(variables || {}).map(k => (
          <code key={k} style={{ marginRight: 8 }}>{`{{${k}}}`}</code>
        ))}
      </div>
      {error && <div className="error-msg">{error}</div>}
    </Modal>
  )
}
