# HMDP

## 本地运行

应用默认连接本机的 MySQL `3306` 和 Redis `6379`。先导入 `src/main/resources/db/hmdp.sql` 到 `hmdp` 数据库。

MySQL 密码可以通过 `MYSQL_PASSWORD` 环境变量提供。也可以在 `src/main/resources/application-local.yaml` 中配置本机密码；该文件已被 Git 忽略，例如：

```yaml
spring:
  datasource:
    password: "你的本机密码"
```
