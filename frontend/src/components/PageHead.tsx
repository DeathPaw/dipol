import type { ReactNode } from 'react'

/**
 * Шапка страницы: заголовок слева, фильтры по центру, кнопки справа.
 *
 * Группа фильтров позиционируется абсолютно по центру ширины контента, а не
 * потоком между заголовком и кнопками. В потоке поля съезжали бы на каждой
 * странице по-своему — заголовки разной длины, кнопок разное количество, —
 * и оператор искал бы поле заново при каждом переходе.
 */
export default function PageHead({
  title, filters, right, extra,
}: {
  title: string
  filters?: ReactNode
  right?: ReactNode
  extra?: ReactNode
}) {
  return (
    <div className="page-sticky-head">
      <div style={{
        position: 'relative',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        gap: 12, minHeight: 46,
      }}>
        <h1 style={{ margin: 0 }}>{title}</h1>

        {filters && (
          <div className="page-filters" style={{
            position: 'absolute', left: 'min(50%, 726px)', transform: 'translateX(-50%)',
            zIndex: 100,
            display: 'flex', gap: 8, alignItems: 'center',
          }}>
            {filters}
          </div>
        )}

        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginLeft: 'auto' }}>
          {right}
        </div>
      </div>
      {extra && <div className="page-head-extra">{extra}</div>}
    </div>
  )
}

/** Ширины фильтров в шапке. Одинаковы на всех страницах — иначе группа «прыгает». */
export const FILTER_WIDTHS = { first: 190, second: 130, third: 300 } as const

/** Стиль главной кнопки страницы: фиксированная ширина, чтобы стояла на месте. */
export const pageActionBtn: React.CSSProperties = {
  width: 180, textAlign: 'center', whiteSpace: 'nowrap',
}
