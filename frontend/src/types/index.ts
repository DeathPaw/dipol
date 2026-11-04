/** Типы домена. Имена полей — snake_case: ровно как их отдаёт API. */

export type UserRole =
  | 'SUPERVISOR' | 'ADMIN' | 'OPERATOR' | 'FOREMAN' | 'WORKER' | 'SAFETY' | 'READONLY'

export interface AuthUser {
  id: number
  username: string
  display_name: string
  role: UserRole
  employee_id: number | null
  permissions: string[]
}

export interface PageResponse<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface Reference {
  code: string
  name: string
  sort_order?: number
}

export interface Unit extends Reference {
  kind: string
}

export interface Sku {
  id: number
  code: string
  name: string
  group_id: number | null
  group_name: string | null
  unit_code: string
  act_wording: string | null
  requires_photo: boolean
  is_active: boolean
  description: string | null
  sort_order: number
}

export interface Zheu {
  id: number
  legacy_id: number | null
  name: string
  head_name: string | null
  address: string | null
  phone: string | null
  email: string | null
  settlements: string | null
  is_active: boolean
}

export interface Building {
  id: number
  legacy_id: number | null
  address: string
  address_key: string
  fias_id: string | null
  kladr: string | null
  zheu_id: number | null
  zheu_name: string | null
  floors: number | null
  entrances_count: number | null
  apartments_count: number | null
  apartments_per_entrance: number | null
  roof_type: string | null
  condition_note: string | null
  roof_access: string | null
  attic_access: string | null
  basement_access: string | null
  access_note: string | null
  lat: number | null
  lon: number | null
  is_active: boolean
  merged_into_id: number | null
}

export interface BuildingEntrance {
  id: number | null
  building_id: number
  number: number
  apartment_from: number | null
  apartment_to: number | null
  floors: number | null
  note: string | null
}

export interface Client {
  id: number
  client_type: 'LEGAL' | 'IP' | 'PERSON'
  name: string
  short_name: string | null
  inn: string | null
  kpp: string | null
  ogrn: string | null
  legal_address: string | null
  actual_address: string | null
  phone: string | null
  email: string | null
  comment: string | null
  is_active: boolean
}

export interface ClientContact {
  id: number
  client_id: number
  full_name: string
  position: string | null
  contact_role: string | null
  phone: string | null
  email: string | null
  messenger: string | null
  comment: string | null
  is_active: boolean
}

export interface Employee {
  id: number
  full_name: string
  short_name: string | null
  role_code: string | null
  role_name: string | null
  position: string | null
  phone: string | null
  email: string | null
  is_active: boolean
  note: string | null
  sku_ids: number[]
}

export interface ProjectConfig {
  id: number
  code: string
  name: string
  description: string | null
  use_work_objects: boolean
  default_lifecycle: 'PERMANENT' | 'ONE_OFF'
  allowed_pay_schemes: string[]
  materials_mode: 'FACT' | 'PLAN_FACT'
  current_version: number
  is_active: boolean
  projects_count: number
}

export interface Project {
  id: number
  code: string
  name: string
  client_id: number
  client_name: string
  config_id: number
  config_code: string
  config_name: string
  config_version: number
  lifecycle_type: 'PERMANENT' | 'ONE_OFF'
  started_on: string | null
  finish_criteria: string | null
  finished_on: string | null
  status: 'ACTIVE' | 'SUSPENDED' | 'CLOSED'
  responsible_employee_id: number | null
  responsible_name: string | null
  season_from: string | null
  season_to: string | null
  contract_number: string | null
  note: string | null
}

export interface Request {
  id: number
  number: string
  external_number: string | null
  external_system: string | null
  source_code: string | null
  client_id: number
  client_name: string
  project_id: number
  project_code: string
  project_name: string
  config_id: number
  building_id: number
  building_address: string
  building_legacy_id: number | null
  zheu_id: number | null
  zheu_name: string | null
  apartment: string | null
  entrance: number | null
  entrance_is_manual: boolean
  entrance_auto: number | null
  place_note: string | null
  received_at_customer: string | null
  received_at: string
  planned_date: string | null
  done_at: string | null
  customer_accepted_at: string | null
  urgency: 'EMERGENCY' | 'PLANNED'
  is_warranty: boolean
  parent_request_id: number | null
  link_type: string | null
  status: string
  status_name: string | null
  financial_class: string | null
  customer_closed: boolean
  is_closed: boolean
  cancel_reason: string | null
  priority: number
  priority_reason: string | null
  description: string | null
  act_wording: string | null
  internal_comment: string | null
  responsible_employee_id: number | null
  responsible_name: string | null
  report_link: string | null
  photo_link: string | null
  report_text: string | null
  created_by: string | null
  created_at: string
}

export interface RequestItem {
  id: number
  request_id: number
  work_object_id: number | null
  work_object_name: string | null
  sku_id: number
  sku_code: string
  sku_name: string
  unit_code: string
  planned_qty: number | null
  declared_qty: number | null
  fact_qty: number | null
  remeasured_qty: number | null
  accepted_qty: number | null
  man_hours: number | null
  reference_qty: number | null
  reference_unit_code: string | null
  repair_type: string | null
  status: string
  accepted_by: string | null
  accepted_at: string | null
  accept_comment: string | null
  note: string | null
  sort_order: number
  assignee_ids: number[]
}

export interface RequestEvent {
  id: number
  request_id: number
  event_type: string
  text: string | null
  wait_reason: string | null
  actor: string | null
  created_at: string
}

export interface ChecklistItem {
  id: number
  request_id: number
  item_code: string
  name: string
  is_required: boolean
  is_done: boolean
  done_at: string | null
  done_by: string | null
  skipped_reason: string | null
}

export interface RequestDetail {
  request: Request
  items: RequestItem[]
  work_objects: Record<string, unknown>[]
  events: RequestEvent[]
  status_history: Record<string, unknown>[]
  checklist: ChecklistItem[]
  linked: Request[]
  trips: Record<string, unknown>[]
  warnings: string[]
}

/** Строки, приходящие «как есть» из queryForList — типизируем по месту. */
export type Row = Record<string, any>
