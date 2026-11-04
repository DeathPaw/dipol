/** Бейдж статуса заявки. Класс собирается из кода — цвета лежат в index.css. */
export default function StatusBadge({ status, name }: { status: string; name?: string | null }) {
  return <span className={`badge badge-${status}`}>{name || STATUS_NAMES[status] || status}</span>
}

export const STATUS_NAMES: Record<string, string> = {
  CREATED: 'Создана',
  ACCEPTED: 'Принята в работу',
  PLANNED: 'Запланирована',
  IN_PROGRESS: 'В работе',
  DONE: 'Выполнена',
  CUSTOMER_ACCEPTED: 'Принята заказчиком',
  ACT_CREATED: 'Акт создан',
  ACT_SIGNED: 'Акт подписан',
  CANCELLED: 'Отменена',
}

/** Порядок статусов в фильтрах и на дашборде. */
export const STATUS_ORDER = [
  'CREATED', 'ACCEPTED', 'PLANNED', 'IN_PROGRESS', 'DONE',
  'CUSTOMER_ACCEPTED', 'ACT_CREATED', 'ACT_SIGNED', 'CANCELLED',
]

export const ITEM_STATUS_NAMES: Record<string, string> = {
  PLANNED: 'Запланирована',
  IN_PROGRESS: 'В работе',
  DONE: 'Выполнена',
  ON_REVIEW: 'На приёмке',
  ACCEPTED: 'Принята',
  REWORK: 'На доработку',
  CANCELLED: 'Отменена',
}
