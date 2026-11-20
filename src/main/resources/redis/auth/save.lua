local kind = redis.call('TYPE', KEYS[2]).ok
if kind ~= 'none' and kind ~= 'set' then
    return redis.error_reply('Invalid authentication index type')
end
redis.call('SADD', KEYS[2], ARGV[2])
redis.call('PEXPIRE', KEYS[2], ARGV[3])
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
return 1
