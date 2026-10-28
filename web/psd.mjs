// Adapted from the user's My Paint PSD reader.
import {canvas,layer as createLayer,group as createGroup,id,validateSize,MAX_BYTES} from './psd-model.mjs';

const MAX_PIXELS=16_777_216;
const fail=code=>{const error=new Error(code);error.code=code;throw error;};

class Reader {
  constructor(bytes,start=0,end=bytes.length){this.bytes=bytes;this.view=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);this.offset=start;this.end=end;}
  require(size){if(!Number.isSafeInteger(size)||size<0||this.offset+size>this.end)fail('psdCorrupt');}
  u8(){this.require(1);return this.view.getUint8(this.offset++);}
  u16(){this.require(2);const value=this.view.getUint16(this.offset);this.offset+=2;return value;}
  s16(){this.require(2);const value=this.view.getInt16(this.offset);this.offset+=2;return value;}
  u32(){this.require(4);const value=this.view.getUint32(this.offset);this.offset+=4;return value;}
  s32(){this.require(4);const value=this.view.getInt32(this.offset);this.offset+=4;return value;}
  u64(){const high=this.u32(),low=this.u32(),value=high*0x100000000+low;if(!Number.isSafeInteger(value))fail('psdCorrupt');return value;}
  ascii(size){this.require(size);let value='';for(let i=0;i<size;i++)value+=String.fromCharCode(this.bytes[this.offset++]);return value;}
  take(size){this.require(size);const value=this.bytes.subarray(this.offset,this.offset+size);this.offset+=size;return value;}
  skip(size){this.require(size);this.offset+=size;}
  get remaining(){return this.end-this.offset;}
}

function boundedEnd(reader,size){if(!Number.isSafeInteger(size)||size<0||reader.offset+size>reader.end)fail('psdCorrupt');return reader.offset+size;}

function parseLayerExtra(reader,record){
  if(reader.remaining<4)fail('psdCorrupt');
  const maskLength=reader.u32(),maskEnd=boundedEnd(reader,maskLength);
  if(maskLength>=18){
    const maskReader=new Reader(reader.bytes,reader.offset,maskEnd);
    record.mask={top:maskReader.s32(),left:maskReader.s32(),bottom:maskReader.s32(),right:maskReader.s32(),defaultColor:maskReader.u8(),flags:maskReader.u8()};
    if(record.mask.right<record.mask.left||record.mask.bottom<record.mask.top)fail('psdCorrupt');
  }
  reader.offset=maskEnd;
  const blendingLength=reader.u32();reader.skip(blendingLength);
  const nameStart=reader.offset,nameLength=reader.u8(),nameBytes=reader.take(nameLength);
  record.name=new TextDecoder('windows-1252').decode(nameBytes).slice(0,100);
  reader.skip((4-(reader.offset-nameStart)%4)%4);
  while(reader.remaining>=12){
    const signature=reader.ascii(4),key=reader.ascii(4);
    if(['TySh','SoCo','GdFl','PtFl','vmsk','vogk','lrFX','lfx2','brit','levl','curv','hue2'].includes(key))record.unsupported=true;
    if(signature!=='8BIM'&&signature!=='8B64')fail('psdCorrupt');
    const length=signature==='8B64'?reader.u64():reader.u32(),dataEnd=boundedEnd(reader,length),data=reader.take(length);
    if(key==='luni'&&data.length>=4){
      const info=new Reader(data),count=info.u32();
      if(count>(data.length-4)/2||count>1024)fail('psdCorrupt');
      let name='';for(let i=0;i<count;i++)name+=String.fromCharCode(info.u16());record.name=name.slice(0,100);
    }else if((key==='lsct'||key==='lsdk')&&data.length>=4){
      const info=new Reader(data);record.section=info.u32();
      if(info.remaining>=8){info.skip(4);const groupBlend=info.ascii(4);record.blend=blendMode(groupBlend);}
    }
    reader.offset=dataEnd;
    if(length%2)reader.skip(1);
  }
  reader.skip(reader.remaining);
  if(!record.name)record.name='Layer';
}

function blendMode(value){return value==='mul '?'multiply':value==='scrn'?'screen':'source-over';}

