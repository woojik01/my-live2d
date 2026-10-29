import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';


function clippedFixture(){
 const u16=n=>{const b=Buffer.alloc(2);b.writeUInt16BE(n);return b},s16=n=>{const b=Buffer.alloc(2);b.writeInt16BE(n);return b},u32=n=>{const b=Buffer.alloc(4);b.writeUInt32BE(n);return b},i32=n=>{const b=Buffer.alloc(4);b.writeInt32BE(n);return b};
 function layer(name,clipped){
  const channels=[0,1,2,-1].map((id,i)=>({id,bytes:Buffer.concat([u16(0),Buffer.from([i===3?255:100+i])])}));
  const pascal=Buffer.alloc(4);pascal[0]=name.length;Buffer.from(name).copy(pascal,1);
  const extra=Buffer.concat([u32(0),u32(0),pascal]);
  const header=Buffer.concat([i32(0),i32(0),i32(1),i32(1),u16(4),...channels.map(c=>Buffer.concat([s16(c.id),u32(c.bytes.length)])),Buffer.from(clipped?'8BIMmul ':'8BIMnorm'),Buffer.from([255,clipped?1:0,0,0]),u32(extra.length),extra]);
  return {header,data:channels.map(c=>c.bytes)};
 }
 const records=[layer('A',false),layer('B',true)],info=Buffer.concat([s16(2),...records.map(x=>x.header),...records.flatMap(x=>x.data)]),lm=Buffer.concat([u32(info.length),info]);
 return Buffer.concat([Buffer.from('8BPS'),u16(1),Buffer.alloc(6),u16(4),u32(1),u32(1),u16(8),u16(3),u32(0),u32(0),u32(lm.length),lm]);
}

test('web editor loads, edits a key and restores project',async()=>{
 const server=spawn('python3',['-m','http.server','18765','--bind','127.0.0.1','--directory','web'],{stdio:'ignore'});
 let browser;
 try{
  await delay(700);browser=await chromium.launch({headless:true});
  const page=await browser.newPage({viewport:{width:1280,height:800}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:18765/',{waitUntil:'networkidle'});
  await page.getByRole('button',{name:'예제 열기'}).click();
  await page.getByRole('button',{name:'현재 값 키폼 편집'}).click();
  await page.getByRole('button',{name:'키폼 기록'}).click();
  await page.waitForTimeout(1200);
  assert.equal(await page.locator('#project-name').inputValue(),'예제 캐릭터');
  await page.reload({waitUntil:'networkidle'});
  assert.equal(await page.locator('#project-name').inputValue(),'예제 캐릭터');
  assert.ok(await page.getByText('예제 캐릭터').count()>0);
  await page.getByRole('button',{name:'복제',exact:true}).click();
  assert.equal(await page.locator('#part-count').innerText(),'2');
  assert.ok(await page.locator('.key-list').count()>0);
  await page.keyboard.press('Control+z');
  assert.equal(await page.locator('#part-count').innerText(),'1');
  await page.locator('#zoom-in').click();
  const zoomAfterIn=await page.locator('#zoom-label').innerText();
  await page.locator('#zoom-out').click();
  assert.notEqual(await page.locator('#zoom-label').innerText(),zoomAfterIn);
  await page.locator('#params input[type=range]').evaluate(el=>{el.value='15';el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))});
  await page.locator('#reset-params').click();
  assert.equal(Number(await page.locator('#params input[type=range]').inputValue()),0);
  page.once('dialog',dialog=>dialog.accept());
  await page.getByRole('button',{name:'파라미터 삭제'}).click();
  assert.equal(await page.locator('#params .parameter').count(),0);
  await page.locator('#undo').click();
  assert.equal(await page.locator('#params .parameter').count(),1);
  await page.keyboard.press('m');
  assert.equal(await page.locator('#mode-mesh').getAttribute('aria-pressed'),'true');
  await page.locator('#psd-input').setInputFiles({name:'clipping.psd',mimeType:'image/vnd.adobe.photoshop',buffer:clippedFixture()});
  await page.waitForFunction(()=>document.querySelector('#part-count').textContent==='3');
  await page.waitForTimeout(1000);
  const saved=await page.evaluate(()=>new Promise((resolve,reject)=>{const q=indexedDB.open('my-live2d-web',1);q.onerror=()=>reject(q.error);q.onsuccess=()=>{const db=q.result,t=db.transaction('state'),req=t.objectStore('state').get('active');req.onsuccess=()=>{db.close();resolve(req.result)};req.onerror=()=>reject(req.error)}}));
  const clipped=saved.parts.find(p=>p.clipBase);
  assert.ok(clipped);assert.equal(clipped.blend,'multiply');assert.ok(saved.parts.some(p=>p.id===clipped.clipBase));
  await page.reload({waitUntil:'networkidle'});
  assert.equal(await page.locator('#part-count').innerText(),'3');
  assert.deepEqual(errors,[]);
  await page.setViewportSize({width:390,height:844});
  await page.locator('[data-panel="inspector-panel"]').click();
  assert.equal(await page.locator('#inspector-panel').isVisible(),true);
  await page.locator('[data-panel="canvas"]').click();
  assert.equal(await page.locator('#inspector-panel').isVisible(),false);
 }finally{if(browser)await browser.close();server.kill()}
});
