import { useEffect } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { LockKeyhole, UserRound } from 'lucide-react'
import { z } from 'zod'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthProvider'
import { Spinner } from '../components/ui/Spinner'

const loginSchema = z.object({
  username: z.string().trim().min(1, '아이디를 입력해 주세요.').max(50),
  password: z.string().min(1, '비밀번호를 입력해 주세요.'),
  rememberUsername: z.boolean(),
  autoLogin: z.boolean(),
})

type LoginForm = z.infer<typeof loginSchema>

export function LoginPage() {
  const { isAuthenticated, isRestoring, login, rememberedUsername } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const { register, handleSubmit, setError, setFocus, formState: { errors, isSubmitting } } = useForm<LoginForm>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      username: rememberedUsername,
      password: '',
      rememberUsername: Boolean(rememberedUsername),
      autoLogin: true,
    },
  })

  useEffect(() => { setFocus(rememberedUsername ? 'password' : 'username') }, [rememberedUsername, setFocus])

  if (isRestoring) return <main className="login-page"><Spinner label="자동 로그인 확인 중" /></main>
  if (isAuthenticated) return <Navigate to="/classrooms" replace />

  const onSubmit = handleSubmit(async (values) => {
    try {
      await login(values.username, values.password, values.rememberUsername, values.autoLogin)
      const from = (location.state as { from?: string } | null)?.from || '/classrooms'
      navigate(from, { replace: true })
    } catch (error) {
      setError('root', { message: error instanceof ApiError ? error.message : '로그인하지 못했습니다.' })
    }
  })

  return (
    <main className="login-page">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-brand">
          <span className="brand-mark" aria-hidden="true">DC</span>
          <div>
            <h1 id="login-title">통합 강의실 관제</h1>
            <p>관리자 로그인</p>
          </div>
        </div>
        <form onSubmit={onSubmit} noValidate>
          <label className="field-label" htmlFor="username">아이디</label>
          <div className="input-with-icon">
            <UserRound size={18} aria-hidden="true" />
            <input id="username" autoComplete="username" {...register('username')} />
          </div>
          {errors.username && <p className="field-error">{errors.username.message}</p>}

          <label className="field-label" htmlFor="password">비밀번호</label>
          <div className="input-with-icon">
            <LockKeyhole size={18} aria-hidden="true" />
            <input id="password" type="password" autoComplete="current-password" {...register('password')} />
          </div>
          {errors.password && <p className="field-error">{errors.password.message}</p>}

          <div className="login-options">
            <label className="check-field">
              <input type="checkbox" {...register('rememberUsername')} />
              <span>아이디 기억</span>
            </label>
            <label className="check-field">
              <input type="checkbox" {...register('autoLogin')} />
              <span>자동 로그인</span>
            </label>
          </div>
          {errors.root && <div className="form-error" role="alert">{errors.root.message}</div>}
          <button className="button primary login-submit" type="submit" disabled={isSubmitting}>
            {isSubmitting ? <><Spinner label="로그인 중" /> 로그인 중</> : '로그인'}
          </button>
        </form>
      </section>
    </main>
  )
}
