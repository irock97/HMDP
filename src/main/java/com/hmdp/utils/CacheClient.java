package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class CacheClient {


    private final StringRedisTemplate stringRedisTemplate;

    private final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long expireTime, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(value), expireTime, timeUnit);
    }


    public void setWithLogicalExpire(String key, Object value, Long expireTime, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expireTime)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix,
                                         ID id,
                                         Class<R> type,
                                         Long expireTime,
                                         TimeUnit timeUnit,
                                         Function<ID, R> dbFallBack) {
        String cacheKey = keyPrefix + id;
        // 1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(cacheKey);

        // 2. 查询存在直接返回
        if (StringUtils.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        if (json != null){
            return null;
        }

        // 3.查询不存在，查db
        R r = dbFallBack.apply(id);
        //4，db不存在
        if (r == null) {
            //将空值写回redis
            stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 5. db存在写入redis返回
        this.set(cacheKey, r, expireTime, timeUnit);
        return r;
    }




    public <R,ID> R queryWithLogicalExpire(String keyPrefix,
                                       String lockKeyPrefix,
                                       ID id,
                                       Class<R> type,
                                       Long expireTimeForR,
                                       Long expireTimeForLock,
                                       TimeUnit timeUnitForR,
                                       TimeUnit timeUnitForLock,
                                       Function<ID, R> dbFallBack) {

        String cacheKey = keyPrefix + id;
        String lockKey = lockKeyPrefix + id;
        // 1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(cacheKey);

        // 2. 查询不存在直接返回
        if (StringUtils.isBlank(json)) {
            return null;
        }

        // 3.查询存在，检查缓存逻辑过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 没过期
            return r;
        }
        // 过期，重建缓存
        //获取互斥锁
        boolean isLock = tryLock(lockKey,expireTimeForLock,timeUnitForLock);
        if (isLock) {
            // double check是否有别的线程更新缓存
            String doubleCheckShopJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StringUtils.isNotBlank(doubleCheckShopJson)) {
                RedisData data = JSONUtil.toBean(doubleCheckShopJson, RedisData.class);
                if (data.getExpireTime().isAfter(LocalDateTime.now())) {
                    try {
                        return JSONUtil.toBean((JSONObject) data.getData(), type);
                    } finally {
                        unlock(lockKey);
                    }
                }
            }
            // 开启独立线程，实现缓存重建和释放锁
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                   //查询数据库
                    R dbRes = dbFallBack.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(cacheKey,dbRes, expireTimeForR, timeUnitForR);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }

            });

        }
        // 返回过期商铺信息
        return r;
    }



    private boolean tryLock(String lockKey, Long expireTime, TimeUnit timeUnit) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", expireTime, timeUnit);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }



}


