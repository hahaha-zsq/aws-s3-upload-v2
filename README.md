# Winter Transfer

独立的文件传输示例：Spring Boot 后端、React 前端、RustFS 对象存储、MyBatis-Plus 操作 MySQL、Redis 任务互斥。基于 `winter-minio-spring-boot-starter:0.0.4`（AWS SDK for Java 2.x）。参考旧项目的功能流程，代码与旧项目独立。

后端通过 `UploadTaskMapper`、`UploadedFileMapper` 的内置 CRUD 访问数据，文件列表使用 MyBatis-Plus 分页插件；实体和配置位于 `upload/persistence/`。迁移保留原表结构和接口字段，已有数据库无须重建。业务代码不再使用 `JdbcTemplate` 或手写数据库查询。

## 准备

需要 Java 17+、Maven、Node 20+、MySQL、Redis 和 RustFS。先在 MySQL 创建独立数据库，再执行本项目的 `src/main/resources/schema.sql`：

```sql
CREATE DATABASE winter_upload_v2 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

公共 `application.yml` 只保留占位符，密码和 S3 密钥没有真实默认值。可选择以下一种方式提供连接信息。

本地或 IDE 运行：复制 `application-local.properties.example` 为项目根目录的 `application-local.properties`，填入真实值。Spring Boot 会自动读取该文件；IDE 工作目录需设为项目根目录。该文件已被 Git 忽略，且不位于 `src/main/resources`，不会打包进 JAR。properties 值不需要引号。

终端或部署环境：通过环境变量注入，环境变量优先于本地私有配置。开发时可复制 `.env.example` 为 `.env` 并填写，再导出变量：

```bash
set -a
source .env
set +a
mvn spring-boot:run
```

`.env` 不会被 Spring Boot 自动读取，需要上述导出步骤；生产环境应由部署平台注入变量。GitHub 只提交 `.example` 模板，不提交真实私有文件。不要把后端密钥放进前端 `VITE_*` 变量。缺少必需的 MySQL 密码或 RustFS 密钥时，服务启动会报配置错误，避免误用内置凭据。

前端另开终端：

```bash
cd web
npm install
npm run dev
```

打开 `http://localhost:5173`。开发服务器代理 `/api` 到 `localhost:8080`。启动前需在 RustFS 中创建 `RUSTFS_BUCKET` 指定的私有桶；初始化接口直接发起分片上传，避免每个文件额外调用一次桶检查。运行账号需要分片上传、列举分片、完成/中止上传和读取对象的权限。

## 上传流程

完整的前后端时序、接口示例、状态流转和恢复边界见 [分片上传、断点续传与秒传交互逻辑](docs/前后端分片上传、断点续传与秒传交互逻辑.md)。

浏览器以 4 MiB 读取文件计算 MD5，调用 `check`。已完成时直接标记秒传；未完成时只上传 RustFS 尚未收到的 8 MiB 分片，最多三个并发。暂停会保留服务端任务；刷新页面后重选同一文件即可继续。取消会中止 RustFS 任务。下载链接由服务端生成，有效期 15 分钟。

API：`GET /api/uploads/check?md5=&size=`、`POST /api/uploads/init`、`PUT /api/uploads/{md5}/parts/{number}`、`POST /api/uploads/{md5}/complete`、`DELETE /api/uploads/{md5}`、`GET /api/uploads/files`、`GET /api/uploads/files/{md5}/download`。

已存文件支持删除：页面确认后调用 `DELETE /api/uploads/files/{md5}`，先删除 RustFS 对象，再清理数据库记录；失败时保留记录供重试。该操作与取消未完成上传不同，删除后再次选择同一文件会重新上传。运行账号需具有删除对象权限。

已存文件的“预览”按钮支持视频、音频、图片和 PDF 弹窗预览，提供加载状态、错误提示、新窗口打开和下载入口；关闭或按 Esc 会卸载播放器。不支持的格式仍可上传和下载。视频是否能够播放取决于浏览器的编码支持，PDF 预览需要浏览器内置阅读器。

MD5 是客户端提供的去重标识，服务端不重新计算完整对象的 MD5；此演示不包含身份认证。公网部署前需增加认证、授权和限流。

所有文件类型都允许上传，不设置扩展名或 MIME 白名单。前端优先使用浏览器 `File.type`，为空时补充常见扩展名映射，未知格式使用 `application/octet-stream`，通过 JSON 初始化字段 `contentType` 传给后端。后端不做内容类型检测，只规范化 HTTP 头格式并设置对象元数据。页面获取视频访问链接时，也由前端传递类型提示以兼容旧对象；不会改写历史对象自身的元数据，视频编码仍需浏览器支持。

## 验证

```bash
mvn test
cd web && npm test && npm run build
```
