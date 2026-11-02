import { defineConfig } from 'vite';
/**
 * 本地开发服务器监听 5173，将同源 /api 请求代理至 Spring Boot 的 8080 端口。
 * 该代理仅用于开发；生产构建部署后需由 Web 服务器配置相应的 /api 转发。
 */
export default defineConfig({server:{port:5173,proxy:{'/api':'http://localhost:8080'}}});
