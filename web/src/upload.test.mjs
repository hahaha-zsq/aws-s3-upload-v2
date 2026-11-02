import test from 'node:test';
import assert from 'node:assert/strict';
import { missingParts, chunkCount, createUploadId, getContentType, getPreviewKind } from './upload.mjs';
// 续传按从 1 开始的服务端片号跳过已完成分片，避免前后端索引偏移。
test('resume skips uploaded 1-based parts',()=>assert.deepEqual(missingParts(4,[1,3]),[2,4]));
// 空文件仍有合法的任务元数据，后续流程通过完成接口创建对象。
test('empty file still has one chunk',()=>assert.equal(chunkCount(0),1));
// 模拟没有 randomUUID 的环境，验证本地队列 ID 生成不依赖该 API。
test('creates unique queue IDs without Web Crypto randomUUID',()=>{
  const original = globalThis.crypto;
  Object.defineProperty(globalThis,'crypto',{configurable:true,value:{}});
  try {
    const first=createUploadId();
    const second=createUploadId();
    assert.match(first,/^upload-/);
    assert.notEqual(first,second);
  } finally {
    // 恢复全局对象，避免本测试的环境模拟影响其他测试。
    Object.defineProperty(globalThis,'crypto',{configurable:true,value:original});
  }
});

test('uses browser type and allows arbitrary file formats',()=>{
  assert.equal(getContentType({name:'file.custom',type:'application/x-custom-format'}),'application/x-custom-format');
  assert.equal(getContentType({name:'file.unknown',type:''}),'application/octet-stream');
  assert.equal(getContentType({name:'file.constructor',type:''}),'application/octet-stream');
  assert.equal(getContentType({name:'movie.mp4',type:''}),'video/mp4');
});

test('preview supports media and PDF while other formats stay downloadable',()=>{
  assert.equal(getPreviewKind({name:'movie.MP4'}),'video');
  assert.equal(getPreviewKind({name:'sound.mp3'}),'audio');
  assert.equal(getPreviewKind({name:'photo.png'}),'image');
  assert.equal(getPreviewKind({name:'document.pdf'}),'pdf');
  assert.equal(getPreviewKind({name:'page.html'}),'unsupported');
  assert.equal(getPreviewKind({name:'archive.zip'}),'unsupported');
});
