import xterm from './xterm.js';
const bridge=window.QuestTerminal;
const baseTheme={background:'#00000000',foreground:'#b9e8c7',cursor:'#aff4c2',selectionBackground:'#8edda64d',black:'#19271f',red:'#df8787',green:'#8cdda5',yellow:'#d2ce8a',blue:'#8aafd4',magenta:'#bd9bcf',cyan:'#88cdc4',white:'#d7e6dd',brightBlack:'#718779',brightRed:'#ffa0a0',brightGreen:'#a6efba',brightYellow:'#eeeaa4',brightBlue:'#a6c7ed',brightMagenta:'#d4b7e4',brightCyan:'#a7e5dc',brightWhite:'#effff4'};
const term=new xterm.Terminal({allowTransparency:true,cursorBlink:true,cursorStyle:'block',fontFamily:'"Droid Sans Mono",monospace',fontSize:17,lineHeight:1.12,scrollback:5000,convertEol:false,theme:baseTheme});
const fit=new xterm.FitAddon();term.loadAddon(fit);term.open(document.querySelector('#terminal'));
let seq=0,writing=false,fontSize=17;
function input(s){const bytes=new TextEncoder().encode(s);for(let i=0;i<bytes.length;i+=4096)bridge.input(btoa(String.fromCharCode(...bytes.slice(i,i+4096))));}
term.onData(input);
term.onResize(({cols,rows})=>bridge.resize(cols,rows));
new ResizeObserver(()=>{try{fit.fit();}catch{}}).observe(document.querySelector('#terminal'));
function poll(){
 if(writing)return;
 try{
  const data=JSON.parse(bridge.poll(seq));
  if(data.error){document.querySelector('#state').textContent=data.error;return;}
  seq=data.seq;
  document.querySelector('#label').textContent=data.label;
  document.querySelector('#state').textContent=data.running?'local · connected':'shell exited';
  if(data.data){writing=true;term.write(Uint8Array.from(atob(data.data),c=>c.charCodeAt(0)),()=>writing=false);}
 }catch(e){document.querySelector('#state').textContent='Terminal error: '+e.message;}
}
setInterval(poll,35);
function focus(){term.focus();}
document.querySelectorAll('[data-key]').forEach(b=>b.addEventListener('click',()=>{input(JSON.parse('"'+b.dataset.key+'"'));focus();}));
document.querySelector('#keyboard').onclick=()=>{focus();bridge.keyboard();};
document.querySelector('#new-window').onclick=()=>bridge.newWindow();
document.querySelector('#close-window').onclick=()=>bridge.closeWindow();
document.querySelector('#restart').onclick=()=>{if(!confirm('End commands in this terminal and start a fresh shell?'))return;const error=bridge.restart();if(error)term.writeln('\r\n'+error);focus();};
document.querySelector('#end-session').onclick=()=>{if(confirm('End this terminal session and its running commands?')){const error=bridge.endSession();if(error)term.writeln('\r\n'+error);}};
function size(delta){fontSize=Math.min(30,Math.max(11,fontSize+delta));term.options.fontSize=fontSize;fit.fit();}
document.querySelector('#smaller').onclick=()=>size(-1);document.querySelector('#larger').onclick=()=>size(1);
function opacity(value){document.querySelector('#panel').style.setProperty('--opacity',value/100);document.querySelector('#opacity-value').textContent=value+'%';}
const slider=document.querySelector('#opacity');
const brightness=document.querySelector('#brightness'),contrast=document.querySelector('#contrast');
try{const prefs=JSON.parse(bridge.preferences());slider.value=prefs.opacity??82;brightness.value=prefs.brightness??100;contrast.value=prefs.contrast??100;}catch{}
opacity(slider.value);slider.oninput=()=>opacity(slider.value);slider.onchange=()=>bridge.opacity(Number(slider.value));
function appearance(){
 const b=Number(brightness.value)/100,c=Number(contrast.value)/100;
 // Change foreground colors and stroke visibility, never the panel's alpha.
 function color(hex,lift=0){
  const rgb=hex.slice(1).match(/../g).map(v=>parseInt(v,16));
  return '#'+rgb.map(v=>{
   v=b<=1?v*b:v+(255-v)*(b-1)*1.75;
   v=lift>=0?v+(255-v)*lift:v*(1+lift);
   return Math.round(Math.max(0,Math.min(255,v))).toString(16).padStart(2,'0');
  }).join('');
 }
 const theme={...baseTheme};
 for(const key of Object.keys(theme))
  if(key!=='background'&&key!=='selectionBackground')theme[key]=color(baseTheme[key],(c-1)*.18);
 term.options.theme=theme;
 const panel=document.querySelector('#panel');
 panel.style.setProperty('--text',color('#c2dfcc',(c-1)*.18));
 panel.style.setProperty('--muted',color('#81a98f',(c-1)*.55));
 panel.style.setProperty('--accent',color('#91e6af',(c-1)*.18));
 panel.style.setProperty('--stroke-rgb',color('#82c39c').slice(1).match(/../g).map(v=>parseInt(v,16)).join(','));
 panel.style.setProperty('--line-alpha',Math.min(.85,.19*Math.pow(c,1.6)));
 panel.style.setProperty('--edge-alpha',Math.min(1,.34*Math.pow(c,1.6)));
 document.querySelector('#brightness-value').textContent=brightness.value+'%';
 document.querySelector('#contrast-value').textContent=contrast.value+'%';
}
brightness.oninput=contrast.oninput=appearance;
brightness.onchange=contrast.onchange=()=>bridge.appearance(Number(brightness.value),Number(contrast.value));
appearance();
requestAnimationFrame(()=>{fit.fit();poll();focus();});
