import { useNavigate } from 'react-router'
import { api } from '../api/client'
import type { Monitor } from '../api/types'
import { MonitorForm } from '../components/MonitorForm'
import { Card, PageTitle } from '../components/ui'

export function NewMonitorPage() {
  const navigate = useNavigate()
  return (
    <div className="max-w-2xl">
      <PageTitle>Add monitor</PageTitle>
      <Card>
        <MonitorForm
          submitLabel="Add monitor"
          onSubmit={async (input) => {
            const created = await api<Monitor>('/api/monitors', { method: 'POST', body: input })
            navigate(`/monitors/${created.id}`)
          }}
          onCancel={() => navigate('/monitors')}
        />
      </Card>
    </div>
  )
}
