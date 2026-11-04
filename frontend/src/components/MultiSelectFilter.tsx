import { useEffect, useRef, useState } from 'react'

export interface MultiSelectOption {
  value: string
  label: string
}

/** Порог по количеству опций, после которого показываем поле поиска. */
const SEARCH_THRESHOLD = 7

/**
 * Мультифильтр с чекбоксами. Используется везде, где раньше был бы одиночный
 * select: статусы, проекты, финансовые классы, исполнители.
 */
export default function MultiSelectFilter({
  options, value, onChange, placeholder = 'Все', width = 220,
  collapseThreshold = 2, searchable = false,
}: {
  options: MultiSelectOption[]
  value: string[]
  onChange: (v: string[]) => void
  placeholder?: string
  width?: number | string
  collapseThreshold?: number
  searchable?: boolean
}) {
  const showSearch = searchable || options.length >= SEARCH_THRESHOLD
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const searchRef = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        setOpen(false)
        setSearch('')
      }
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  useEffect(() => {
    if (open && showSearch) requestAnimationFrame(() => searchRef.current?.focus())
  }, [open, showSearch])

  const toggle = (v: string) => {
    onChange(value.includes(v) ? value.filter(x => x !== v) : [...value, v])
  }

  // Выбранные пункты всегда показываем: иначе снять фильтр, не подходящий
  // под поисковый запрос, было бы нечем.
  const filtered = (() => {
    const q = search.trim().toLowerCase()
    if (!q) return options
    return options.filter(o => value.includes(o.value) || o.label.toLowerCase().includes(q))
  })()

  const triggerLabel = (() => {
    if (value.length === 0) return placeholder
    if (value.length <= collapseThreshold) {
      return value.map(v => options.find(o => o.value === v)?.label || v).join(', ')
    }
    return `${value.length} выбрано`
  })()

  return (
    <div ref={wrapRef} style={{ position: 'relative', width: typeof width === 'number' ? `${width}px` : width }}>
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        style={{
          width: '100%', height: 'var(--control-h)',
          textAlign: 'left', padding: '0 10px',
          border: '1px solid #ddd', borderRadius: 4, background: '#fff', cursor: 'pointer',
          whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
          color: value.length ? 'var(--c-text)' : 'var(--c-text-muted)',
        }}
      >
        {triggerLabel}
        <span style={{ float: 'right', color: 'var(--c-primary)' }}>▾</span>
      </button>
      {open && (
        <div style={{
          position: 'absolute', top: '100%', left: 0, marginTop: 2, zIndex: 2000,
          background: '#ffffff', border: '1px solid #ddd', borderRadius: 4,
          minWidth: '100%', boxShadow: '0 6px 18px rgba(0,0,0,0.18)', padding: 8,
          maxHeight: 330, overflowY: 'auto',
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
            <button
              type="button"
              style={{ background: 'none', border: 'none', color: '#3498db', cursor: 'pointer', padding: 0, fontSize: 'var(--font-sm)' }}
              onClick={() => onChange([])}
              disabled={value.length === 0}
            >
              Сбросить
            </button>
            <button
              type="button"
              style={{ background: 'none', border: 'none', color: '#888', cursor: 'pointer', padding: 0, fontSize: 'var(--font-sm)' }}
              onClick={() => { setOpen(false); setSearch('') }}
            >
              Закрыть
            </button>
          </div>
          {showSearch && (
            <input
              ref={searchRef}
              type="text"
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Поиск…"
              style={{ marginBottom: 6, height: 28, fontSize: 'var(--font-sm)' }}
            />
          )}
          {filtered.length === 0 && (
            <div style={{ color: '#aaa', fontSize: 'var(--font-sm)', padding: '4px 0' }}>
              Ничего не найдено
            </div>
          )}
          {filtered.map(o => {
            const checked = value.includes(o.value)
            return (
              <label
                key={o.value}
                style={{
                  display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer',
                  padding: '5px 6px', borderRadius: 3,
                  background: checked ? 'var(--c-primary-light)' : 'transparent',
                  fontWeight: checked ? 600 : 400,
                }}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggle(o.value)}
                  style={{ width: 'auto' }}
                />
                <span style={{ whiteSpace: 'nowrap' }}>{o.label}</span>
              </label>
            )
          })}
        </div>
      )}
    </div>
  )
}