function readLayerRecord(reader){
  const record={top:reader.s32(),left:reader.s32(),bottom:reader.s32(),right:reader.s32()};
  record.width=record.right-record.left;record.height=record.bottom-record.top;
  if(record.width<0||record.height<0||record.width*record.height>MAX_PIXELS)fail('psdMemory');
  const count=reader.u16();if(count>64)fail('psdCorrupt');record.channels=[];
  for(let i=0;i<count;i++)record.channels.push({id:reader.s16(),length:reader.u32()});
  const signature=reader.ascii(4);if(signature!=='8BIM')fail('psdCorrupt');
  record.blendKey=reader.ascii(4);record.blend=blendMode(record.blendKey);record.opacity=reader.u8()/255;record.clipped=reader.u8()===1;
  const flags=reader.u8();record.alphaLock=!!(flags&1);record.hidden=!!(flags&2);reader.skip(1);
  const extraLength=reader.u32(),extraEnd=boundedEnd(reader,extraLength),extra=new Reader(reader.bytes,reader.offset,extraEnd);
  parseLayerExtra(extra,record);reader.offset=extraEnd;
  return record;
}

function decodePackBits(bytes,start,end,width){
  const output=new Uint8Array(width);let input=start,out=0;
  while(input<end&&out<width){
    const value=bytes[input++],control=value<128?value:value-256;
    if(control>=0){const length=control+1;if(input+length>end||out+length>width)fail('psdCorrupt');output.set(bytes.subarray(input,input+length),out);input+=length;out+=length;}
    else if(control!==-128){const length=1-control;if(input>=end||out+length>width)fail('psdCorrupt');output.fill(bytes[input++],out,out+length);out+=length;}
  }
  if(out!==width)fail('psdCorrupt');return output;
}

async function inflateDeflate(bytes,expected){
  if(typeof DecompressionStream==='undefined')fail('psdZipUnavailable');
  try{
    const stream=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate'));
    const reader=stream.getReader(),chunks=[];let total=0;
    while(true){const part=await reader.read();if(part.done)break;total+=part.value.byteLength;if(total>expected){await reader.cancel();fail('psdMemory');}chunks.push(part.value);}
    if(total!==expected)fail('psdCorrupt');
    const output=new Uint8Array(total);let offset=0;for(const chunk of chunks){output.set(chunk,offset);offset+=chunk.byteLength;}return output;
  }catch(error){if(error?.code)throw error;fail('psdCompression');}
}

async function decodeChannel(bytes,channel,width,height){
  const pixels=width*height;if(!pixels)return null;
  if(channel.length<2)fail('psdCorrupt');
  const data=bytes.subarray(channel.offset,channel.offset+channel.length),reader=new Reader(data),compression=reader.u16();
  if(compression===0){if(reader.remaining<pixels)fail('psdCorrupt');return reader.take(pixels).slice();}
  if(compression===1){
    if(height*2>reader.remaining)fail('psdCorrupt');const rowLengths=[];for(let y=0;y<height;y++)rowLengths.push(reader.u16());
    const output=new Uint8Array(pixels);
    for(let y=0;y<height;y++){const end=boundedEnd(reader,rowLengths[y]);output.set(decodePackBits(data,reader.offset,end,width),y*width);reader.offset=end;}
    return output;
  }
  if(compression===2||compression===3){
    const output=await inflateDeflate(reader.take(reader.remaining),pixels);
    if(compression===3)for(let y=0;y<height;y++){const row=y*width;for(let x=1;x<width;x++)output[row+x]=(output[row+x]+output[row+x-1])&255;}
    return output;
  }
  fail('psdCompression');
}

function channelRect(record,channel){
  if(channel.id!==-2||!record.mask||record.mask.flags&2)return {left:record.left,top:record.top,width:record.width,height:record.height,mask:false};
  let {left,top,right,bottom}=record.mask;
  if(record.mask.flags&1){left+=record.left;right+=record.left;top+=record.top;bottom+=record.top;}
  return {left,top,width:right-left,height:bottom-top,mask:true};
}

