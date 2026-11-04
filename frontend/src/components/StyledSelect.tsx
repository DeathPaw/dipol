import { useEffect, useRef, useState } from 'react'

export interface SelectOption<T extends string | number> {
  value: T
  label: string
  hint?: string
}

/**
 * Выпадающий список в стиле приложения.
 *
 * Нативный select рисует раскрытый список средствами ОС — на macOS это
 * тёмно-серая панель, выбивающаяся из бело-голубой палитры. Список рисуем
 * сами; поведение остаётся привычным: клик раскрывает, Esc и клик мимо
 * закрывают, стрелки и Enter работают с клавиатуры.
 */
export default function StyledSelect<T extends string | number>({
  value, options, onChange, placeholder = '— не выбрано —', disabled, width, ariaLabel, allowEmpty,
}: {
  value: T | null
  options: SelectOption<T>[]
  onChange: (v: T | null) => void
  placeholder?: string
  disabled?: boolean
  width?: number | string
  ariaLabel?: string
  /** Показывать пункт «не выбрано» — для необязательных фильтров. */
  allowEmpty?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [highlight, setHighlight] = useState(0)
  const wrapRef = useRef<HTMLDivElement | null>(null)

  const selected = options.find(o => o.value === value) ?? null

  useEffect(() => {
    if (!open) return
    const onDocClick = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDocClick)
    return () => document.removeEventListener('mousedown', onDocClick)
  }, [open])

  useEffect(() => {
    if (open) setHighlight(Math.max(0, options.findIndex(o => o.value === value)))
  }, [open, options, value])

  const pick = (v: T | null) => { onChange(v); setOpen(false) }

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (disabled) return
    if (!open && (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown')) {
      e.preventDefault(); setOpen(true); return
    }
    if (!open) return
    if (e.key === 'Escape') { e.preventDefault(); setOpen(false) }
    else if (e.key === 'ArrowDown') { e.preventDefault(); setHighlight(h => Math.min(h + 1, options.length - 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setHighlight(h => Math.max(h - 1, 0)) }
    else if (e.key === 'Enter') {
      e.preventDefault()
      if (options[highlight]) pick(options[highlight].value)
    }
  }

  return (
    <div ref={wrapRef} style={{ position: 'relative', width: width ?? '100%' }}>
      <button
        type="button"
        role="combobox"
        aria-expanded={open}
        aria-label={ariaLabel}
        disabled={disabled}
        onClick={() => !disabled && setOpen(o => !o)}
        onKeyDown={onKeyDown}
        style={{
          width: '100%', textAlign: 'left',
          display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8,
          background: disabled ? 'var(--c-bg-hover)' : '#fff',
          color: disabled ? 'var(--c-text-muted)' : 'var(--c-text)',
          border: `1px solid ${open ? 'var(--c-primary)' : '#ddd'}`,
          borderRadius: 'var(--radius-sm)',
          height: 'var(--control-h)', padding: '0 10px', fontSize: 14,
          cursor: disabled ? 'not-allowed' : 'pointer',
        }}
      >
        <span style={{
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          color: selected ? undefined : 'var(--c-text-muted)',
        }}>
          {selected ? selected.label : placeholder}
        </span>
        <span style={{ color: 'var(--c-primary)', fontSize: 'var(--font-sm)', flexShrink: 0 }}>▼</span>
      </button>

      {open && (
        <div
          role="listbox"
          style={{
            position: 'absolute', top: 'calc(100% + 4px)', left: 0, minWidth: '100%', zIndex: 2000,
            background: '#fff', border: '1px solid var(--c-border)',
            borderRadius: 'var(--radius)', boxShadow: '0 6px 18px rgba(0,0,0,0.12)',
            maxHeight: 300, overflowY: 'auto', padding: 4,
          }}
        >
          {allowEmpty && (
            <div
              role="option"
              aria-selected={value === null}
              onClick={() => pick(null)}
              style={{
                padding: '7px 10px', borderRadius: 'var(--radius-sm)', cursor: 'pointer',
                fontSize: 14, color: 'var(--c-text-muted)',
              }}
            >
              {placeholder}
            </div>
          )}
          {options.length === 0 ? (
            <div style={{ padding: '8px 10px', color: 'var(--c-text-muted)', fontSize: 'var(--font-sm)' }}>
              Нет вариантов
            </div>
          ) : options.map((o, i) => {
            const isSelected = o.value === value
            const isHot = i === highlight
            return (
              <div
                key={String(o.value)}
                role="option"
                aria-selected={isSelected}
                onMouseEnter={() => setHighlight(i)}
                onClick={() => pick(o.value)}
                style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10,
                  padding: '7px 10px', borderRadius: 'var(--radius-sm)', cursor: 'pointer',
                  fontSize: 14, whiteSpace: 'nowrap',
                  background: isSelected ? 'var(--c-primary)' : (isHot ? 'var(--c-primary-light)' : 'transparent'),
                  color: isSelected ? '#fff' : 'var(--c-text)',
                  fontWeight: isSelected ? 600 : 400,
                }}
              >
                <span>{o.label}</span>
                {o.hint && (
                  <span style={{
                    fontSize: 'var(--font-sm)',
                    color: isSelected ? 'rgba(255,255,255,0.85)' : 'var(--c-text-secondary)',
                  }}>{o.hint}</span>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
