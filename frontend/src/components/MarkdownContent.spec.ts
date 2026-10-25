import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const messageMocks = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn()
}))

vi.mock('element-plus', () => ({
  ElMessage: messageMocks
}))

import MarkdownContent from './MarkdownContent.vue'

describe('MarkdownContent', () => {
  beforeEach(() => {
    messageMocks.success.mockReset()
    messageMocks.error.mockReset()
  })

  it('renders markdown structures and sanitizes active content', () => {
    const wrapper = mount(MarkdownContent, {
      props: {
        content: [
          '**加粗文本**',
          '',
          '| 列A | 列B |',
          '| --- | --- |',
          '| 1 | 2 |',
          '',
          '```js',
          'const answer = 42',
          '```',
          '',
          '<img src="x" onerror="window.__xss = true">',
          '<script>window.__xss = true</script>',
          '[危险链接](javascript:window.__xss=true)'
        ].join('\n')
      }
    })

    expect(wrapper.find('strong').text()).toBe('加粗文本')
    expect(wrapper.find('table').exists()).toBe(true)
    expect(wrapper.find('.copy-code').exists()).toBe(true)
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('img').attributes('onerror')).toBeUndefined()
    const link = wrapper.find('a')
    if (link.exists()) expect(link.attributes('href') ?? '').not.toContain('javascript:')
  })

  it('copies fenced code and reports success', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText }
    })
    const wrapper = mount(MarkdownContent, {
      props: { content: '```js\nconst answer = 42\n```' }
    })

    await wrapper.find('.copy-code').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText.mock.calls[0][0]).toContain('const answer = 42')
    expect(messageMocks.success).toHaveBeenCalledTimes(1)
  })
})
