import { ShieldX } from 'lucide-react'
import { Link } from 'react-router-dom'

export function ForbiddenPage() {
  return <section className="content-section permission-page"><ShieldX size={34} /><h1>접근 권한이 없습니다.</h1><p>현재 계정으로 이 화면을 이용할 수 없습니다.</p><Link className="button primary" to="/classrooms">강의실 현황으로 이동</Link></section>
}
