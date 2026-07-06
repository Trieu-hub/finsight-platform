import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { login, register } from '../api/endpoints'
import { errorMessage } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n'
import { markAccountCreated } from '../lib/onboarding'
import { AuthShell, Field } from './Login'

export default function Register() {
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const { signIn } = useAuth()
  const { t } = useI18n()
  const navigate = useNavigate()

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await register(username, email, password)
      // Auto sign-in after a successful registration for a smoother demo flow.
      const res = await login(email, password)
      if (res.accessToken) signIn(res.accessToken, res.refreshToken)
      // Mark this as a freshly created account so the dashboard opens the guided tour.
      markAccountCreated()
      navigate('/')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthShell title={t('register.title')}>
      <form onSubmit={handleSubmit} className="space-y-4">
        <Field label={t('register.username')} type="text" value={username} onChange={setUsername} />
        <Field label={t('register.email')} type="email" value={email} onChange={setEmail} />
        <Field
          label={t('register.password')}
          type="password"
          value={password}
          onChange={setPassword}
        />
        {error && <p className="text-sm text-red-400">{error}</p>}
        <button
          type="submit"
          disabled={loading}
          className="w-full rounded-lg bg-emerald-600 py-2.5 font-semibold text-white shadow-lg shadow-emerald-900/40 transition hover:bg-emerald-500 disabled:opacity-60"
        >
          {loading ? t('register.submitting') : t('register.submit')}
        </button>
      </form>
      <p className="mt-5 text-center text-sm text-neutral-500">
        {t('register.haveAccount')}{' '}
        <Link to="/login" className="font-medium text-emerald-400 hover:text-emerald-300">
          {t('register.signIn')}
        </Link>
      </p>
    </AuthShell>
  )
}
