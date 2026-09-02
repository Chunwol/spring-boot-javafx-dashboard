import { z } from 'zod'
import type { CreateUserInput, ManagedUser, UpdateUserInput, UserRole } from './api'

export const ROLE_OPTIONS: ReadonlyArray<{ value: UserRole; label: string; description: string }> = [
  { value: 'ADMIN', label: '관리자 (ADMIN)', description: '모든 조회와 관리 작업을 수행할 수 있습니다.' },
  { value: 'USER', label: '운영자 (USER)', description: '허용된 PC 및 운영 설정을 관리할 수 있습니다.' },
  { value: 'VIEWER', label: '조회 전용 (VIEWER)', description: '현황과 보고서만 조회할 수 있습니다.' },
]

const roleSchema = z.enum(['ADMIN', 'USER', 'VIEWER'], {
  message: '역할을 선택하세요.',
})

const usernameSchema = z.string()
  .trim()
  .min(1, '사용자명을 입력하세요.')
  .max(50, '사용자명은 50자 이하로 입력하세요.')

const emailSchema = z.string()
  .trim()
  .min(1, '이메일을 입력하세요.')
  .max(200, '이메일은 200자 이하로 입력하세요.')
  .email('올바른 이메일 주소를 입력하세요.')

const noteSchema = z.string().trim().max(500, '비고는 500자 이하로 입력하세요.').optional()
const permissionsSchema = z.array(z.enum([
  'CLASSROOMS', 'PCS', 'COMMANDS', 'SCRIPT_LIBRARY', 'TOKENS', 'BROKEN_PCS',
  'NETWORK', 'REPORTS', 'FILES', 'CLASSROOM_SETTINGS', 'SYSTEM_SETTINGS',
]))

export const createUserSchema = z.object({
  username: usernameSchema,
  password: z.string()
    .min(1, '비밀번호를 입력하세요.')
    .min(8, '비밀번호는 8자 이상으로 입력하세요.')
    .max(100, '비밀번호는 100자 이하로 입력하세요.'),
  email: emailSchema,
  role: roleSchema,
  note: noteSchema,
  permissions: permissionsSchema.optional(),
})

export const updateUserSchema = z.object({
  username: usernameSchema,
  email: emailSchema,
  role: roleSchema,
  note: noteSchema,
  permissions: permissionsSchema.optional(),
})

export type UserFormErrors = Partial<Record<'username' | 'password' | 'email' | 'role' | 'note', string>>

export type UserValidationResult<T> =
  | { success: true; data: T; errors: UserFormErrors }
  | { success: false; data?: never; errors: UserFormErrors }

function toFormErrors(error: z.ZodError): UserFormErrors {
  return error.issues.reduce<UserFormErrors>((errors, issue) => {
    const field = issue.path[0]
    if (typeof field === 'string' && !(field in errors)) {
      errors[field as keyof UserFormErrors] = issue.message
    }
    return errors
  }, {})
}

export function validateCreateUser(input: CreateUserInput): UserValidationResult<CreateUserInput> {
  const result = createUserSchema.safeParse(input)
  return result.success
    ? { success: true, data: result.data, errors: {} }
    : { success: false, errors: toFormErrors(result.error) }
}

export function validateUpdateUser(input: UpdateUserInput): UserValidationResult<UpdateUserInput> {
  const result = updateUserSchema.safeParse(input)
  return result.success
    ? { success: true, data: result.data, errors: {} }
    : { success: false, errors: toFormErrors(result.error) }
}

export function roleLabel(role: string) {
  return ROLE_OPTIONS.find((option) => option.value === role)?.label || `알 수 없음 (${role || '-'})`
}

export function filterAndSortUsers(users: ManagedUser[], search: string) {
  const needle = search.trim().toLocaleLowerCase('ko-KR')
  const collator = new Intl.Collator('ko-KR', { numeric: true, sensitivity: 'base' })
  return users
    .filter((user) => !needle || [
      user.username, user.email, user.role, roleLabel(user.role), user.note, user.loginIpAddress,
    ]
      .filter((value): value is string => Boolean(value))
      .some((value) => value.toLocaleLowerCase('ko-KR').includes(needle)))
    .sort((left, right) => collator.compare(left.username, right.username))
}

export function isCurrentAccount(user: Pick<ManagedUser, 'username'>, currentUsername?: string | null) {
  return Boolean(currentUsername)
    && user.username.trim().toLocaleLowerCase('ko-KR') === currentUsername?.trim().toLocaleLowerCase('ko-KR')
}
