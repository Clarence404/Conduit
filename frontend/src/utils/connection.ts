export interface ConnectionInfo {
  host: string
  port: number
  clientId: string
  username: string
  topic: string
  payloadExample: string
}

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

export function formatValue(value: number | string | boolean): string {
  if (typeof value === 'number') {
    return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(3)))
  }
  return String(value)
}
