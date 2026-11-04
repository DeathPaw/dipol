import { useEscapeClose } from '../hooks/useEscapeClose'

export default function ConfirmModal({
  title, message, onConfirm, onCancel,
  confirmText = 'Подтвердить', cancelText = 'Отмена', danger,
}: {
  title: string
  message: string
  onConfirm: () => void
  onCancel: () => void
  confirmText?: string
  cancelText?: string
  danger?: boolean
}) {
  useEscapeClose(true, onCancel)
  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div className="modal" onClick={e => e.stopPropagation()} style={{ maxWidth: 420 }}>
        <h2>{title}</h2>
        <p style={{ color: '#555', marginBottom: 16 }}>{message}</p>
        <div className="modal-actions">
          <button className="btn-secondary" onClick={onCancel}>{cancelText}</button>
          <button className={danger ? 'btn-danger' : 'btn-primary'} onClick={onConfirm}>
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  )
}
