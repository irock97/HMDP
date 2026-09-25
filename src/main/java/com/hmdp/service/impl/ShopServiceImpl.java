package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryById(Long id) {

        // 缓存穿透
//        Shop shop = queryWithPassThrough(id);
        //用互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

        return Result.ok(shop);


    }


    // 旧版锁辅助方法，保留用于对照
    private boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }


    public Shop queryWithPassThrough(Long id) {
        // 1. 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        // 2. 查询存在直接返回
        if (StringUtils.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        if (shopJson != null){
            return null;
        }

        // 3.查询不存在，查db
        Shop shop = getById(id);
        //4，db不存在
        if (shop == null) {
            //将空值写回redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 5. db存在写入redis返回
        stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public Shop queryWithMutex(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        if (StringUtils.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        if (shopJson != null){
            return null;
        }
        Shop shop = null;
        // 缓存重建
        try {
            //1.获取互斥锁
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            //2.判断是否获取成功
            if (!isLock) {
                //3.失败则休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.成功，根据id查数据库
            // double check是否有别的线程更新了缓存，如果有则直接返回
            String doubleCheckShopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StringUtils.isNotBlank(doubleCheckShopJson)) {
                return JSONUtil.toBean(doubleCheckShopJson, Shop.class);
            }
            // 没有则查库
            shop = getById(id);
            if (shop == null) {
                //将空值写回redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //5.写回redis
            stringRedisTemplate.opsForValue().set(
                    CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e){
            throw new RuntimeException(e);
        } finally {
            //6. 释放锁
            unlock(LOCK_SHOP_KEY + id);
        }
        return shop;
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        if (!updateById(shop)) {
            return Result.fail("店铺不存在！");
        }
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
