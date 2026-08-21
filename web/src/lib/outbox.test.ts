import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearOutbox, enqueue, flush, queued, queuedCount, remove } from './outbox'

const BODY = {
  type: 'EXPENSE' as const,
  amount: 42,
  currency: 'USD',
  categoryId: 4,
  transactionDate: '2026-06-01',
}

describe('offline outbox', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('keeps a queued write, with a token of its own', () => {
    const item = enqueue(BODY)

    expect(item).not.toBeNull()
    expect(item!.clientRequestId).toMatch(/[0-9a-f-]{36}/)
    expect(queuedCount()).toBe(1)
    // The date the user picked travels with it. Without that, a write composed on Tuesday and
    // sent on Thursday would book itself on Thursday — the reason a write queue was refused
    // before this existed.
    expect(queued()[0].body.transactionDate).toBe('2026-06-01')
  })

  it('gives each queued write a distinct token', () => {
    const first = enqueue(BODY)
    const second = enqueue(BODY)

    // Two identical coffees are two coffees; sharing a token would make the second one silently
    // resolve to the first on the server.
    expect(first!.clientRequestId).not.toEqual(second!.clientRequestId)
  })

  it('survives a reload, which is the entire point of storing it', () => {
    enqueue(BODY)
    // A fresh read is what a new page load does.
    expect(queuedCount()).toBe(1)
    expect(queued()[0].body.amount).toBe(42)
  })

  it('refuses to grow without bound', () => {
    for (let i = 0; i < 100; i++) enqueue(BODY)

    // The 101st is rejected rather than silently dropped later: the form can then tell the user,
    // instead of pretending to have saved something that will never be sent.
    expect(enqueue(BODY)).toBeNull()
    expect(queuedCount()).toBe(100)
  })

  it('sends oldest first and clears what landed', async () => {
    const first = enqueue({ ...BODY, amount: 1 })!
    enqueue({ ...BODY, amount: 2 })
    const order: number[] = []

    const sent = await flush(async (item) => {
      order.push(item.body.amount)
    }, () => false)

    expect(sent).toBe(2)
    expect(order).toEqual([1, 2])
    expect(queuedCount()).toBe(0)
    expect(queued().find((i) => i.clientRequestId === first.clientRequestId)).toBeUndefined()
  })

  it('stops at the first failure and keeps the rest queued', async () => {
    enqueue({ ...BODY, amount: 1 })
    enqueue({ ...BODY, amount: 2 })
    const send = vi.fn(async () => {
      throw new Error('network down')
    })

    const sent = await flush(send, () => false)

    expect(sent).toBe(0)
    // Not "try them all anyway": if the network is still down the rest cannot succeed either, and
    // hammering them only burns battery.
    expect(send).toHaveBeenCalledTimes(1)
    expect(queuedCount()).toBe(2)
  })

  it('drops a write the server will never accept, so it cannot block the queue', async () => {
    enqueue({ ...BODY, amount: 1 })
    enqueue({ ...BODY, amount: 2 })
    const seen: number[] = []

    const sent = await flush(
      async (item) => {
        seen.push(item.body.amount)
        if (item.body.amount === 1) throw new Error('422 unprocessable')
      },
      (error) => String(error).includes('422'),
    )

    // The bad one is discarded and the good one still gets through — otherwise one malformed
    // write would pin everything behind it forever.
    expect(sent).toBe(1)
    expect(seen).toEqual([1, 2])
    expect(queuedCount()).toBe(0)
  })

  it('clears on sign-out, since a queue belongs to the account that filled it', () => {
    enqueue(BODY)

    clearOutbox()

    expect(queuedCount()).toBe(0)
  })

  it('treats unreadable storage as an empty queue rather than throwing', () => {
    window.localStorage.setItem('vernfy.outbox.transactions', 'not json{{{')

    // A corrupt value must not take the page down with it.
    expect(queued()).toEqual([])
    expect(() => remove('anything')).not.toThrow()
  })
})
