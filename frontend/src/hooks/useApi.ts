import { useCallback, useEffect, useState } from 'react'
import client, { apiError } from '../api/client'

/**
 * Загрузка данных с состояниями «грузится / ошибка / данные».
 *
 * Пересоздание запроса завязано на `deps`, а не на саму функцию: иначе
 * каждый рендер страницы дёргал бы бэкенд заново.
 */
export function useApi<T>(url: string | null, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(!!url)
  const [error, setError] = useState('')

  const reload = useCallback(async () => {
    if (!url) return
    setLoading(true)
    setError('')
    try {
      const res = await client.get<T>(url)
      setData(res.data)
    } catch (e) {
      setError(apiError(e, 'Не удалось загрузить данные'))
    } finally {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, ...deps])

  useEffect(() => { void reload() }, [reload])

  return { data, loading, error, reload, setData }
}
