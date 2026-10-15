-- KEYS[1] = conduit:presence:{deviceKey}（hash：online / since / clientIp / source / lastSeen）
-- ARGV[1] = online(true/false), ARGV[2] = 时间（毫秒）, ARGV[3] = clientIp,
-- ARGV[4] = 来源 'event'（EMQX 上下线事件，时间为 EMQX 时钟）或 'data'（设备上报数据，时间为 transport 接收时间）,
-- ARGV[5] = 数据复活宽限期（毫秒）
--
-- since 是唯一的乱序保护，只由事件推进；数据永远不推进已有记录的 since：
--   * data，无记录          → 置在线，since = ts，source = data
--   * data，已在线          → 不改状态，只刷新 lastSeen
--   * data，已离线          → 仅当 ts > since + 宽限期 才置在线并令 since = ts（宽限期内的在途数据不复活设备）
--   * event                 → 仅当 ts >= since 才生效；例外：当前记录 source = data 时 since 来自另一时钟，不可信，事件总是生效。
--                             上线事件 clientIp 为空时保留原有 IP；下线事件按传入值写入。
-- 返回 1 表示改变了状态，0 表示被忽略或无变化。
local rec = redis.call('HMGET', KEYS[1], 'since', 'online', 'source', 'lastSeen')
local curSince, curOnline, curSource, curLastSeen = rec[1], rec[2], rec[3], rec[4]
local ts = tonumber(ARGV[2])

if ARGV[4] == 'data' then
  if not curSince then
    redis.call('HSET', KEYS[1], 'online', 'true', 'since', ARGV[2], 'clientIp', '', 'source', 'data', 'lastSeen', ARGV[2])
    return 1
  end
  if curOnline == 'true' then
    if not curLastSeen or tonumber(curLastSeen) < ts then
      redis.call('HSET', KEYS[1], 'lastSeen', ARGV[2])
    end
    return 0
  end
  if ts > tonumber(curSince) + tonumber(ARGV[5]) then
    redis.call('HSET', KEYS[1], 'online', 'true', 'since', ARGV[2], 'source', 'data', 'lastSeen', ARGV[2])
    return 1
  end
  return 0
end

if curSince and curSource ~= 'data' and tonumber(curSince) > ts then
  return 0
end
if ARGV[1] == 'true' and ARGV[3] == '' then
  redis.call('HSET', KEYS[1], 'online', ARGV[1], 'since', ARGV[2], 'source', 'event')
  return 1
end
redis.call('HSET', KEYS[1], 'online', ARGV[1], 'since', ARGV[2], 'clientIp', ARGV[3], 'source', 'event')
return 1
