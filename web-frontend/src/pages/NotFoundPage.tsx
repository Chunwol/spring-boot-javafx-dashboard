import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return <main className="not-found"><h1>페이지를 찾을 수 없습니다.</h1><Link to="/classrooms">강의실 현황으로 이동</Link></main>
}
