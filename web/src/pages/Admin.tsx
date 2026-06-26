import { useEffect, useState } from 'react'
import { deleteUser, listUsers, updateUserRole, updateUserStatus } from '../api/endpoints'
import { errorMessage } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import type { AdminUser } from '../api/types'

const ROLES = ['ROLE_USER', 'ROLE_ANALYST', 'ROLE_ADMIN']

export default function Admin() {
  const { email: myEmail } = useAuth()
  const [users, setUsers] = useState<AdminUser[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState<number | null>(null)

  async function load() {
    try {
      setUsers(await listUsers())
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  async function run(id: number, action: () => Promise<unknown>) {
    setError('')
    setBusyId(id)
    try {
      await action()
      await load()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusyId(null)
    }
  }

  const total = users.length
  const admins = users.filter((u) => u.role === 'ROLE_ADMIN').length
  const active = users.filter((u) => u.enabled).length

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-neutral-400">
          Admin · User management
        </h2>
        <span className="rounded-full border border-emerald-500/30 bg-emerald-500/10 px-2 py-0.5 text-xs font-medium text-emerald-300">
          ROLE_ADMIN
        </span>
      </div>

      {/* Summary */}
      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="Total users" value={total} accent="from-emerald-500/20" />
        <Stat label="Admins" value={admins} accent="from-teal-500/20" />
        <Stat label="Active" value={active} accent="from-emerald-500/10" />
      </div>

      <p className="text-sm text-neutral-500">
        Change roles, enable/disable, or remove accounts. Access enforced server-side — not
        just hidden in the UI. You cannot modify your own account.
      </p>

      {error && <p className="text-sm text-red-400">{error}</p>}

      {loading ? (
        <p className="text-neutral-500">Loading…</p>
      ) : (
        <div className="overflow-hidden rounded-2xl border border-neutral-800 bg-neutral-900">
          <table className="w-full text-sm">
            <thead className="bg-neutral-950/40 text-left text-neutral-400">
              <tr>
                <th className="px-4 py-2.5 font-medium">User</th>
                <th className="px-4 py-2.5 font-medium">Role</th>
                <th className="px-4 py-2.5 font-medium">Status</th>
                <th className="px-4 py-2.5 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => {
                const isSelf = !!myEmail && myEmail.toLowerCase() === u.email.toLowerCase()
                const busy = busyId === u.id
                return (
                  <tr key={u.id} className="border-t border-neutral-800 transition hover:bg-neutral-800/40">
                    <td className="px-4 py-2.5">
                      <div className="font-medium text-neutral-200">
                        {u.username}
                        {isSelf && <span className="ml-2 text-xs text-emerald-400">(you)</span>}
                      </div>
                      <div className="text-xs text-neutral-500">{u.email}</div>
                    </td>
                    <td className="px-4 py-2.5">
                      <select
                        value={u.role}
                        disabled={isSelf || busy}
                        onChange={(e) => run(u.id, () => updateUserRole(u.id, e.target.value))}
                        className="rounded-lg border border-neutral-700 bg-neutral-950/60 px-2 py-1 text-xs text-neutral-200 outline-none transition focus:border-emerald-500 disabled:opacity-50"
                      >
                        {ROLES.map((r) => (
                          <option key={r} value={r}>
                            {r.replace('ROLE_', '')}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="px-4 py-2.5">
                      <span className={u.enabled ? 'text-emerald-400' : 'text-rose-400'}>
                        {u.enabled ? 'Active' : 'Disabled'}
                      </span>
                    </td>
                    <td className="px-4 py-2.5">
                      <div className="flex justify-end gap-2">
                        <button
                          disabled={isSelf || busy}
                          onClick={() => run(u.id, () => updateUserStatus(u.id, !u.enabled))}
                          className="rounded-lg border border-neutral-700 px-2.5 py-1 text-xs font-medium text-neutral-300 transition hover:bg-neutral-800 disabled:opacity-40"
                        >
                          {u.enabled ? 'Disable' : 'Enable'}
                        </button>
                        <button
                          disabled={isSelf || busy}
                          onClick={() => {
                            if (confirm(`Delete user "${u.username}"? This cannot be undone.`)) {
                              run(u.id, () => deleteUser(u.id))
                            }
                          }}
                          className="rounded-lg border border-rose-500/30 px-2.5 py-1 text-xs font-medium text-rose-300 transition hover:bg-rose-500/10 disabled:opacity-40"
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function Stat({ label, value, accent }: { label: string; value: number; accent: string }) {
  return (
    <div className={`rounded-2xl border border-neutral-800 bg-gradient-to-br ${accent} to-neutral-900 p-5`}>
      <div className="text-xs font-medium uppercase tracking-wide text-neutral-400">{label}</div>
      <div className="mt-2 text-2xl font-bold text-neutral-100">{value}</div>
    </div>
  )
}
