import {test} from 'node:test';
import assert from 'node:assert/strict';
import {deflateSync} from 'node:zlib';
globalThis.document={createElement(){const context={createImageData(w,h){return {data:new Uint8ClampedArray(w*h*4)}},putImageData(image){this.image=image},drawImage(source){this.image=source.getContext('2d').image}};return {width:0,height:0,getContext(){return context}}}};
const {readPsd,readPsdMerged}=await import('../web/psd.mjs');
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


test('layered PSD preserves bottom-to-top ordering and clipping base',async()=>{
 const i32=n=>{const b=Buffer.alloc(4);b.writeInt32BE(n);return b};
 const record=(name,section,clip=false)=>{
  const group=section!==null, data=group?[]:[0,1,2,-1].map((id,i)=>({id,data:Buffer.concat([u16(0),Buffer.from([i===3?255:50+i])])}));
  const nam=Buffer.from(name),pas=Buffer.alloc(Math.ceil((nam.length+1)/4)*4);pas[0]=nam.length;nam.copy(pas,1);
  const sectionData=group?Buffer.concat([Buffer.from('8BIMlsct'),u32(4),u32(section)]):Buffer.alloc(0);
  const extra=Buffer.concat([u32(0),u32(0),pas,sectionData]);
  const header=Buffer.concat([i32(0),i32(0),i32(group?0:1),i32(group?0:1),u16(data.length),...data.map(c=>Buffer.concat([s16(c.id),u32(c.data.length)])),Buffer.from('8BIMnorm'),Buffer.from([255,clip?1:0,0,0]),u32(extra.length),extra]);
  return {header,data:data.map(x=>x.data)};
 };
 const records=[record('end',3),record('base',null),record('clip',null,true),record('group',2)];
 const info=Buffer.concat([s16(records.length),...records.map(x=>x.header),...records.flatMap(x=>x.data)]);
 const lm=Buffer.concat([u32(info.length),info]);
 const file=Buffer.concat([Buffer.from('8BPS'),u16(1),Buffer.alloc(6),u16(4),u32(1),u32(1),u16(8),u16(3),u32(0),u32(0),u32(lm.length),lm]);
 const result=await readPsd(file.buffer.slice(file.byteOffset,file.byteOffset+file.byteLength));
 const layers=result.document.layers.filter(x=>x.type==='paint');
 assert.deepEqual(layers.map(x=>x.name),['base','clip']);
 assert.equal(layers[1].clipBaseId,layers[0].importId);
});
