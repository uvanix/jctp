import { ref, watch, type Ref } from 'vue'

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 读取 JSON；不存在或内容损坏时返回缺省值。对象与缺省值合并，新增字段自动取缺省 */
function read<T>(key: string, defaults: T): T {
  try {
    const raw = localStorage.getItem(key)
    if (raw === null) return defaults
    const value: unknown = JSON.parse(raw)
    if (isPlainObject(defaults)) return isPlainObject(value) ? ({ ...defaults, ...value } as T) : defaults
    return value as T
  } catch {
    return defaults
  }
}

/** 与 localStorage 同步的 ref（JSON 存储）；置为 null 时删除该项 */
export function useStorage<T>(key: string, defaults: T): Ref<T> {
  const value = ref(read(key, defaults)) as Ref<T>
  watch(
    value,
    (v) => {
      if (v === null) localStorage.removeItem(key)
      else localStorage.setItem(key, JSON.stringify(v))
    },
    { deep: true },
  )
  return value
}
