import type { ReactNode } from 'react'

/** Поле формы: подпись сверху, контрол снизу. Отступы одинаковые везде. */
export default function Field({
  label, children, hint, required,
}: {
  label: string
  children: ReactNode
  hint?: string
  required?: boolean
}) {
  return (
    <div className="form-group">
      <label>
        {label}
        {required && <span style={{ color: 'var(--c-danger)' }}> *</span>}
      </label>
      {children}
      {hint && <div className="small muted" style={{ marginTop: 4 }}>{hint}</div>}
    </div>
  )
}
