'use strict';
const {chromium}=require('playwright'),fs=require('fs'),path=require('path');
const OUT='fix96-diagnostics';fs.mkdirSync(OUT,{recursive:true});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage']});
const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
const errors=[];
page.on('pageerror',e=>errors.push(String(e)));page.on('dialog',d=>d.dismiss().catch(()=>{}));
const snap=async name=>{
await page.screenshot({path:path.join(OUT,name+'.png')});
return page.evaluate(()=>{
const h=globalThis.LCEWebIggy; const players=(h?.debugState()?.players||[]).filter(x=>x.visible);
const p=players.find(x=>x.filename==='FullscreenProgress720.swf');
const paths=['Tip','MainPanel.Tip','Tip.Label','Tip.Text','Tip.m_tfTextField','Tip.m_tfLabel','Tip.FJ_Label','Title','ProgressBar','Timer','MainPanel'];
const props=['visible','text','htmlText','m_sText','label','x','y','width','height','__lce_stage_x','__lce_stage_y'];
return {players:players.map(x=>({id:x.id,filename:x.filename})),tipId:p?.id,
 paths:p?paths.map(k=>{const o={path:k};try{o.exists=h.pathExists(p.id,k)}catch(e){o.error=String(e)}
 for(const prop of props)try{let v=h.getValue(p.id,k,prop);if(v!==undefined&&v!==null)o[prop]=v}catch(e){}
 return o;}):[], calls:globalThis.__lce96?.calls?.slice(-200), panels:globalThis.__lce96?.panels?.slice(-50)};
});
};
try{
await page.goto('http://127.0.0.1:8000/lce_web_demo.html',{waitUntil:'domcontentloaded',timeout:120000});
await delay(34000);
await page.evaluate(()=>{
const h=globalThis.LCEWebIggy, result={calls:[],panels:[]};
globalThis.__lce96=result;let cur=null;
const b=h.beginCall.bind(h),a=h.pushArg.bind(h),f=h.finishCall.bind(h),s=h.setNativeScale9Panel.bind(h);
h.beginCall=(id,path,method)=>{cur={id,path,method,args:[]};return b(id,path,method)};
h.pushArg=value=>{if(cur)cur.args.push(String(value).slice(0,900));return a(value)};
h.finishCall=()=>{if(cur&&/Tip|Label|Title|Progress/i.test(cur.path+' '+cur.method)){result.calls.push(cur);if(result.calls.length>800)result.calls.shift()}cur=null;return f()};
h.setNativeScale9Panel=(...args)=>{if(String(args[1]).includes('tip'))result.panels.push(args);return s(...args)};
});
await page.mouse.click(642,310);await delay(1500);
await page.mouse.click(642,610);
const result={};
for (const [name,ms] of [['progress-0',300],['progress-1',700],['progress-2',1100],['progress-3',2200],['progress-4',4100]]){await delay(ms);result[name]=await snap(name)}
fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify({result,errors},null,2));
console.log('LCE96_DIAG '+JSON.stringify({result,errors}).slice(0,29000));
}catch(e){errors.push(String(e.stack||e));console.log('LCE96_DIAG_ERROR '+errors.join('\n'));process.exitCode=1;}
finally{await browser.close().catch(()=>{});}
})();