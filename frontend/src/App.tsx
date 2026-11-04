import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import Layout from './components/Layout'
import ErrorBoundary from './components/ErrorBoundary'
import { ToastProvider } from './components/Toast'
import { AuthProvider, useAuth } from './auth/AuthContext'

import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import RequestsPage from './pages/RequestsPage'
import RequestDetailPage from './pages/RequestDetailPage'
import TripsPage from './pages/TripsPage'
import EquipmentShiftsPage from './pages/EquipmentShiftsPage'
import BuildingsPage from './pages/BuildingsPage'
import BuildingDetailPage from './pages/BuildingDetailPage'
import ClientsPage from './pages/ClientsPage'
import ProjectsPage from './pages/ProjectsPage'
import ProjectConfigsPage from './pages/ProjectConfigsPage'
import Rk3Page from './pages/Rk3Page'
import MaterialsPage from './pages/MaterialsPage'
import TransportPage from './pages/TransportPage'
import SafetyPage from './pages/SafetyPage'
import DocumentsPage from './pages/DocumentsPage'
import PayrollPage from './pages/PayrollPage'
import CompensationsPage from './pages/CompensationsPage'
import BridgePage from './pages/BridgePage'
import FinancePage from './pages/FinancePage'
import AnalyticsPage from './pages/AnalyticsPage'
import DataQualityPage from './pages/DataQualityPage'
import ReferencesPage from './pages/ReferencesPage'
import EmployeesPage from './pages/EmployeesPage'
import ImportPage from './pages/ImportPage'
import UsersPage from './pages/UsersPage'
import AuditLogPage from './pages/AuditLogPage'
import ErrorLogPage from './pages/ErrorLogPage'
import WorkerLoginPage from './pages/worker/WorkerLoginPage'
import WorkerHomePage from './pages/worker/WorkerHomePage'

function RequireAuth({ children }: { children: ReactNode }) {
  if (!sessionStorage.getItem('auth')) return <Navigate to="/login" replace />
  return <>{children}</>
}

/** Кабинет исполнителя — отдельная сессия по PIN, без Basic-логина. */
function RequireWorker({ children }: { children: ReactNode }) {
  if (!sessionStorage.getItem('worker_employee_id')) return <Navigate to="/worker-login" replace />
  return <>{children}</>
}

function RequireAdmin({ children }: { children: ReactNode }) {
  const { user, loading, isAdmin } = useAuth()
  if (loading) return <div className="loading">Загрузка…</div>
  if (!user) return <Navigate to="/login" replace />
  if (!isAdmin) return <Navigate to="/dashboard" replace />
  return <>{children}</>
}

/** Раздел, закрытый точечным правом: ФОТ, кассы, себестоимость. */
function RequirePermission({ permission, children }: { permission: string; children: ReactNode }) {
  const { user, loading, can } = useAuth()
  if (loading) return <div className="loading">Загрузка…</div>
  if (!user) return <Navigate to="/login" replace />
  if (!can(permission)) {
    return (
      <div className="notice notice-warning">
        Раздел недоступен: требуется право «{permission}». Обратитесь к администратору системы.
      </div>
    )
  }
  return <>{children}</>
}

export default function App() {
  return (
    <ErrorBoundary>
      <ToastProvider>
        <AuthProvider>
          <BrowserRouter>
            <Routes>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/worker-login" element={<WorkerLoginPage />} />
              <Route path="/worker" element={<RequireWorker><WorkerHomePage /></RequireWorker>} />

              <Route path="/" element={<RequireAuth><Layout /></RequireAuth>}>
                <Route index element={<Navigate to="/dashboard" replace />} />
                <Route path="dashboard" element={<DashboardPage />} />
                <Route path="requests" element={<RequestsPage />} />
                <Route path="requests/:id" element={<RequestDetailPage />} />
                <Route path="trips" element={<TripsPage />} />
                <Route path="equipment-shifts" element={<EquipmentShiftsPage />} />
                <Route path="buildings" element={<BuildingsPage />} />
                <Route path="buildings/:id" element={<BuildingDetailPage />} />
                <Route path="clients" element={<ClientsPage />} />
                <Route path="projects" element={<ProjectsPage />} />
                <Route path="rk3" element={<Rk3Page />} />
                <Route path="materials" element={<MaterialsPage />} />
                <Route path="transport" element={<TransportPage />} />
                <Route path="safety" element={<SafetyPage />} />
                <Route path="documents" element={<DocumentsPage />} />
                <Route path="analytics" element={<AnalyticsPage />} />
                <Route path="data-quality" element={<DataQualityPage />} />

                <Route path="payroll" element={
                  <RequirePermission permission="PAYROLL_VIEW"><PayrollPage /></RequirePermission>} />
                <Route path="compensations" element={
                  <RequirePermission permission="PAYROLL_VIEW"><CompensationsPage /></RequirePermission>} />
                <Route path="bridge" element={
                  <RequirePermission permission="PAYROLL_VIEW"><BridgePage /></RequirePermission>} />
                <Route path="finance" element={
                  <RequirePermission permission="FINANCE_VIEW"><FinancePage /></RequirePermission>} />

                <Route path="references" element={<RequireAdmin><ReferencesPage /></RequireAdmin>} />
                <Route path="project-configs" element={<RequireAdmin><ProjectConfigsPage /></RequireAdmin>} />
                <Route path="employees" element={<RequireAdmin><EmployeesPage /></RequireAdmin>} />
                <Route path="import" element={<RequireAdmin><ImportPage /></RequireAdmin>} />
                <Route path="users" element={<RequireAdmin><UsersPage /></RequireAdmin>} />
                <Route path="audit-log" element={<RequireAdmin><AuditLogPage /></RequireAdmin>} />
                <Route path="error-log" element={<RequireAdmin><ErrorLogPage /></RequireAdmin>} />
              </Route>
            </Routes>
          </BrowserRouter>
        </AuthProvider>
      </ToastProvider>
    </ErrorBoundary>
  )
}
