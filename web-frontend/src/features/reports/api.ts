import { api } from '../../api/client'
import { pdfQuery, reportQuery } from './model'
import type { ReportFilters } from './model'

export function getMonthlyReports() {
  return api.get<unknown>('/reports/monthly')
}

export function getTrafficReport(filters: ReportFilters) {
  return api.get<unknown>(`/reports/traffic?${reportQuery(filters)}`)
}

export function getUptimeReport(filters: ReportFilters) {
  return api.get<unknown>(`/reports/uptime?${reportQuery(filters)}`)
}

export function getPerformanceStatus() {
  return api.get<unknown>('/status/performance')
}

export function regenerateMonthlyReport(yearMonth: string) {
  return api.post<unknown>(`/reports/monthly/${encodeURIComponent(yearMonth)}/regenerate`)
}

export function downloadReportPdf(filters: ReportFilters, type: 'WEEKLY' | 'MONTHLY') {
  const fallbackName = `classroom-report-${filters.fromDate}-${filters.toDate}.pdf`
  return api.download(`/reports/pdf?${pdfQuery(filters, type)}`, fallbackName)
}
