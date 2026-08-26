import { describe, expect, it } from 'vitest'
import type { ManagedUser } from './api'
import {
  filterAndSortUsers,
  isCurrentAccount,
  roleLabel,
  validateCreateUser,
  validateUpdateUser,
} from './model'

function user(overrides: Partial<ManagedUser> = {}): ManagedUser {
  return {
    id: 1,
    username: 'admin',
    email: 'admin@example.com',
    role: 'ADMIN',
    active: true,
    failedLoginAttempts: 0,
    ...overrides,
  }
}

describe('user management model', () => {
  it('validates and trims a create request while preserving the password', () => {
    const result = validateCreateUser({
      username: '  operator  ',
      password: ' secret value ',
      email: '  operator@example.com ',
      role: 'USER',
    })

    expect(result).toEqual({
      success: true,
      data: {
        username: 'operator',
        password: ' secret value ',
        email: 'operator@example.com',
        role: 'USER',
      },
      errors: {},
    })
  })

  it('reports field errors for invalid create values', () => {
    const result = validateCreateUser({ username: '', password: '', email: 'invalid', role: 'VIEWER' })
    expect(result.success).toBe(false)
    expect(result.errors).toMatchObject({
      username: '사용자명을 입력하세요.',
      password: '비밀번호를 입력하세요.',
      email: '올바른 이메일 주소를 입력하세요.',
    })
  })

  it('validates update fields without requiring a password', () => {
    expect(validateUpdateUser({ username: 'viewer', email: 'viewer@example.com', role: 'VIEWER' })).toMatchObject({
      success: true,
      data: { username: 'viewer', email: 'viewer@example.com', role: 'VIEWER' },
    })
  })

  it('searches username, email and Korean role labels, then sorts naturally', () => {
    const users = [
      user({ id: 10, username: 'user10', email: 'ten@example.com', role: 'USER' }),
      user({ id: 2, username: 'user2', email: 'two@example.com', role: 'VIEWER' }),
      user({ id: 3, username: 'root', email: 'root@example.com', role: 'ADMIN' }),
    ]
    expect(filterAndSortUsers(users, '').map((item) => item.username)).toEqual(['root', 'user2', 'user10'])
    expect(filterAndSortUsers(users, '조회 전용').map((item) => item.username)).toEqual(['user2'])
    expect(filterAndSortUsers(users, 'TEN@').map((item) => item.username)).toEqual(['user10'])
  })

  it('uses explicit labels and detects the signed-in account case-insensitively', () => {
    expect(roleLabel('ADMIN')).toBe('관리자 (ADMIN)')
    expect(roleLabel('UNKNOWN')).toBe('알 수 없음 (UNKNOWN)')
    expect(isCurrentAccount(user({ username: 'Admin' }), 'admin')).toBe(true)
    expect(isCurrentAccount(user({ username: 'operator' }), 'admin')).toBe(false)
  })
})
