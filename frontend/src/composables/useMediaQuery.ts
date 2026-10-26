import { onUnmounted, ref } from 'vue'

/**
 * 订阅一个 CSS 媒体查询并返回响应式布尔值。
 *
 * 首屏直接读取真实匹配结果，避免移动端先按桌面布局渲染一帧再跳变；
 * jsdom（单测环境）没有 matchMedia，此时恒为 false。
 */
export function useMediaQuery(query: string) {
  const supported = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  const mql = supported ? window.matchMedia(query) : null
  const matches = ref(mql ? mql.matches : false)

  const onChange = (e: MediaQueryListEvent) => {
    matches.value = e.matches
  }
  mql?.addEventListener('change', onChange)
  onUnmounted(() => mql?.removeEventListener('change', onChange))

  return matches
}

/** 移动端断点，与 styles/index.css 里的 @media (max-width: 768px) 保持一致 */
export function useIsMobile() {
  return useMediaQuery('(max-width: 768px)')
}
