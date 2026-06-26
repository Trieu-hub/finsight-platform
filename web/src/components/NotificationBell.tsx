import { useCallback, useEffect, useRef, useState } from 'react'
import type { Notification, NotificationSeverity } from '../api/types'
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  unreadNotificationCount,
} from '../api/endpoints'

// Poll the unread count this often. Notifications arrive via Kafka on the backend,
// so the FE has no push channel — a light poll is enough for a bell badge.
const POLL_MS = 25_000

// Severity → dot/accent colour. Falls back to neutral for anything unexpected.
const SEVERITY_STYLES: Record<NotificationSeverity, string> = {
  HIGH: 'bg-rose-500',
  MEDIUM: 'bg-amber-500',
  LOW: 'bg-neutral-500',
}

function severityDot(severity: string) {
  return SEVERITY_STYLES[severity as NotificationSeverity] ?? 'bg-neutral-500'
}

function timeAgo(iso: string): string {
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return ''
  const secs = Math.max(0, Math.floor((Date.now() - then) / 1000))
  if (secs < 60) return 'just now'
  const mins = Math.floor(secs / 60)
  if (mins < 60) return `${mins}m ago`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

export default function NotificationBell() {
  const [unread, setUnread] = useState(0)
  const [items, setItems] = useState<Notification[]>([])
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  // Poll the unread count. Wrapped in try/catch so a transient gateway error
  // (or the user simply not being logged in) never bubbles up and breaks Layout.
  const refreshCount = useCallback(async () => {
    try {
      setUnread(await unreadNotificationCount())
    } catch {
      // swallow — keep the last known count
    }
  }, [])

  useEffect(() => {
    refreshCount()
    const id = setInterval(refreshCount, POLL_MS)
    return () => clearInterval(id)
  }, [refreshCount])

  // Close on click outside.
  useEffect(() => {
    if (!open) return
    function onClick(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onClick)
    return () => document.removeEventListener('mousedown', onClick)
  }, [open])

  async function loadList() {
    setLoading(true)
    try {
      setItems(await listNotifications(false))
    } catch {
      setItems([])
    } finally {
      setLoading(false)
    }
  }

  function toggle() {
    const next = !open
    setOpen(next)
    if (next) loadList()
  }

  async function onMarkRead(n: Notification) {
    if (n.read) return
    try {
      await markNotificationRead(n.id)
      setItems((prev) => prev.map((it) => (it.id === n.id ? { ...it, read: true } : it)))
      setUnread((c) => Math.max(0, c - 1))
    } catch {
      // ignore; next poll/open will reconcile
    }
  }

  async function onMarkAll() {
    try {
      await markAllNotificationsRead()
      setItems((prev) => prev.map((it) => ({ ...it, read: true })))
      setUnread(0)
    } catch {
      // ignore
    }
  }

  return (
    <div ref={containerRef} className="relative">
      <button
        onClick={toggle}
        aria-label="Notifications"
        className="relative rounded-lg border border-neutral-800 px-2.5 py-1.5 text-neutral-400 transition hover:border-neutral-700 hover:bg-neutral-800 hover:text-neutral-100"
      >
        {/* bell glyph */}
        <svg
          xmlns="http://www.w3.org/2000/svg"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="h-5 w-5"
        >
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
        {unread > 0 && (
          <span className="absolute -right-1 -top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-rose-500 px-1 text-[10px] font-bold text-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 z-20 mt-2 w-80 overflow-hidden rounded-xl border border-neutral-800 bg-neutral-900 shadow-xl shadow-black/40">
          <div className="flex items-center justify-between border-b border-neutral-800 px-4 py-2.5">
            <span className="text-sm font-semibold text-neutral-200">Notifications</span>
            {unread > 0 && (
              <button
                onClick={onMarkAll}
                className="text-xs font-medium text-emerald-400 hover:text-emerald-300"
              >
                Mark all read
              </button>
            )}
          </div>

          <div className="max-h-96 overflow-y-auto">
            {loading ? (
              <p className="px-4 py-6 text-center text-sm text-neutral-500">Loading…</p>
            ) : items.length === 0 ? (
              <p className="px-4 py-6 text-center text-sm text-neutral-500">No notifications</p>
            ) : (
              items.map((n) => (
                <button
                  key={n.id}
                  onClick={() => onMarkRead(n)}
                  className={`flex w-full gap-3 border-b border-neutral-800/60 px-4 py-3 text-left transition last:border-b-0 hover:bg-neutral-800/50 ${
                    n.read ? 'opacity-60' : ''
                  }`}
                >
                  <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${severityDot(n.severity)}`} />
                  <span className="min-w-0 flex-1">
                    <span className="flex items-baseline justify-between gap-2">
                      <span className="truncate text-sm font-medium text-neutral-100">
                        {n.title}
                      </span>
                      <span className="shrink-0 text-[11px] text-neutral-500">
                        {timeAgo(n.createdAt)}
                      </span>
                    </span>
                    <span className="mt-0.5 block text-xs text-neutral-400">{n.message}</span>
                  </span>
                  {!n.read && <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-emerald-400" />}
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  )
}
