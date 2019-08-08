package com.newegg.ec.redis.client;

import com.newegg.ec.redis.entity.*;
import com.newegg.ec.redis.util.RedisUtil;
import com.newegg.ec.redis.util.SplitUtil;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.*;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.*;

import static com.newegg.ec.redis.client.RedisURI.MAX_ATTEMPTS;
import static com.newegg.ec.redis.client.RedisURI.TIMEOUT;
import static com.newegg.ec.redis.util.RedisUtil.removeCommandAndKey;

/**
 * @author Jay.H.Zou
 * @date 2019/7/22
 */
public class RedisClusterClient implements IRedisClusterClient {

    private JedisCluster jedisCluster;

    private RedisClient redisClient;

    public RedisClusterClient(RedisURI redisURI) {
        Set<HostAndPort> hostAndPortSet = redisURI.getHostAndPortSet();
        String redisPassword = redisURI.getRequirePass();
        jedisCluster = new JedisCluster(hostAndPortSet, TIMEOUT, TIMEOUT, MAX_ATTEMPTS, redisPassword, new GenericObjectPoolConfig());
        redisClient = RedisClientFactory.buildRedisClient(redisURI);
    }

    @Override
    public JedisCluster getRedisClusterClient() {
        return jedisCluster;
    }

    /**
     * <id> <ip:port> <flags> <master> <ping-sent> <pong-recv> <config-epoch> <link-state> <slot> ... <slot>
     *
     * @return
     * @throws Exception
     */
    @Override
    public List<RedisNode> clusterNodes() throws Exception {
        Jedis jedis = redisClient.getJedisClient();
        String nodes = jedis.clusterNodes();
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(nodes.getBytes(Charset.forName("utf8"))), Charset.forName("utf8")));
        String line;
        List<RedisNode> redisNodeList = new ArrayList<>();
        while ((line = bufferedReader.readLine()) != null) {
            String[] item = SplitUtil.splitBySpace(line);
            String nodeId = item[0].trim();
            String ipPort = item[1];
            Set<HostAndPort> hostAndPortSet = RedisUtil.nodesToHostAndPortSet(SplitUtil.splitByAite(ipPort)[0]);
            HostAndPort hostAndPort = hostAndPortSet.iterator().next();
            String flags = item[2];
            String masterId = item[3];
            String linkState = item[7];
            RedisNode redisNode = new RedisNode(nodeId, hostAndPort.getHost(), hostAndPort.getPort(), null);
            redisNode.setFlags(flags);
            redisNode.setMasterId(masterId);
            redisNode.setLinkState(linkState);
            if (item.length > 8) {
                String slotRang = item[8];
                redisNode.setSlotRange(slotRang);
            }
            if (flags.contains(NodeRole.MASTER.getValue())) {
                redisNode.setNodeRole(NodeRole.MASTER);
            } else if (flags.contains(NodeRole.SLAVE.getValue())) {
                redisNode.setNodeRole(NodeRole.SLAVE);
            } else {
                redisNode.setNodeRole(NodeRole.UNKNOWN);
            }
            redisNodeList.add(redisNode);
        }
        return redisNodeList;
    }

    @Override
    public boolean exists(String key) {
        return jedisCluster.exists(key);
    }

    @Override
    public String type(String key) {
        return jedisCluster.type(key);
    }

    @Override
    public long ttl(String key) {
        return jedisCluster.ttl(key);
    }

    @Override
    public Long del(String key) {
        return jedisCluster.del(key);
    }

    @Override
    public AutoCommandResult query(AutoCommandParam autoCommandParam) {
        String key = autoCommandParam.getKey();
        int count = autoCommandParam.getCount();
        String type = type(key);
        long ttl = ttl(key);
        Object value = null;
        switch (type) {
            case TYPE_STRING:
                value = jedisCluster.get(key);
                break;
            case TYPE_HASH:
                value = jedisCluster.hgetAll(key);
                break;
            case TYPE_LIST:
                value = jedisCluster.lrange(key, 0, count);
                break;
            case TYPE_SET:
                value = jedisCluster.srandmember(key, count);
                break;
            case TYPE_ZSET:
                value = jedisCluster.zrangeWithScores(key, 0, count);
                break;
            default:
                break;
        }
        return new AutoCommandResult(ttl, type, value);
    }

    @Override
    public AutoCommandResult scan(AutoCommandParam autoCommandParam) {
        ScanParams scanParams = autoCommandParam.buildScanParams();
        ScanResult<String> scanResult = jedisCluster.scan(autoCommandParam.getCursor(), scanParams);
        return new AutoCommandResult(scanResult);
    }

    @Override
    public Object string(DataCommandsParam dataCommandsParam) {
        String command = dataCommandsParam.getCommand();
        String[] list = SplitUtil.splitBySpace(command);
        String cmd = command.toUpperCase();
        String key = list[1];
        Object result = null;
        if (cmd.startsWith(GET)) {
            result = jedisCluster.get(key);
        } else if (cmd.startsWith(SET)) {
            result = jedisCluster.set(key, list[2]);
        }
        return result;
    }

    @Override
    public Object hash(DataCommandsParam dataCommandsParam) {
        AutoCommandResult autoCommandResult = new AutoCommandResult();
        String command = dataCommandsParam.getCommand();
        String[] list = SplitUtil.splitBySpace(command);
        String cmd = command.toUpperCase();
        String key = list[1];
        Object result = null;
        if (cmd.startsWith(HGETALL)) {
            result = jedisCluster.hgetAll(key);
        } else if (cmd.startsWith(HGET)) {
            result = jedisCluster.hget(key, list[2]);
        } else if (cmd.startsWith(HMGET)) {
            String[] items = removeCommandAndKey(list);
            result = jedisCluster.hmget(key, items);
        } else if (cmd.startsWith(HKEYS)) {
            result = jedisCluster.hkeys(key);
        } else if (cmd.startsWith(HSET)) {
            Map<String, String> hash = new HashMap<>();
            String[] items = removeCommandAndKey(list);
            for (int i = 0; i < items.length; i += 2) {
                hash.put(items[i], items[i + 1]);
            }
            result = jedisCluster.hset(key, hash);
        }
        autoCommandResult.setValue(result);
        return autoCommandResult;
    }

    @Override
    public Object list(DataCommandsParam dataCommandsParam) {
        String command = dataCommandsParam.getCommand();
        String[] list = SplitUtil.splitBySpace(command);
        String cmd = command.toUpperCase();
        String key = list[1];
        String[] items = removeCommandAndKey(list);
        Object result = null;
        if (cmd.startsWith(LPUSH)) {
            result = jedisCluster.lpush(key, items);
        } else if (cmd.startsWith(RPUSH)) {
            result = jedisCluster.rpush(key, items);
        } else if (cmd.startsWith(LINDEX)) {
            result = jedisCluster.lindex(key, Integer.valueOf(list[2]));
        } else if (cmd.startsWith(LLEN)) {
            result = jedisCluster.llen(key);
        } else if (cmd.startsWith(LRANGE)) {
            int start = Integer.valueOf(list[2]);
            int stop = Integer.valueOf(list[3]);
            result = jedisCluster.lrange(key, start, stop);
        }
        return result;
    }

    @Override
    public Object set(DataCommandsParam dataCommandsParam) {
        String command = dataCommandsParam.getCommand();
        String[] list = SplitUtil.splitBySpace(command);
        String cmd = command.toUpperCase();
        String key = list[1];
        Object result = null;
        if (cmd.startsWith(SCARD)) {
            result = jedisCluster.scard(key);
        } else if (cmd.startsWith(SADD)) {
            result = jedisCluster.sadd(key, removeCommandAndKey(list));
        } else if (cmd.startsWith(SMEMBERS)) {
            result = jedisCluster.smembers(key);
        } else if (cmd.startsWith(SRANDMEMBER)) {
            int count = 1;
            if (list.length > 2) {
                count = Integer.valueOf(list[2]);
            }
            result = jedisCluster.srandmember(key, count);
        }
        return result;
    }

    @Override
    public Object zset(DataCommandsParam dataCommandsParam) {
        String command = dataCommandsParam.getCommand();
        String[] list = SplitUtil.splitBySpace(command);
        String cmd = command.toUpperCase();
        String key = list[1];
        String param1 = list[2];
        String param2 = list[3];
        Object result = null;
        if (cmd.startsWith(ZCARD)) {
            result = jedisCluster.zcard(key);
        } else if (cmd.startsWith(ZSCORE)) {
            result = jedisCluster.zscore(key, param1);
        } else if (cmd.startsWith(ZCOUNT)) {
            result = jedisCluster.zcount(key, param1, param2);
        } else if (cmd.startsWith(ZRANGE)) {
            int start = Integer.valueOf(param1);
            int stop = Integer.valueOf(param2);
            if (list.length > 4) {
                result = jedisCluster.zrangeWithScores(key, start, stop);
            } else {
                result = jedisCluster.zrange(key, start, stop);
            }
        } else if (cmd.startsWith(ZADD)) {
            result = jedisCluster.zadd(key, Double.valueOf(param1), param2);
        }
        return result;
    }
    @Override
    public void close() {
        if (redisClient != null) {
            redisClient.close();
        }
        if (jedisCluster != null) {
            jedisCluster.close();
        }
    }

}