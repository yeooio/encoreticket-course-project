local digests = redis.call('SMEMBERS', KEYS[1])
for _, digest in ipairs(digests) do
    redis.call('DEL', ARGV[1] .. digest)
end
redis.call('DEL', KEYS[1])
return 1
