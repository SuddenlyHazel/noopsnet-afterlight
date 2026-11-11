import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {runInNewContext} from 'node:vm';

test('offline picker selects arbitrary RGB, validates hex and switches power without stale renders', async()=>{
  const root=new URL('../preview/',import.meta.url);
  const page=readFileSync(new URL('index.html',root),'utf8');
  const catalog=JSON.parse(readFileSync(new URL('catalog.json',import.meta.url),'utf8'));
  const ctx=()=>({clearRect(){},drawImage(){},fillRect(){}});
  const element=dataset=>({dataset,value:'',textContent:'',attrs:{},events:{},
    addEventListener(type,handler){this.events[type]=handler;},
    setAttribute(key,value){this.attrs[key]=value;},getContext:ctx});
  const forms=Object.entries(catalog.forms).map(([form,spec])=>element({form,label:spec.name}));
  const presets=Object.keys(catalog.colors).map(preset=>element({preset}));
  const ids=Object.fromEntries(['picker','hex','invalid','power'].map(key=>['#'+key,element({})]));
  const loads=[];
  class ImageMock {
    set src(path){assert.ok(existsSync(fileURLToPath(new URL(path,root))),path);loads.push(path);queueMicrotask(()=>this.onload());}
  }
  const document={
    querySelector:selector=>ids[selector],
    querySelectorAll:selector=>selector==='[data-form]'?forms:presets,
    createElement:()=>element({})
  };
  runInNewContext(page.match(/<script>([\s\S]*?)<\/script>/)[1],{document,Image:ImageMock,Map,Promise});
  const settle=()=>new Promise(resolve=>setImmediate(resolve));
  await settle();
  assert.equal(forms[0].attrs['aria-label'],'Glow Inlay, #FFA743, on');
  ids['#hex'].events.input({target:{value:'#123abc'}});await settle();
  assert.equal(ids['#picker'].value,'#123ABC');
  assert.match(forms[0].attrs['aria-label'],/#123ABC/);
  ids['#hex'].events.input({target:{value:'bad'}});await settle();
  assert.equal(ids['#invalid'].textContent,'Use six hex digits');
  assert.equal(ids['#picker'].value,'#123ABC');
  ids['#picker'].events.input({target:{value:'#000000'}});
  ids['#picker'].events.input({target:{value:'#f0cafe'}});
  ids['#power'].onclick();await settle();
  assert.equal(ids['#power'].attrs['aria-pressed'],'true');
  assert.equal(forms[0].attrs['aria-label'],'Glow Inlay, #F0CAFE, off');
  presets[1].onclick();await settle();
  assert.equal(ids['#picker'].value,'#FF42A4');
  assert.equal(presets[1].attrs['aria-pressed'],'true');
  assert.ok(loads.some(path=>path.endsWith('_off_glow.png')));
  assert.ok(forms.every(canvas=>canvas.attrs['aria-label'].includes('#FF42A4')));
});
