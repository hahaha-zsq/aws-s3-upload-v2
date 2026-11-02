import React from 'react';
import {createRoot} from 'react-dom/client';
import SparkMD5 from 'spark-md5';
import {CHUNK_SIZE, chunkCount, missingParts, createUploadId, getContentType} from './upload.mjs';
import './style.css';
import FilePreview from './FilePreview';

/**
 * 页面队列状态：等待、计算指纹、上传中、暂停、失败、完成、秒传完成、已取消。
 * uploading 同时覆盖检查任务、初始化、发送分片和等待合并，不单独设置合并状态。
 */
type Status = 'ready' | 'hashing' | 'uploading' | 'paused' | 'error' | 'complete' | 'instant' | 'cancelled';
/** 单个本地文件的运行状态；刷新页面后不会持久保存 File 或此队列。 */
type Item = {
    /** 页面内的队列标识，不同于后端用于关联同一文件内容的 MD5。 */
    id: string;
    /** 用户通过选择框或拖放提供的浏览器文件对象。 */
    file: File;
    /** 完整文件指纹；首次计算成功后保留，页面内继续上传时可复用。 */
    md5?: string;
    status: Status;
    /** 显示用百分比，不是正在发送的单片字节级进度。 */
    progress: number;
    error?: string;
    /** 本轮运行使用的控制器；继续上传会重新创建，已中止的控制器不能复用。 */
    controller?: AbortController
};
/** 后端文件列表的响应字段，保留数据库接口中的下划线命名。 */
type Stored = { md5: string; file_name: string; file_size: number; created_at: string };
/**
 * 统一请求后端：成功响应直接解析业务 JSON，没有额外的 data 包装。
 * 非 2xx 时优先使用后端 error 字段，交给上传流程设置失败状态。
 * 开发环境由 Vite 将 /api 代理到后端；此函数本身不做自动重试。
 */
const api = async <T, >(path: string, init?: RequestInit): Promise<T> => {
    const res = await fetch('/api/uploads' + path, init);
    if (!res.ok) {
        const body = await res.json().catch(() => ({error: res.statusText}));
        throw new Error(body.error || res.statusText);
    }
    return res.json();
};
/** 构造初始化等 JSON POST 请求配置，不用于发送分片二进制。 */
const json = (value: unknown): RequestInit => ({
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(value)
});

/**
 * 使用 SparkMD5 增量计算完整文件的摘要，供秒传检查和续传任务匹配使用。
 * 每次读取 4 MiB，避免把整个大文件一次加载进内存；实际上传分片大小为 8 MiB。
 * 此实现运行在主线程，没有 Web Worker；中止信号在每次读取前检查，
 * 已开始的 arrayBuffer() 读取本身不会被该信号直接取消。
 */
async function md5(file: File, signal: AbortSignal) {
    const hash = new SparkMD5.ArrayBuffer();
    const step = 4 * 1024 * 1024;
    // 空文件也有合法 MD5，后续由后端走零字节对象上传流程。
    if (file.size === 0) return SparkMD5.hash('');
    for (let offset = 0; offset < file.size; offset += step) {
        if (signal.aborted) throw new DOMException('Paused', 'AbortError');
        hash.append(await file.slice(offset, offset + step).arrayBuffer());
        // 计算阶段只展示文字状态，不把本地文件读取进度混入网络上传百分比。
    }
    return hash.end();
}

