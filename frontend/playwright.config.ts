import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL || 'http://localhost:5173'
const localFrontend = /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/)/.test(baseURL)

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    viewport: { width: 1440, height: 900 }
  },
  // 本地/CI 都自举离线模型、Ollama、渠道、MCP 与 OTLP collector；已有进程时直接复用。
  // 前端只在默认本地地址时自举，避免覆盖 E2E_BASE_URL 指向的远端环境。
  webServer: [
    {
      command: 'python3 e2e/mock_servers.py',
      url: 'http://127.0.0.1:18080/v1/models',
      reuseExistingServer: true,
      timeout: 30_000
    },
    ...(localFrontend ? [{
      command: 'npm run dev -- --host 127.0.0.1 --port 5173',
      url: baseURL,
      reuseExistingServer: true,
      timeout: 120_000
    }] : [])
  ],
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    // Edge 兼容（需求 5.1：Chrome/Edge 最新两个大版本）；CI edge job 用 --project=msedge 跑
    ...(process.env.E2E_BROWSER === 'msedge'
      ? [{ name: 'msedge', use: { ...devices['Desktop Edge'], channel: 'msedge' } }]
      : [])
  ]
})
