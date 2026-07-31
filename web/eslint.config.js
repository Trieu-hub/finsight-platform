import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // These React-Compiler-era rules (eslint-plugin-react-hooks v7 / react-refresh) are new and
      // opinionated, and they fire on existing, working production code. We keep them ON as
      // **warnings** — visible in the lint output and CI logs — rather than errors, so CI enforces
      // genuine problems (syntax, unused vars, prefer-const, hooks-rules violations) without a
      // brand-new advisory rule forcing a risky refactor of a live app as a merge precondition.
      // Revisit when the frontend gets a data-layer pass (e.g. moving fetch-on-mount to a query lib).
      //   - set-state-in-effect: the ubiquitous `useEffect(() => { load() }, [])` fetch-on-mount.
      //   - immutability: a local running-total accumulated during render.
      //   - only-export-components: files that export a provider/hook alongside components (breaks
      //     Vite fast-refresh only — not a correctness issue).
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/immutability': 'warn',
      'react-refresh/only-export-components': 'warn',
    },
  },
])
