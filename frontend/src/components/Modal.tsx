import type { ReactNode } from 'react'
import { useEscapeClose } from '../hooks/useEscapeClose'

/** Модальное окно с заголовком и полосой действий. Ширина — три градации. */
export default function Modal({
  title, onClose, children, actions, size = 'normal',
}: {
  title: string
  onClose: () => void
  children: ReactNode
  actions?: ReactNode
  size?: 'normal' | 'large' | 'wide'
}) {
  useEscapeClose(true, onClose)
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className={`modal${size === 'large' ? ' large' : size === 'wide' ? ' wide' : ''}`}
        onClick={e => e.stopPropagation()}
      >
        <h2>{title}</h2>
        {children}
        {actions && <div className="modal-actions">{actions}</div>}
      </div>
    </div>
  )
}
