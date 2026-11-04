import axios from 'axios'

/**
 * HTTP-клиент. Basic-токен лежит в sessionStorage: вкладку закрыли — сессия
 * закончилась, на общем компьютере в офисе это важнее удобства.
 */
const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  headers: { 'Content-Type': 'application/json' },
})

client.interceptors.request.use(config => {
  const auth = sessionStorage.getItem('auth')
  if (auth) config.headers.Authorization = `Basic ${auth}`
  return config
})

client.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      sessionStorage.removeItem('auth')
      // Не редиректим, если уже на странице входа: иначе /api/me → 401 →
      // перезагрузка → снова /api/me, и так по кругу.
      const path = window.location.pathname
      if (path !== '/login' && path !== '/worker-login') window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default client

/** Читаемое сообщение об ошибке из ответа бэкенда. */
export function apiError(e: unknown, fallback = 'Произошла ошибка'): string {
  const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
  if (typeof msg === 'string' && msg.trim()) return msg
  if (e instanceof Error && e.message) return e.message
  return fallback
}
