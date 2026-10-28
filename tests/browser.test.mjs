import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

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
  assert.deepEqual(errors,[]);
  await page.setViewportSize({width:390,height:844});
  await page.locator('[data-panel="inspector-panel"]').click();
  assert.equal(await page.locator('#inspector-panel').isVisible(),true);
  await page.locator('[data-panel="canvas"]').click();
  assert.equal(await page.locator('#inspector-panel').isVisible(),false);
 }finally{if(browser)await browser.close();server.kill()}
});
