import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import client from '../api/client'
import type { AuthUser } from '../types'

interface AuthContextType {
  user: AuthUser | null
  loading: boolean
  refresh: () => Promise<void>
  /** Есть ли точечное право. Роль сама по себе прав не задаёт — см. permissions. */
  can: (permission: string) => boolean
  isSupervisor: boolean
  isAdmin: boolean
  isReadonly: boolean
}

const AuthContext = createContext<AuthContextType>({
  user: null, loading: true, refresh: async () => {}, can: () => false,
  isSupervisor: false, isAdmin: false, isReadonly: false,
})

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = async () => {
    if (!sessionStorage.getItem('auth')) {
      setUser(null)
      setLoading(false)
      return
    }
    try {
      const res = await client.get<AuthUser>('/api/me')
      setUser(res.data)
    } catch {
      setUser(null)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void refresh() }, [])

  const role = user?.role
  const value: AuthContextType = {
    user,
    loading,
    refresh,
    can: (permission: string) => !!user?.permissions?.includes(permission),
    isSupervisor: role === 'SUPERVISOR',
    isAdmin: role === 'SUPERVISOR' || role === 'ADMIN',
    isReadonly: role === 'READONLY',
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}
