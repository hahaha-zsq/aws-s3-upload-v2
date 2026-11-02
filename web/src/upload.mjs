/** 上传单片固定为 8 MiB，必须与后端 UploadRules.CHUNK_SIZE 保持一致。 */
export const CHUNK_SIZE = 8 * 1024 * 1024;

/**
 * 前端整理类型元数据：优先使用浏览器 File.type，空值时补充常见格式映射。
 * 没有类型白名单，任何格式均可上传；未知扩展名使用通用二进制类型。
 * 这属于 MIME 提示，不是文件内容真实性检查。
 */
export function getContentType(file) {
    const type = file.type?.trim();
    if (type && /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/i.test(type)) return type;
    const extension = file.name?.split('.').pop()?.toLowerCase();
    const types = {mp4:'video/mp4',m4v:'video/mp4',mov:'video/quicktime',webm:'video/webm',
        mkv:'video/x-matroska',avi:'video/x-msvideo',pdf:'application/pdf',png:'image/png',
        jpg:'image/jpeg',jpeg:'image/jpeg',gif:'image/gif',webp:'image/webp',mp3:'audio/mpeg',
        wav:'audio/wav',ogg:'audio/ogg',flac:'audio/flac',m4a:'audio/mp4',aac:'audio/aac',
        svg:'image/svg+xml',avif:'image/avif',bmp:'image/bmp',ico:'image/x-icon',
        zip:'application/zip',txt:'text/plain'};
    return Object.prototype.hasOwnProperty.call(types, extension) ? types[extension] : 'application/octet-stream';
}

/** 仅选择浏览器内置预览组件；未知文件仍保留上传和下载能力。 */
export function getPreviewKind(file) {
    const type = getContentType(file);
    for (const kind of ['video', 'audio', 'image']) if (type.startsWith(kind + '/')) return kind;
    return type === 'application/pdf' ? 'pdf' : 'unsupported';
}

/** 根据文件字节数向上取整；空文件约定为一片，实际由后端普通上传创建零字节对象。 */
export function chunkCount(size) {
    return Math.max(1, Math.ceil(size / CHUNK_SIZE));
}

/**
 * 返回需要上传的片号，所有编号从 1 开始。
 * uploaded 来自 RustFS 的实际分片列表，而非页面缓存；Set 避免重复片号干扰筛选。
 * 例如 total=4、uploaded=[1,3] 时，结果为 [2,4]。
 */
export function missingParts(total, uploaded) {
    const done = new Set(uploaded);
    return Array.from({length: total}, (_, i) => i + 1).filter(n => !done.has(n));
}

// 模块内递增序号配合时间戳，区分同一毫秒加入的多个页面队列项。
let uploadIdSequence = 0;

/**
 * 创建页面内使用的 ID，不是 RustFS uploadId，也不承担安全令牌职责。
 * 不调用 crypto.randomUUID，兼容该方法不可用的浏览器环境。
 */
export function createUploadId() {
    return `upload-${Date.now().toString(36)}-${++uploadIdSequence}`;
}
