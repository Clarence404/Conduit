-- KEYS[1] = conduit:latest:{deviceKey}
-- ARGV[1] = 本次数据的 ts（毫秒）
-- ARGV[2..] = 测点名, {"v":...,"ts":...} 交替
-- 逐测点比较 ts：只有不早于当前值的数据才覆盖，补传的旧数据不会盖掉新值。
local ts = tonumber(ARGV[1])
local updated = 0
for i = 2, #ARGV, 2 do
  local current = redis.call('HGET', KEYS[1], ARGV[i])
  if (not current) or ts >= tonumber(cjson.decode(current)['ts']) then
    redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
    updated = updated + 1
  end
end
local last = redis.call('HGET', KEYS[1], '__last')
if (not last) or ts > tonumber(last) then
  redis.call('HSET', KEYS[1], '__last', ARGV[1])
end
return updated
