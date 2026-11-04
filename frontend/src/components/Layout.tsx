import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import './Layout.css'

const ROLE_LABELS: Record<string, string> = {
  SUPERVISOR: 'Руководитель',
  ADMIN: 'Администратор',
  OPERATOR: 'Оператор',
  FOREMAN: 'Прораб/мастер',
  WORKER: 'Рабочий',
  SAFETY: 'Техник по ТБ',
  READONLY: 'Только просмотр',
}

/**
 * Каркас управленческого и операционного контуров.
 *
 * Разделы сгруппированы по рабочему дню: сначала ежедневное (заявки,
 * планирование, исполнение), потом ресурсы и деньги, потом настройки.
 * Финансовые разделы показываются по праву, а не по роли: право на кассы
 * может быть выдано точечно (US-N1).
 */
export default function Layout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, can, isAdmin, isSupervisor, isReadonly } = useAuth()

  const logout = () => {
    sessionStorage.removeItem('auth')
    window.location.href = '/login'
  }

  const navClass = (extra = '') =>
    ({ isActive }: { isActive: boolean }) =>
      `nav-link${extra ? ' ' + extra : ''}${isActive ? ' active' : ''}`

  const showBack = location.pathname !== '/dashboard'

  return (
    <div className="app">
      <aside className="app-sidebar">
        <div className="sidebar-brand">
          ERP «Диполь»
          <small>кровельные работы</small>
        </div>

        {showBack && (
          <button onClick={() => navigate(-1)} className="sidebar-back" title="Назад">
            ← Назад
          </button>
        )}

        <div className="sidebar-section-label">Операционный контур</div>
        <nav className="sidebar-nav">
          <NavLink to="/dashboard" className={navClass()}>Главная</NavLink>
          <NavLink to="/requests" className={navClass()}>Заявки</NavLink>
          <NavLink to="/trips" className={navClass()}>Выезды</NavLink>
          <NavLink to="/equipment-shifts" className={navClass()}>Спецтехника</NavLink>
          <NavLink to="/buildings" className={navClass()}>Дома и адреса</NavLink>
          <NavLink to="/clients" className={navClass()}>Клиенты</NavLink>
          <NavLink to="/projects" className={navClass()}>Проекты</NavLink>
          <NavLink to="/rk3" className={navClass()}>РК-3</NavLink>
        </nav>

        <div className="sidebar-section-label">Ресурсы</div>
        <nav className="sidebar-nav">
          <NavLink to="/materials" className={navClass()}>Материалы и склад</NavLink>
          <NavLink to="/transport" className={navClass()}>Транспорт</NavLink>
          <NavLink to="/safety" className={navClass()}>Техника безопасности</NavLink>
          <NavLink to="/documents" className={navClass()}>Документы</NavLink>
        </nav>

        {(can('PAYROLL_VIEW') || can('FINANCE_VIEW')) && (
          <>
            <div className="sidebar-section-label">Деньги</div>
            <nav className="sidebar-nav">
              {can('PAYROLL_VIEW') && <NavLink to="/payroll" className={navClass()}>ФОТ</NavLink>}
              {can('PAYROLL_VIEW') && <NavLink to="/compensations" className={navClass()}>Компенсации</NavLink>}
              {can('PAYROLL_VIEW') && <NavLink to="/bridge" className={navClass()}>Отчёт «Мост»</NavLink>}
              {can('FINANCE_VIEW') && <NavLink to="/finance" className={navClass()}>Кассы</NavLink>}
            </nav>
          </>
        )}

        <div className="sidebar-section-label">Аналитика</div>
        <nav className="sidebar-nav">
          <NavLink to="/analytics" className={navClass()}>Отчёты и план-факт</NavLink>
          <NavLink to="/data-quality" className={navClass()}>Качество данных</NavLink>
        </nav>

        {isAdmin && (
          <>
            <div className="sidebar-section-label" style={{ color: '#e67e22' }}>
              {isSupervisor ? 'Руководитель' : 'Администратор'}
            </div>
            <nav className="sidebar-nav">
              <NavLink to="/references" className={navClass('admin-link')}>Справочники</NavLink>
              <NavLink to="/project-configs" className={navClass('admin-link')}>Конфигурации проектов</NavLink>
              <NavLink to="/employees" className={navClass('admin-link')}>Сотрудники</NavLink>
              <NavLink to="/import" className={navClass('admin-link')}>Импорт и миграция</NavLink>
              <NavLink to="/users" className={navClass('admin-link')}>Пользователи</NavLink>
              <NavLink to="/audit-log" className={navClass('admin-link')}>Журнал действий</NavLink>
              <NavLink to="/error-log" className={navClass('admin-link')}>Лог ошибок</NavLink>
            </nav>
          </>
        )}

        <div className="sidebar-footer">
          {user && (
            <div className="sidebar-user">
              <b>{user.display_name}</b>
              {ROLE_LABELS[user.role] || user.role}
            </div>
          )}
          <button onClick={logout} className="nav-link" style={{ opacity: 0.75 }}>Выход</button>
        </div>
      </aside>

      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        {isReadonly && (
          <div style={{
            background: '#34495e', color: '#fff', padding: '6px 16px',
            fontSize: 'var(--font-sm)', fontWeight: 500, textAlign: 'center',
          }}>
            Режим просмотра — изменения отключены
          </div>
        )}
        <main className="main-content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
