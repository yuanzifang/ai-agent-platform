import http from 'k6/http'
import { check } from 'k6'

const VUS = Number(__ENV.VUS || 50)
const DURATION = __ENV.DURATION || '30s'
const URL = __ENV.GATEWAY_URL
const KEY = __ENV.GATEWAY_KEY

export const options = {
  scenarios: {
    gateway: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '10s'
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500']
  }
}

export default function () {
  const res = http.get(URL, { headers: { 'X-Gateway-Key': KEY } })
  check(res, { 'status 200': r => r.status === 200 })
}
