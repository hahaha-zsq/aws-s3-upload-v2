import React from 'react';
import {getContentType, getPreviewKind} from './upload.mjs';

type Props = {
    file: {md5: string; file_name: string; file_size: number};
    onClose: () => void;
    onDownload: () => void;
};

/** 原生模态框提供焦点约束；关闭会卸载播放器、停止播放并中止尚未完成的链接请求。 */
export default function FilePreview({file, onClose, onDownload}: Props) {
    const dialog = React.useRef<HTMLDialogElement>(null);
    const [url, setUrl] = React.useState('');
    const [loading, setLoading] = React.useState(true);
    const [error, setError] = React.useState('');
    const kind = getPreviewKind({name: file.file_name});

    React.useEffect(() => {
        const node = dialog.current!;
        node.showModal();
        return () => node.close();
    }, []);

    React.useEffect(() => {
        const controller = new AbortController();
        if (kind === 'unsupported') { setLoading(false); return; }
        const type = getContentType({name: file.file_name});
        fetch(`/api/uploads/files/${file.md5}/download?contentType=${encodeURIComponent(type)}`, {signal: controller.signal})
            .then(async response => {
                const body = await response.json();
                if (!response.ok) throw new Error(body.error || '获取预览链接失败');
                if (!controller.signal.aborted) setUrl(body.url);
            }).catch(e => {
                if (!controller.signal.aborted) setError(e instanceof Error ? e.message : String(e));
            }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
        return () => controller.abort();
    }, [file.md5, file.file_name, kind]);

    const mediaError = () => setError('浏览器无法预览该文件，可能是编码不受支持、文件损坏或访问链接已过期。请关闭后重试，或下载查看。');
    return <dialog ref={dialog} className="previewDialog" aria-labelledby="previewTitle"
                   onCancel={e => { e.preventDefault(); onClose(); }}
                   onClick={e => { if (e.target === dialog.current) onClose(); }}>
        <div className="previewPanel">
            <div className="previewHeader">
                <div><h2 id="previewTitle">{file.file_name}</h2><span>{(file.file_size / 1024 / 1024).toFixed(2)} MB · 文件预览</span></div>
                <button autoFocus onClick={onClose} aria-label="关闭预览">关闭 ×</button>
            </div>
            <div className="previewBody">
                {loading && <p role="status">正在获取预览链接…</p>}
                {error && <p className="fileError" role="alert">{error}</p>}
                {kind === 'unsupported' && <p>此格式暂不支持在线预览，文件仍可正常下载。</p>}
                {url && !error && <>
                    {kind === 'video' && <video src={url} controls playsInline preload="metadata" onError={mediaError}/>}
                    {kind === 'audio' && <audio src={url} controls preload="metadata" onError={mediaError}/>}
                    {kind === 'image' && <img src={url} alt={file.file_name} onError={mediaError}/>}
                    {kind === 'pdf' && <iframe src={url} title={`预览 ${file.file_name}`} sandbox="allow-same-origin" onError={mediaError}/>}
                </>}
            </div>
            <div className="previewFooter">
                <span>预览链接有效期为 15 分钟</span>
                {url && <a href={url} target="_blank" rel="noopener noreferrer">新窗口打开 ↗</a>}
                <button onClick={onDownload}>下载文件 ↗</button>
            </div>
        </div>
    </dialog>;
}
