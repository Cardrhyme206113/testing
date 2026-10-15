'use strict';
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const OUT='createworld-evidence';fs.mkdirSync(OUT,{recursive:true});
const report={cases:[],pageErrors:[],console:[],finished:null};
const pause=t=>new Promise(r=>setTimeout(r,t));
async function main(){
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--lang=en-US']});
 const context=await browser.newContext({viewport:{width:1280,height:800},locale:'en-US',deviceScaleFactor:1});
 const page=await context.newPage();
 page.on('pageerror',e=>report.pageErrors.push(String(e.stack||e).slice(0,4000)));
 page.on('console',m=>{if(report.console.length<3000)report.console.push({type:m.type(),text:m.text().slice(0,700)});});
 page.on('dialog',async d=>{report.cases.push({nativeDialog:d.message()});await d.dismiss();});
 const shot=async n=>{await page.screenshot({path:path.join(OUT,n+'.png'),timeout:25000});return n+'.png';};
 await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1',{waitUntil:'domcontentloaded',timeout:120000});
 await pause(30000);
 async function open(){
  const r=await page.evaluate(()=>Module._lce_web_test_open_ui(12));if(r!==1)throw new Error('Request rejected '+r);
  let x=-99;for(let i=0;i<55;i++){await pause(250);x=await page.evaluate(()=>Module._lce_web_test_last_ui_result());if(x!==-99)break;}
  if(x!==1)throw new Error('Create World open failed '+x);
  await pause(1300);
 }
 const controls=[
  ['world-name',642,259],['seed',644,329],['game-mode',643,404],['texture-pack',500,482],['difficulty',638,551],['more-options',641,596]
 ];
 for(const [name,x,y] of controls){
   const one={name,x,y,status:'unknown',scenesBefore:[],scenesAfter:[]};report.cases.push(one);
   try{
    await open();
    one.before=await shot(name+'-before');
    one.scenesBefore=(await page.evaluate(()=>globalThis.LCEWebIggy?.debugState?.().players?.filter(p=>p.visible).map(p=>p.filename)||[]));
    await page.mouse.click(x,y);
    await pause(2000);
    one.after=await shot(name+'-after');
    one.scenesAfter=(await page.evaluate(()=>globalThis.LCEWebIggy?.debugState?.().players?.filter(p=>p.visible).map(p=>p.filename)||[]));
    one.inputs=await page.evaluate(()=>[...document.querySelectorAll('input,textarea')].filter(el=>el.getBoundingClientRect().width>0).map(el=>({tag:el.tagName,type:el.type,value:el.value})));
    one.status='captured';
    if(name==='world-name'||name==='seed'){
      await page.keyboard.insertText(name==='world-name'?'Playtest UI':'123456');
      await pause(400);
      one.typed=await shot(name+'-typed');
      await page.keyboard.press('Enter');
      await pause(1300);
      one.confirmed=await shot(name+'-confirmed');
    }
   }catch(e){one.status='failed';one.error=String(e);}
   fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
 }
 const last={name:'create-world',x:640,y:648,status:'unknown'};report.cases.push(last);
 try{
  await open();last.before=await shot('create-world-before');
  await page.mouse.click(640,648);await pause(2500);last.after=await shot('create-world-after');
  await page.keyboard.press('Enter');await pause(24000);last.wait=await shot('create-world-after-24s');
  last.status='captured';
 }catch(e){last.status='failed';last.error=String(e);}
 report.finished=new Date().toISOString();
 fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
 fs.writeFileSync(path.join(OUT,'console-errors.txt'),report.console.filter(x=>/error|warn/i.test(x.type)).map(x=>x.text).join('\n').slice(-300000));
 await browser.close();
}
main().catch(e=>{report.pageErrors.push(String(e.stack||e));report.finished=new Date().toISOString();fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));console.error(e);process.exitCode=1});