async function paintLayer(bytes,record,docWidth,docHeight,parent){
  const result=createLayer(record.name,record.width,record.height,parent),context=result.canvas.getContext('2d');result.left=record.left;result.top=record.top;
  result.visible=!record.hidden;result.opacity=record.opacity;result.blend=record.blend;result.clip=record.clipped;result.alphaLock=record.alphaLock;
  if(record.width&&record.height){
    const image=context.createImageData(record.width,record.height),pixels=image.data;
    let hasAlpha=false;
    for(const channel of record.channels){
      if(![0,1,2,-1,-2].includes(channel.id)||!channel.length)continue;
      const rect=channelRect(record,channel);if(channel.id===-2&&!rect.mask)continue;if(rect.width<0||rect.height<0||rect.width*rect.height>MAX_PIXELS)fail('psdMemory');
      const plane=await decodeChannel(bytes,channel,rect.width,rect.height);if(!plane)continue;
      if(rect.mask){
        if(!result.mask){result.mask=canvas(record.width,record.height);const maskContext=result.mask.getContext('2d');if(record.mask.defaultColor===255){maskContext.fillStyle='#fff';maskContext.fillRect(0,0,record.width,record.height);}}
        const maskContext=result.mask.getContext('2d'),maskImage=maskContext.createImageData(rect.width,rect.height);
        for(let i=0;i<plane.length;i++){const p=i*4;maskImage.data[p]=255;maskImage.data[p+1]=255;maskImage.data[p+2]=255;maskImage.data[p+3]=plane[i];}
        maskContext.putImageData(maskImage,rect.left-record.left,rect.top-record.top);continue;
      }
      const component=channel.id===-1?3:channel.id;
      if(component===3)hasAlpha=true;
      const x0=rect.left,y0=rect.top,x1=rect.left+rect.width,y1=rect.top+rect.height;
      for(let y=y0;y<y1;y++)for(let x=x0;x<x1;x++)pixels[((y-record.top)*record.width+(x-record.left))*4+component]=plane[(y-rect.top)*rect.width+(x-rect.left)];
    }
    if(!hasAlpha)for(let i=3;i<pixels.length;i+=4)pixels[i]=255;
    context.putImageData(image,0,0);
  }
  return result;
}

function buildLayerTree(records){
  const roots=[],stack=[{children:roots}];
  for(let i=records.length-1;i>=0;i--){
    const record=records[i];
    if(record.section===1||record.section===2){const node={kind:'group',record,children:[]};stack[stack.length-1].children.push(node);stack.push(node);}
    else if(record.section===3){if(stack.length>1)stack.pop();}
    else stack[stack.length-1].children.push({kind:'paint',record});
  }
  return roots;
}

async function inflateComposite(bytes,offset,width,height,channels){
  const reader=new Reader(bytes,offset),compression=reader.u16(),pixels=width*height;
  if(pixels*channels>MAX_BYTES)fail('psdMemory');
  let planes=[];
  if(compression===0){for(let c=0;c<channels;c++)planes.push(reader.take(pixels).slice());}
  else if(compression===1){
    const rows=height*channels;if(rows*2>reader.remaining)fail('psdCorrupt');const lengths=[];for(let i=0;i<rows;i++)lengths.push(reader.u16());
    for(let c=0;c<channels;c++){const plane=new Uint8Array(pixels);for(let y=0;y<height;y++){const end=boundedEnd(reader,lengths[c*height+y]);plane.set(decodePackBits(bytes,reader.offset,end,width),y*width);reader.offset=end;}planes.push(plane);}
  }else if(compression===2||compression===3){
    const raw=await inflateDeflate(reader.take(reader.remaining),pixels*channels);
    for(let c=0;c<channels;c++){const plane=raw.slice(c*pixels,(c+1)*pixels);if(compression===3)for(let y=0;y<height;y++){const row=y*width;for(let x=1;x<width;x++)plane[row+x]=(plane[row+x]+plane[row+x-1])&255;}planes.push(plane);}
  }else fail('psdCompression');
  const rgba=new Uint8ClampedArray(pixels*4);for(let i=0;i<pixels;i++){rgba[i*4]=planes[0][i];rgba[i*4+1]=planes[1][i];rgba[i*4+2]=planes[2][i];rgba[i*4+3]=channels>3?planes[3][i]:255;}
  return rgba;
}

