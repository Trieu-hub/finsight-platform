/**
 * Turning a fetched file into a saved file. The export endpoint needs an Authorization header,
 * so the browser cannot simply follow a link to it — the body has to be fetched by the client
 * and handed to the user as a Blob.
 */

/** The [from, to] day range covering a 'YYYY-MM' month, or an empty range for "all months". */
export function monthRange(month: string): { fromDate?: string; toDate?: string } {
  if (!/^\d{4}-\d{2}$/.test(month)) return {}
  const year = Number(month.slice(0, 4))
  const monthIndex = Number(month.slice(5, 7))
  // Day 0 of the next month is the last day of this one, which is how February and the
  // 30-day months get their right length without a table.
  const lastDay = new Date(Date.UTC(year, monthIndex, 0)).getUTCDate()
  return { fromDate: `${month}-01`, toDate: `${month}-${String(lastDay).padStart(2, '0')}` }
}

/** Saves `blob` under `filename` through a temporary object URL. */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  // Revoking frees the blob; doing it synchronously after click() is safe because the
  // download has already been handed to the browser.
  URL.revokeObjectURL(url)
}
