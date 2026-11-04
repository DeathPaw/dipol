/** Вкладки внутри страницы: справочники, аналитика, карточка проекта. */
export default function Tabs<T extends string>({
  tabs, value, onChange,
}: {
  tabs: { key: T; label: string; hint?: string | number }[]
  value: T
  onChange: (key: T) => void
}) {
  return (
    <div className="tabs">
      {tabs.map(t => (
        <button
          key={t.key}
          className={t.key === value ? 'active' : ''}
          onClick={() => onChange(t.key)}
        >
          {t.label}
          {t.hint !== undefined && t.hint !== '' && (
            <span className="muted small" style={{ marginLeft: 6 }}>{t.hint}</span>
          )}
        </button>
      ))}
    </div>
  )
}