async function readPsd(buffer,name='Imported PSD'){
  const bytes=new Uint8Array(buffer),reader=new Reader(bytes);
  if(reader.ascii(4)!=='8BPS')fail('psdInvalid');
  const version=reader.u16();reader.skip(6);const channels=reader.u16(),height=reader.u32(),width=reader.u32(),depth=reader.u16(),colorMode=reader.u16();
  if(version!==1||colorMode!==3||depth!==8||channels<3||channels>16)fail('psdUnsupported');
  try{validateSize(width,height);}catch{fail('psdMemory');}
  const colorDataLength=reader.u32();reader.skip(colorDataLength);const imageResourcesLength=reader.u32();reader.skip(imageResourcesLength);
  const layerMaskLength=reader.u32(),layerMaskEnd=boundedEnd(reader,layerMaskLength);if(layerMaskLength>0&&layerMaskLength<4)fail('psdCorrupt');let records=[];
  if(layerMaskLength>=4){
    const layerInfoLength=reader.u32(),layerInfoEnd=boundedEnd(reader,layerInfoLength);if(layerInfoEnd>layerMaskEnd)fail('psdCorrupt');
    if(layerInfoLength>=2){
      const info=new Reader(bytes,reader.offset,layerInfoEnd);let count=info.s16();if(count<0)count=-count;if(count>96)fail('psdTooManyLayers');
      for(let i=0;i<count;i++)records.push(readLayerRecord(info));
      for(const record of records){for(const channel of record.channels){channel.offset=info.offset;boundedEnd(info,channel.length);info.skip(channel.length);}}
    }
    reader.offset=layerMaskEnd;
  }
  const paintCount=records.filter(record=>record.section!==1&&record.section!==2&&record.section!==3).length;
  if(paintCount>32||records.some(r=>r.width>2048||r.height>2048)||records.filter(r=>!r.section).reduce((sum,r)=>sum+r.width*r.height,0)>16777216)fail('psdMemory');
  if(records.some(r=>r.unsupported||r.clipped||(r.section!==3&&r.blendKey!=='norm'&&r.blendKey!=='pass')||((r.section===1||r.section===2)&&r.opacity!==1)||r.channels.some(c=>c.id===-3)))fail('psdUnsupportedLayers');
  const document={id:id(),name:String(name).slice(0,100)||'Imported PSD',width,height,background:'transparent',revision:0,updated:Date.now(),layers:[],active:''};
  const tree=buildLayerTree(records);let nestedGroups=0;
  const addPaint=async(record,parent,path=[],visible=true,opacity=1)=>{
    const copy={...record,name:[...path,record.name].filter(Boolean).join(' / ')||'Layer'};
    const item=await paintLayer(bytes,copy,width,height,parent);item.visible=item.visible&&visible;item.opacity*=opacity;document.layers.push(item);if(!document.active)document.active=item.id;
  };
  const addNested=async(node,parent,path,visible,opacity)=>{
    nestedGroups++;
    const nextPath=[...path,node.record.name];const nextVisible=visible&&!node.record.hidden,nextOpacity=opacity*node.record.opacity;
    for(const child of node.children)if(child.kind==='paint')await addPaint(child.record,parent,nextPath,nextVisible,nextOpacity);else await addNested(child,parent,nextPath,nextVisible,nextOpacity);
  };
  for(const node of tree){
    if(node.kind==='paint'){await addPaint(node.record,null);continue;}
    const item=createGroup(node.record.name);item.visible=!node.record.hidden;item.opacity=node.record.opacity;item.blend=node.record.blend;document.layers.push(item);
    for(const child of node.children)if(child.kind==='paint')await addPaint(child.record,item.id,[],item.visible,1);else await addNested(child,item.id,[],item.visible,1);
  }
  if(!document.layers.some(item=>item.type==='paint')){
    if(channels>4)fail('psdUnsupported');const rgba=await inflateComposite(bytes,reader.offset,width,height,channels),item=createLayer('Merged image',width,height);
    const context=item.canvas.getContext('2d'),image=context.createImageData(width,height);image.data.set(rgba);context.putImageData(image,0,0);document.layers.push(item);document.active=item.id;
  }
  return {document,nestedGroups};
}

export {readPsd};
