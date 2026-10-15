-- KEYS[1] = conduit:presence:{deviceKey}
-- ARGV[1] = online(true/false), ARGV[2] = 事件时间（毫秒）, ARGV[3] = clientIp
-- 上线事件的 clientIp 为空（如由上报数据推断的在线）时保留原有 IP；下线事件按传入值写入。
-- 只接受不早于当前记录的事件，防止快速断连重连时旧事件后到覆盖新状态。
local current = redis.call('HGET', KEYS[1], 'since')
if current and tonumber(current) > tonumber(ARGV[2]) then
  return 0
end
if ARGV[1] == 'true' and ARGV[3] == '' then
  redis.call('HSET', KEYS[1], 'online', ARGV[1], 'since', ARGV[2])
  return 1
end
redis.call('HSET', KEYS[1], 'online', ARGV[1], 'since', ARGV[2], 'clientIp', ARGV[3])
return 1
