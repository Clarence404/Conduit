/** 设备连接参数与测点数值的格式化。 */

/** 设备接入 MQTT 所需的连接参数，用于设备详情页展示。 */
export interface ConnectionInfo {
  host: string
  port: number
  clientId: string
  username: string
  topic: string
  payloadExample: string
}

/**
 * 生成设备的 MQTT 连接参数与上报示例。
 * clientId 与 username 都是设备 Key，密码是设备密钥（不在此处出现）；
 * 上报 topic 为 conduit/{productKey}/{deviceKey}/up/property，payload 为 {ts, params}。
 * @param host MQTT 地址（页面用当前访问的主机名）
 * @param port MQTT 端口，默认 1883
 */
export function buildConnectionInfo(productKey: string, deviceKey: string, host: string, port = 1883): ConnectionInfo {
  return {
    host,
    port,
    clientId: deviceKey,
    username: deviceKey,
    topic: `conduit/${productKey}/${deviceKey}/up/property`,
    payloadExample: JSON.stringify({ ts: 1760400000000, params: { Ua: 220.1, Ia: 5.2 } }, null, 2),
  }
}

/** 无物模型信息时的数值显示：整数原样，小数最多保留 3 位（去掉多余的 0），布尔与文本转字符串。 */
export function formatValue(value: number | string | boolean): string {
  if (typeof value === 'number') {
    return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(3)))
  }
  return String(value)
}
