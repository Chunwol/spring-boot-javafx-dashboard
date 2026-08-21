import type { ClassroomSummary } from '../../types/dashboard'

export interface NetworkTraffic {
  inboundMbps: number
  outboundMbps: number
}

export interface TrafficThresholds {
  globalMbps: number
  classroomMbps: number
  pcMbps: number
}

export interface PcTraffic {
  pcName: string
  ipAddress: string | null
  macAddress: string
  status: string | null
  inboundMbps: number
  outboundMbps: number
  updatedAt: string | null
}

export interface TrafficHistoryPoint extends NetworkTraffic {
  timestamp: number
  label: string
}

export interface ClassroomTrafficSummary {
  groupId: number
  groupName: string
  onlinePcs: number
  totalPcs: number
  inboundMbps: number | null
  outboundMbps: number | null
  updatedAt?: string | null
}

export type ClassroomPage = {
  content?: ClassroomSummary[]
  data?: ClassroomSummary[]
}
