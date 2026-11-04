/** Общие форматтеры. Дублировать их по страницам — верный способ получить
    разное написание одной и той же даты в соседних таблицах. */

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return d.toLocaleDateString('ru')
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return d.toLocaleString('ru', { dateStyle: 'short', timeStyle: 'short' })
}

/** «10 000,00 ₽». Ноль и null различаются: null — «—», ноль — настоящий ноль. */
export function formatRub(amount: number | string | null | undefined): string {
  if (amount === null || amount === undefined || amount === '') return '—'
  return Number(amount).toLocaleString('ru', {
    minimumFractionDigits: 2, maximumFractionDigits: 2,
  }) + ' ₽'
}

export function formatQty(value: number | string | null | undefined, unit?: string | null): string {
  if (value === null || value === undefined || value === '') return '—'
  const n = Number(value)
  const s = n.toLocaleString('ru', { maximumFractionDigits: 3 })
  return unit ? `${s} ${unitName(unit)}` : s
}

const UNITS: Record<string, string> = {
  M2: 'м²', M3: 'м³', MP: 'м.пог.', PCS: 'шт.', MH: 'чел·ч', HOUR: 'ч',
  SHIFT: 'смена', RUN: 'рейс', KM: 'км', ROLL: 'рулон', KG: 'кг', L: 'л', SET: 'к-т',
}

export function unitName(code: string | null | undefined): string {
  if (!code) return ''
  return UNITS[code] || code
}

/** Первое число текущего месяца в формате ISO — частый дефолт фильтров периода. */
export function monthStart(date = new Date()): string {
  return new Date(date.getFullYear(), date.getMonth(), 1).toISOString().slice(0, 10)
}

export function today(): string {
  return new Date().toISOString().slice(0, 10)
}

export function daysAgo(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  return d.toISOString().slice(0, 10)
}
