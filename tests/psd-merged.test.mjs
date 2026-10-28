import {test} from 'node:test';
import assert from 'node:assert/strict';
import {deflateSync} from 'node:zlib';
globalThis.document={createElement(){const context={createImageData(w,h){return {data:new Uint8ClampedArray(w*h*4)}},putImageData(image){this.image=image},drawImage(source){this.image=source.getContext('2d').image}};return {width:0,height:0,getContext(){return context}}}};
const {readPsdMerged}=await import('../web/psd.mjs');
const u16=n=>{const b=Buffer.alloc(2);b.writeUInt16BE(n);return b};
const s16=n=>{const b=Buffer.alloc(2);b.writeInt16BE(n);return b};
const u32=n=>{const b=Buffer.alloc(4);b.writeUInt32BE(n);return b};
const u64=n=>Buffer.concat([u32(0),u32(n)]);
function fixture({version=1,depth=8,mode=3,channels,alpha=false,rle=false,zip=false}){
 const layer=alpha?Buffer.concat([version===2?u64(2):u32(2),s16(-1)]):Buffer.alloc(0);
 const ch=channels.map(v=>depth===8?Buffer.from([v]):depth===16?u16(v):(()=>{const b=Buffer.alloc(4);b.writeFloatBE(v);return b})());
 const payload=zip?Buffer.concat([u16(2),deflateSync(Buffer.concat(ch))]):rle?Buffer.concat([u16(1),...ch.map(b=>version===2?u32(b.length+1):u16(b.length+1)),...ch.map(b=>Buffer.concat([Buffer.from([b.length-1]),b]))]):Buffer.concat([u16(0),...ch]);
 const file=Buffer.concat([Buffer.from('8BPS'),u16(version),Buffer.alloc(6),u16(channels.length),u32(1),u32(1),u16(depth),u16(mode),u32(0),u32(0),version===2?u64(layer.length):u32(layer.length),layer,payload]);
 return file.buffer.slice(file.byteOffset,file.byteOffset+file.byteLength);
}
function pixels(result){return [...result.document.layers[0].canvas.getContext('2d').image.data]}
test('merged 16-bit RGB and alpha are converted to 8-bit',async()=>{
 const p=await readPsdMerged(fixture({depth:16,mode:3,channels:[65535,32768,0,16384],alpha:true}));
 assert.equal(p.merged,true);assert.deepEqual(pixels(p),[255,128,0,64]);
});
test('PSB grayscale PackBits decodes all colour channels',async()=>{
 const p=await readPsdMerged(fixture({version:2,mode:1,channels:[121],rle:true}));
 assert.deepEqual(pixels(p),[121,121,121,255]);
});
test('32-bit float RGB ZIP composite is converted to 8-bit',async()=>{
 const p=await readPsdMerged(fixture({depth:32,mode:3,channels:[1,0.5,0],zip:true}));
 assert.deepEqual(pixels(p),[255,128,0,255]);
});
test('unsupported merged colour modes fail explicitly',async()=>{
 await assert.rejects(readPsdMerged(fixture({mode:4,channels:[1,2,3,4]})),e=>e.code==='psdUnsupported');
});

