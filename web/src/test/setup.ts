import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Vitest runs without globals, so Testing Library's automatic cleanup (which hooks itself onto a
// global afterEach) never registers. Unmount explicitly instead — otherwise every render stays in
// the document and the next test's queries match the previous test's DOM.
afterEach(cleanup)
