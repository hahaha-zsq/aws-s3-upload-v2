/** upload.mjs 的 TypeScript 声明文件，提供类型提示，不包含运行时实现。 */
/** 每个上传分片的字节数，固定为 8 MiB。 */
export const CHUNK_SIZE: number;
/** 优先读取浏览器 MIME，常见扩展名兜底；未知类型仍允许上传。 */
export function getContentType(file: {name?: string; type?: string}): string;
/** 浏览器内置预览类型；不支持的格式显示下载提示。 */
export function getPreviewKind(file: {name?: string; type?: string}): 'video' | 'audio' | 'image' | 'pdf' | 'unsupported';
/** 计算至少为 1 的总片数，size 的单位为字节。 */
export function chunkCount(size: number): number;
/** 从 1..total 中筛选服务端尚未确认上传的片号。 */
export function missingParts(total: number, uploaded: number[]): number[];
/** 生成本地队列 ID，与文件 MD5、RustFS uploadId 无关。 */
export function createUploadId(): string;
