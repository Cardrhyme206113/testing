'use strict';
const fs=require('fs'),path=require('path'),{chromium}=require('playwright');
const OUT='fix77-review';fs.mkdirSync(OUT,{recursive:true});
const pause=n=>new Promise(r=>setTimeout(r,n));
const report={states:[],errors:[],buttons:[],inventory:{}};
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--enable-webgl','--enable-unsafe-swiftshader','--use-gl=angle','--use-angle=swiftshader','--disable-dev-shm-usage','--lang=en-US']});
 const page=await browser.newPage({viewport:{width:1280,height:800},locale:'en-US'});
 let events=[];
 page.on('console',m=>{if(events.length<15000)events.push(m.text());});
 page.on('pageerror',e=>report.errors.push(String(e)));
 page.on('dialog',d=>d.dismiss().catch(()=>{}));
 const state=()=>page.evaluate(()=>({root:globalThis.LCEWebIggy?.debugState?.().root,players:(globalThis.LCEWebIggy?.debugState?.().players||[]).filter(x=>x.visible).map(x=>({id:x.id,name:x.filename})),panels:[...document.querySelectorAll('.lce-webiggy-scale9-panel-overlay')].filter(e=>getComputedStyle(e).visibility==='visible').map(e=>e.dataset.lcePanelKey)}));
 const screenshot=async name=>page.screenshot({path:path.join(OUT,name+'.png'),timeout:30000});
 const open=async k=>{
  let r=await page.evaluate(k=>Module._lce_web_test_open_ui(k),k);if(r!==1)return r;
  for(let i=0;i<70;i++){await pause(200);const y=await page.evaluate(()=>Module._lce_web_test_last_ui_result());if(y!==-99)return y;}return -99;
 };
 const boot=async suffix=>{await page.goto('http://127.0.0.1:8000/lce_web_demo.html?lce_ui_test=1&run='+suffix,{waitUntil:'domcontentloaded',timeout:120000});await pause(34000);};
 try{
  await boot('settings');
  report.states.push({case:'create',open:await open(12)});
  await pause(1000);await page.mouse.click(642,558);
  for(let i=0;i<110;i++){await pause(300);const s=await state();if(s.players.some(x=>x.name==='LaunchMoreOptionsMenu720.swf'))break;}
  await pause(1500);await screenshot('01-world-options-existing-divider');
  const original=await page.evaluate(()=>{
   const h=globalThis.LCEWebIggy;
   return ['WorldOptions','CheckboxHostPrivileges','CheckboxStructures','CheckboxFlatWorld','CheckboxBonusChest'].map(name=>({name,y:h.getValue(19,name,'y'),stage:h.getValue(19,name,'__lce_stage_y'),h:h.getValue(19,name,'height')}));
  });
  report.states.push({case:'options-original',controls:original,state:await state()});
  const n=await page.evaluate(()=>globalThis.LCEWebIggy.setValue(19,'WorldOptions','y',322));
  await pause(2000);await screenshot('02-world-options-header-up14');
  report.states.push({case:'options-header-up14',set:n,controls:await page.evaluate(()=>{
   const h=globalThis.LCEWebIggy;return ['WorldOptions','CheckboxStructures','CheckboxBonusChest'].map(name=>({name,y:h.getValue(19,name,'y')}));
  })});
  const m=await page.evaluate(()=>globalThis.LCEWebIggy.setValue(19,'WorldOptions','y',316));
  await pause(1600);await screenshot('03-world-options-header-up20');
  report.states.push({case:'options-header-up20',set:m});
  await boot('inventory');
  report.states.push({case:'create2',open:await open(12)});
  await pause(800);await page.mouse.click(642,608);
  await pause(12500);
  let started=false;
  for(let i=0;i<85;i++){
    const o=await open(20);
    if(o===0||o===1){started=true;report.inventory.startProbe=o;break;}
    await pause(1750);
  }
  report.inventory.worldStarted=started;
  if(started){
    await pause(2300);await screenshot('04-world-before-inventory');
    await page.keyboard.press('e');await pause(4000);
    report.inventory.afterKeyboard=await state();
    await screenshot('05-inventory-4s');
    await pause(6500);report.inventory.later=await state();
    await screenshot('06-inventory-10sec');
    const menu=report.inventory.later.players.find(x=>x.name.includes('InventoryMenu'));
    if(menu){
      report.inventory.properties=await page.evaluate(id=>{
        const h=globalThis.LCEWebIggy;
        let paths=['MainPanel','Inventory','InventoryPanel','InventoryMenu','Crafting','IggyInventory','MenuPanel','Slots','InventorySlots','InventoryWindow','Header','Slot0','iggy_slot_0','iggy_player','iggy_slot_9'];
        let keys=['visible','x','y','width','height','__lce_stage_y','__lce_stage_width'];
        let rows=[];
        for(const p of paths){
          const record={path:p};
          for(const prop of keys){
           try{const n=h.getValue(id,p,prop);if(n!==undefined&&n!==null)record[prop]=n;}catch(e){}
          }
          if(Object.keys(record).length>1)rows.push(record);
        }
        return rows;
      },menu.id);
    }
    report.inventory.recentConsole=events.filter(x=>/InventoryMenu|custom regions|container-panel|scale9|slot/i.test(x)).slice(-38);
  }
 }catch(e){report.errors.push('HARNESS: '+String(e.stack||e));}
 finally{fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));console.log('FIX77_RESULT '+JSON.stringify(report).slice(0,52000));await browser.close().catch(()=>{});}
})();
