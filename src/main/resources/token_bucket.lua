-- Arguments
local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1000000

local userCapacity = tonumber(ARGV[1])
local userRate = tonumber(ARGV[2])
local tenantCapacity = tonumber(ARGV[3])
local tenantRate = tonumber(ARGV[4])

-- Read user bucket (or initialize if missing)
local userTokens = tonumber(redis.call('HGET', KEYS[1], 'tokens')) or userCapacity
local userLast = tonumber(redis.call('HGET', KEYS[1], 'last_refill')) or now

-- Read tenant bucket (or initialize if missing)
local tenantTokens = tonumber(redis.call('HGET', KEYS[2], 'tokens')) or tenantCapacity
local tenantLast = tonumber(redis.call('HGET', KEYS[2], 'last_refill')) or now

-- Calculate refilled tokens
local userAvailable = math.min(userCapacity, userTokens + (now - userLast) * userRate)
local tenantAvailable = math.min(tenantCapacity, tenantTokens + (now - tenantLast) * tenantRate)

-- Both quotas must pass

if userAvailable < 1 or tenantAvailable < 1 then
	local userWait = 0
	if userAvailable < 1 then
		if userRate <= 0 then
			return 0
		end

		userWait = (1 - userAvailable) / userRate
	end

	local tenantWait = 0

	if tenantAvailable < 1 then
		if tenantRate <= 0 then
			return 0
		end

		tenantWait = (1 - tenantAvailable) / tenantRate
	end

	local retrySeconds = math.ceil(
		math.max(userWait, tenantWait)
	)

	return -math.max(1, retrySeconds)
end


-- Atomically consume one token from both buckets
redis.call('HSET', KEYS[1],'tokens', userAvailable - 1,'last_refill', now)
redis.call('HSET', KEYS[2],'tokens', tenantAvailable - 1,'last_refill', now)

return 1