/** 独立上传控制台：本地传输队列与后端已完成文件列表分别维护。 */
function App() {
    // items 保留本地文件和运行状态；stored 来自 MySQL 完成记录，不包含本地 File。
    const [items, setItems] = React.useState<Item[]>([]);
    const [stored, setStored] = React.useState<Stored[]>([]);
    /** 只预览已完成文件；切换文件时通过 key 重建播放器，防止旧链接覆盖新文件。 */
    const [preview, setPreview] = React.useState<Stored | null>(null);
    /** 文件库删除状态：逐项禁用按钮，失败时保留原记录并展示错误。 */
    const [deleting, setDeleting] = React.useState<Set<string>>(new Set());
    const deletingRef = React.useRef(new Set<string>());
    const [fileError, setFileError] = React.useState('');
    const [drag, setDrag] = React.useState(false);
    /** 点击拖放区域时，通过引用打开隐藏的原生文件选择框。 */
    const input = React.useRef<HTMLInputElement>(null);
    /** 与状态更新同步保存队列引用；当前业务操作主要读取传入的队列项。 */
    const itemsRef = React.useRef<Item[]>([]);
    /** 记录正在执行的初始化 Promise，取消时先等待初始化结束，再请求服务端中止。 */
    const pendingInit = React.useRef(new Map<string, Promise<unknown>>());
    /** 函数式更新避免异步上传回调拿到旧数组，仅修改 id 匹配的队列项。 */
    const update = (id: string, patch: Partial<Item>) => setItems(prev => {
        const next = prev.map(x => x.id === id ? {...x, ...patch} : x);
        itemsRef.current = next;
        return next;
    });
    /** 刷新文件库；当前实现忽略列表查询失败，保留原列表，不影响上传队列。 */
    const refresh = React.useCallback(() => api<Stored[]>('/files').then(setStored).catch(() => {
    }), []);
    // 页面首次挂载时加载已完成文件；上传或秒传成功后也会主动刷新。
    React.useEffect(() => {
        refresh();
    }, [refresh]);

    /**
     * 首次上传和“继续”共用的入口：MD5 → 查询状态 → 必要时初始化 → 补传 → 合并。
     * 继续时重新查询 RustFS 的实际片号，不以页面旧进度判断哪些片已经上传。
     */
    async function run(item: Item) {
        const controller = new AbortController();
        update(item.id, {controller, status: item.md5 ? 'uploading' : 'hashing', error: undefined});
        // 分片失败也会中止共享控制器；单独保留原始错误，避免被误判成用户主动暂停。
        let uploadFailure: unknown;
        try {
            // 计算时仅展示“MD5 计算中”；摘要完成后切换到上传状态，不再保留 MD5 状态文案。
            const digest = item.md5 || await md5(item.file, controller.signal);
            update(item.id, {md5: digest, status: 'uploading'});
            const total = chunkCount(item.file.size);
            // 完成对象可直接秒传；未完成任务会返回已上传片号；全新文件返回 new。
            let check = await api<{
                status: string;
                uploadedParts: number[]
            }>(`/check?md5=${digest}&size=${item.file.size}`, {signal: controller.signal});
            if (controller.signal.aborted) throw new DOMException('Paused', 'AbortError');
            if (check.status === 'complete') {
                // 命中秒传后不再初始化、不传文件内容，也不调用合并接口。
                update(item.id, {status: 'instant', progress: 100});
                refresh();
                return;
            }
            if (check.status === 'new') {
                // 初始化不绑定本轮 AbortSignal，让取消操作能够等待其明确结束后再清理。
                // 前端提供 MIME 元数据，未知格式也允许上传；无需传递检测样本。
                const request = api<{status: string; uploadedParts: number[]}>('/init', json({
                    md5: digest,
                    fileName: item.file.name,
                    fileSize: item.file.size,
                    totalChunks: total,
                    contentType: getContentType(item.file)
                }));
                pendingInit.current.set(item.id, request);
                try {
                    check = await request;
                } finally {
                    pendingInit.current.delete(item.id);
                }
            }
            if (controller.signal.aborted) throw new DOMException('Paused', 'AbortError');
            // 初始化期间其他页面可能已建立任务或完成上传，必须使用初始化返回的最新状态。
            if (check.status === 'complete') {
                update(item.id, {status: 'instant', progress: 100});
                refresh();
                return;
            }
            // 片号统一从 1 开始，例如总片号 [1,2,3,4] 减去已传 [1,3] 得到 [2,4]。
            const missing = missingParts(total, check.uploadedParts || []);
            let done = total - missing.length;
            update(item.id, {progress: Math.round(done / total * 95)});
            // 多个异步任务共享游标，取片号后才进入 await，防止同一轮重复领取分片。
            let cursor = 0;
            const worker = async () => {
                while (cursor < missing.length) {
                    if (controller.signal.aborted) throw new DOMException('Paused', 'AbortError');
                    const part = missing[cursor++];
                    // 空文件没有实际分片，直接进入后面的完成接口。
                    if (item.file.size === 0) break;
                    const body = new FormData();
                    // slice 只构造对应范围的 Blob；浏览器自动生成 multipart boundary。
                    // 不手动设置 Content-Type，否则可能缺失 FormData 所需的 boundary。
                    body.append('file', item.file.slice((part - 1) * CHUNK_SIZE, part * CHUNK_SIZE));
                    try {
                        await api(`/${digest}/parts/${part}`, {method: 'PUT', body, signal: controller.signal});
                    } catch (e) {
                        // 任意一片失败就停止本轮其他请求，保留服务端进度供手动继续。
                        if (!controller.signal.aborted) uploadFailure = e;
                        controller.abort();
                        throw e;
                    }
                    // 一片得到成功响应才计入进度；预留最后 5% 等待后端合并确认。
                    done++;
                    update(item.id, {progress: Math.round(done / total * 95)});
                }
            };
            // 并发上限是“每个文件 3 路”；多个文件同时上传时没有全局 3 路限制。
            // worker 是普通异步函数，不是浏览器 Web Worker。
            await Promise.all(Array.from({length: Math.min(3, missing.length)}, worker));
            if (controller.signal.aborted) throw new DOMException('Paused', 'AbortError');
            // 所有缺失分片成功后才申请合并；后端还会重新核对 RustFS 实际分片。
            await api(`/${digest}/complete`, {method: 'POST', signal: controller.signal});
            update(item.id, {status: 'complete', progress: 100});
            refresh();
        } catch (e) {
            // 优先展示分片真实错误；主动中止为暂停，其余异常统一为失败。
            if (uploadFailure) update(item.id, {
                status: 'error',
                error: uploadFailure instanceof Error ? uploadFailure.message : String(uploadFailure)
            });
            else if (controller.signal.aborted) update(item.id, {status: 'paused'});
            else update(item.id, {status: 'error', error: e instanceof Error ? e.message : String(e)});
        }
    }

    /** 选择或拖放文件后加入队列并立即启动；每次选择都会创建独立的页面队列项。 */
    function add(files: FileList | File[]) {
        const next = Array.from(files).map(file => ({
            // 本地 ID 不依赖 crypto.randomUUID，兼容没有该 API 的 HTTP 页面环境。
            id: createUploadId(),
            file,
            status: 'ready' as Status,
            progress: 0
        }));
        setItems(prev => {
            itemsRef.current = [...next, ...prev];
            return itemsRef.current
        });
        next.forEach(run);
    }

    /** 暂停仅停止本轮浏览器请求，不调用 DELETE，保留后端任务用于续传。 */
    function pause(item: Item) {
        item.controller?.abort();
        update(item.id, {status: 'paused'});
    }

    /**
     * 取消会清理未完成会话，与暂停不同。
     * 如初始化仍在进行，先等待其结束，避免 DELETE 先完成而初始化随后又创建任务。
     * 中止浏览器请求不保证后端在途操作立即停止，最终状态仍需服务端确认。
     */
    async function cancel(item: Item) {
        item.controller?.abort();
        try {
            await pendingInit.current.get(item.id)?.catch(() => {
            });
            if (item.md5) await api(`/${item.md5}`, {method: 'DELETE'});
        } catch (e) {
            update(item.id, {status: 'error', error: String(e)});
            return;
        }
        update(item.id, {status: 'cancelled', progress: 0});
    }

    /** 按需获取 15 分钟有效的预签名下载链接，不把过期地址长期存入页面状态。 */
    async function download(file: Stored) {
        // 文件库没有本地 File，使用前端扩展名映射兼容旧视频对象的预览链接。
        const contentType = getContentType({name: file.file_name});
        const {url} = await api<{ url: string }>(`/files/${file.md5}/download?contentType=${encodeURIComponent(contentType)}`);
        window.open(url, '_blank', 'noopener,noreferrer');
    }

    /** 确认后删除完整文件；成功才移除列表行，并清理本地已完成的同内容队列项。 */
    async function deleteFile(file: Stored) {
        if (deletingRef.current.has(file.md5)) return;
        if (!window.confirm(`确定删除“${file.file_name}”？这将删除存储文件及其记录。`)) return;
        deletingRef.current.add(file.md5);
        setDeleting(new Set(deletingRef.current));
        setFileError('');
        try {
            await api(`/files/${file.md5}`, {method: 'DELETE'});
            setPreview(current => current?.md5 === file.md5 ? null : current);
            setStored(prev => prev.filter(x => x.md5 !== file.md5));
            setItems(prev => {
                const next = prev.filter(x => !(x.md5 === file.md5 && ['complete', 'instant'].includes(x.status)));
                itemsRef.current = next;
                return next;
            });
        } catch (e) {
            setFileError(`删除“${file.file_name}”失败：${e instanceof Error ? e.message : String(e)}`);
        } finally {
            deletingRef.current.delete(file.md5);
            setDeleting(new Set(deletingRef.current));
        }
    }

    // 这里统计未结束的队列项，包含暂停和失败项，不等于当前正在传输的网络请求数。
    const active = items.filter(x => !['cancelled', 'complete', 'instant'].includes(x.status)).length;
    return <main className="shell">
        <header>
            <div className="brand"><span className="brandmark">W</span><span>WINTER <b>TRANSFER</b></span></div>
            <div className="headerMeta"><span className="live"></span> STORAGE CONSOLE <span
                className="divider">/</span> 001
            </div>
        </header>
        <section className="hero">
            <div className="eyebrow">FILE DELIVERY SYSTEM <span>—</span> AWS SDK v2 · RUSTFS</div>
            <h1>大文件上传，<br/><em>从容继续。</em></h1><p>分片传输 · 中断续传 ·
            相同文件秒传。把文件放进来，其余交给系统。</p>
            <div className="heroIndex">01 / UPLOAD WORKSPACE</div>
        </section>
        {/* 拖放与文件选择共用 add；阻止浏览器默认打开被拖入文件的行为。 */}
        <section className={'drop ' + (drag ? 'drag' : '')} onDragOver={e => {
            e.preventDefault();
            setDrag(true)
        }} onDragLeave={() => setDrag(false)} onDrop={e => {
            e.preventDefault();
            setDrag(false);
            add(e.dataTransfer.files)
        }} onClick={() => input.current?.click()}>
            <input ref={input} type="file" multiple hidden onChange={e => {
                if (e.target.files) add(e.target.files);
                // 清空选择框值，确保用户再次选择同一个文件时仍能触发 change。
                e.target.value = ''
            }}/>
            <div className="dropIcon">↑</div>
            <div><strong>拖放文件到这里</strong><span>或点击选择文件 · 支持多个文件</span></div>
            <button type="button">选择文件 <span>↗</span></button>
        </section>
        {/* 本地队列根据状态展示操作：上传中可暂停，暂停或失败后可继续，未结束时可取消。 */}
        <section className="queue">
            <div className="sectionHead">
                <div><span className="num">02</span><h2>传输队列</h2></div>
                <span>{active} 项进行中 / {items.length} 项总计</span></div>
            {items.length === 0 ? <div className="empty">等待文件加入队列 <span>—</span></div> : items.map(item =>
                <article className="item" key={item.id}>
                    <div className="fileGlyph">↗</div>
                    <div className="fileInfo"><strong
                        title={item.file.name}>{item.file.name}</strong><span>{(item.file.size / 1024 / 1024).toFixed(2)} MB{item.md5 && <> · {item.status === 'instant' ? '秒传完成' : ({
                        ready: '等待中',
                        hashing: '计算中',
                        uploading: '正在上传',
                        paused: '已暂停',
                        error: '上传失败',
                        complete: '上传完成',
                        cancelled: '已取消'
                    } as Record<Status, string>)[item.status]}</>}</span>
                        {item.error && <small>{item.error}</small>}</div>
                    {/* 两个阶段互斥：摘要未完成只显示 MD5 文字，完成后只显示上传进度。 */}
                    {item.md5 ? <><div className="progress">
                        <div style={{width: `${item.progress}%`}}/>
                    </div>
                    <span className="percent">{Math.round(item.progress)}%</span></> :
                        <><div className="hashStatus">{item.status === 'hashing' ? 'MD5 计算中…' :
                            item.status === 'paused' ? 'MD5 计算已暂停' :
                            item.status === 'error' ? 'MD5 计算失败' :
                            item.status === 'cancelled' ? '已取消' : '等待计算 MD5'}</div><span/></>}
                    <div className="actions">{['uploading', 'hashing'].includes(item.status) &&
                        <button onClick={() => pause(item)}>暂停</button>}{['paused', 'error'].includes(item.status) &&
                        <button
                            onClick={() => run(item)}>继续</button>}{!['cancelled', 'complete', 'instant'].includes(item.status) &&
                        <button onClick={() => cancel(item)}>取消</button>}</div>
                </article>)}
        </section>
        {/* 文件库来自后端完成记录，与当前页面队列分别维护；刷新页面后仍可加载。 */}
        <section className="library">
            <div className="sectionHead">
                <div><span className="num">03</span><h2>已存文件</h2></div>
                <button onClick={refresh}>刷新列表 ↻</button>
            </div>
            {fileError && <p className="fileError" role="alert">{fileError}</p>}
            {stored.length === 0 ? <div className="empty">暂无文件 <span>—</span></div> : stored.map(file => <div
                className="stored" key={file.md5}><span
                className="storedName">{file.file_name}</span><span>{(file.file_size / 1024 / 1024).toFixed(2)} MB</span><span>{new Date(file.created_at).toLocaleString()}</span>
                <div className="storedActions">
                    <button disabled={deleting.has(file.md5)} onClick={() => setPreview(file)}>预览</button>
                    <button disabled={deleting.has(file.md5)} onClick={() => download(file)}>下载 ↗</button>
                    <button className="deleteButton" disabled={deleting.has(file.md5)} onClick={() => deleteFile(file)}>
                        {deleting.has(file.md5) ? '删除中…' : '删除'}
                    </button>
                </div>
            </div>)}
        </section>
        <footer>WINTER TRANSFER <span>BUILT FOR CONTINUITY / 2026</span></footer>
        {preview && <FilePreview key={preview.md5} file={preview} onClose={() => setPreview(null)}
                                 onDownload={() => { void download(preview).catch(e => setFileError(String(e))); }}/>}
    </main>;
}

// index.html 提供 root 挂载节点；非空断言表示此节点属于页面入口约定。
createRoot(document.getElementById('root')!).render(<App/>);
