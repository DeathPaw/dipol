import type { ReactNode } from 'react'

export interface Column<T> {
  key: string
  title: string
  /** Как отрисовать ячейку. По умолчанию — значение поля с ключом `key`. */
  render?: (row: T) => ReactNode
  width?: number | string
  align?: 'left' | 'right' | 'center'
  nowrap?: boolean
}

/**
 * Таблица списка.
 *
 * Своя разметка таблицы на каждой из двадцати страниц означала бы двадцать
 * вариантов пустого состояния, состояния загрузки и выравнивания чисел.
 */
export default function DataTable<T extends { id?: number | string }>({
  columns, rows, loading, empty = 'Нет данных', onRowClick, rowKey, rowClass,
}: {
  columns: Column<T>[]
  rows: T[]
  loading?: boolean
  empty?: string
  onRowClick?: (row: T) => void
  rowKey?: (row: T, index: number) => string | number
  rowClass?: (row: T) => string | undefined
}) {
  if (loading) return <div className="loading">Загрузка…</div>
  if (!rows.length) return <div className="empty">{empty}</div>

  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            {columns.map(c => (
              <th key={c.key} style={{ width: c.width, textAlign: c.align }}>{c.title}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr
              key={rowKey ? rowKey(row, i) : (row.id ?? i)}
              className={rowClass?.(row)}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              style={onRowClick ? { cursor: 'pointer' } : undefined}
            >
              {columns.map(c => (
                <td
                  key={c.key}
                  style={{ textAlign: c.align, whiteSpace: c.nowrap ? 'nowrap' : undefined }}
                  className={c.align === 'right' ? 'num' : undefined}
                >
                  {c.render ? c.render(row) : String((row as Record<string, unknown>)[c.key] ?? '—')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
