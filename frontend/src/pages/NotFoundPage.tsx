import { Link } from 'react-router'
import { Empty } from '../components/ui'

export function NotFoundPage() {
  return (
    <Empty>
      <p className="mb-3 font-medium text-slate-800">Page not found</p>
      <Link to="/monitors" className="font-medium text-emerald-700 hover:underline">
        Back to monitors
      </Link>
    </Empty>
  )
}
