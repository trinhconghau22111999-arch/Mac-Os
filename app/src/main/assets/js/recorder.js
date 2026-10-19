/* Nạp lười: mã app "recorder". Không chạy trực tiếp: index.html (lazyBuild) lấy nội dung hàm bọc rồi eval trong phạm vi của nó, nên dùng chung mọi biến của index.html. */
window.__LZ=window.__LZ||{};
window.__LZ.recorder=function(){
/* ---------- Ghi âm ---------- */
const RECDB=(()=>{
  let db=null, mem=[], useMem=false;
  const open=()=>new Promise(res=>{
    if(db||useMem)return res();
    try{
      const q=indexedDB.open('dm-rec',1);
      q.onupgradeneeded=()=>{q.result.createObjectStore('r',{keyPath:'id'})};
      q.onsuccess=()=>{db=q.result;res()};
      q.onerror=()=>{useMem=true;res()};
      q.onblocked=()=>{useMem=true;res()};
    }catch(e){useMem=true;res()}
  });
  const tx=(mode,fn)=>new Promise(res=>{
    try{const t=db.transaction('r',mode);const r=fn(t.objectStore('r'));t.oncomplete=()=>res(r&&r.result);t.onerror=()=>res(null);t.onabort=()=>res(null)}catch(e){res(null)}
  });
  return {
    memOnly:()=>useMem,
    async all(){await open();if(useMem)return mem.slice();return (await tx('readonly',s=>s.getAll()))||[]},
    async put(x){await open();if(useMem){const i=mem.findIndex(y=>y.id===x.id);if(i<0)mem.push(x);else mem[i]=x;return}await tx('readwrite',s=>s.put(x))},
    async del(id){await open();if(useMem){mem=mem.filter(y=>y.id!==id);return}await tx('readwrite',s=>s.delete(id))}
  };
})();
function buildRecorder(b,w){
  const PAUSE='<svg viewBox="0 0 24 24"><rect x="6" y="5" width="4" height="14" rx="1" fill="currentColor"/><rect x="14" y="5" width="4" height="14" rx="1" fill="currentColor"/></svg>', PLAY='<svg viewBox="0 0 24 24"><path d="M8 5.5v13l11-6.5z" fill="currentColor"/></svg>', RATES=[1,1.5,2,0.5];
  const fmt=ms=>{ms=Math.max(0,Math.floor(ms));return pad(Math.floor(ms/60000))+':'+pad(Math.floor(ms/1000)%60)+'.'+(Math.floor(ms/100)%10)};
  const rl=r=>(r===0.5?'0.5':String(r))+'x';
  b.innerHTML='<div class="rc"><div class="rcm"><div class="rcx"><h2>Máy ghi âm</h2><div class="rcw"><canvas class="rcv"></canvas></div><div class="rctime">00:00.0</div><div class="rcbt"><button type="button" class="rcb rcp" aria-label="Tạm dừng" disabled>'+PAUSE+'</button><button type="button" class="rcb rcr" aria-label="Ghi âm"><i></i></button></div></div><div class="rcs"><div class="rch">Bản ghi đã lưu</div><div class="rcl"></div></div></div></div>';
  const root=$('.rc',b), cv=$('.rcv',b), cx=cv.getContext('2d'), tm=$('.rctime',b), bp=$('.rcp',b), br=$('.rcr',b), ls=$('.rcl',b);
  let st='idle', stream=null, mr=null, ac=null, an=null, buf=null, acc=0, t0=0, tmr=null, raf=0, lastP=0, hist=[], items=[], cur=null, menuIt=null;
  const menu=document.createElement('div'); menu.className='rcmenu'; menu.hidden=true;
  menu.innerHTML='<button type="button" data-a="ren">Đổi tên</button><button type="button" data-a="shr">Chia sẻ</button><button type="button" class="dg" data-a="del">Xoá bản ghi</button>';
  root.appendChild(menu);
  const warn=(t,m)=>notify({app:'Ghi âm',title:t,body:m,icon:'recorder'});
  const elapsed=()=>acc+(st==='rec'?Date.now()-t0:0);

  /* sóng âm */
  function size(){const r=cv.getBoundingClientRect(),d=window.devicePixelRatio||1,W=Math.max(1,Math.round(r.width*d)),H=Math.max(1,Math.round(r.height*d));if(cv.width!==W||cv.height!==H){cv.width=W;cv.height=H}return d}
  function draw(){
    raf=0; if(!w.isConnected)return;
    const d=size(), W=cv.width, H=cv.height, bw=3*d, gap=2*d, step=bw+gap, max=Math.max(1,Math.floor((W-40*d)/step));
    cx.clearRect(0,0,W,H);
    if(an&&st==='rec'){
      if(!buf||buf.length!==an.fftSize)buf=new Uint8Array(an.fftSize);
      an.getByteTimeDomainData(buf);
      const t=performance.now();
      if(t-lastP>55){lastP=t;let m=0;for(let i=0;i<buf.length;i++){const v=Math.abs(buf[i]-128);if(v>m)m=v}hist.push(m/128);while(hist.length>max)hist.shift()}
    }
    cx.strokeStyle='rgba(180,76,255,.35)'; cx.lineWidth=d; cx.beginPath(); cx.moveTo(20*d,H/2); cx.lineTo(W-20*d,H/2); cx.stroke();
    cx.fillStyle='#b44cff'; cx.shadowColor='rgba(180,76,255,.8)'; cx.shadowBlur=8*d;
    const x0=W-20*d-hist.length*step;
    hist.forEach((a,i)=>{const h=Math.max(2*d,Math.min(1,a*2.2)*(H-24*d));cx.fillRect(x0+i*step,(H-h)/2,bw,h)});
    cx.shadowBlur=0;
    if(st!=='idle')raf=requestAnimationFrame(draw);
  }
  function ui(){
    br.classList.toggle('on',st!=='idle');
    br.setAttribute('aria-label',st==='idle'?'Ghi âm':'Dừng và lưu');
    bp.disabled=st==='idle';
    bp.innerHTML=st==='pause'?PLAY:PAUSE;
    bp.setAttribute('aria-label',st==='pause'?'Tiếp tục':'Tạm dừng');
  }

  /* ghi */
  async function start(){
    if(st!=='idle')return;
    if(!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia&&window.MediaRecorder)){warn('Không ghi âm được','Thiết bị này không hỗ trợ ghi âm trong ứng dụng.');return}
    let s; try{s=await navigator.mediaDevices.getUserMedia({audio:true})}catch(e){warn('Không mở được micro',(e&&e.name==='NotFoundError'?'Không tìm thấy micro hoặc micro đang được app khác dùng.':e&&e.name==='NotReadableError'?'Micro đang bị app khác chiếm dùng.':'Hãy cấp quyền Micro cho Desktop Mini rồi thử lại.')+(e&&e.name?' ('+e.name+')':''));return}
    if(!w.isConnected||st!=='idle'){s.getTracks().forEach(t=>t.stop());return}
    const types=['audio/webm;codecs=opus','audio/mp4','audio/webm'];
    const mt=types.find(t=>MediaRecorder.isTypeSupported&&MediaRecorder.isTypeSupported(t))||'';
    const cs=[]; let rec;
    try{rec=mt?new MediaRecorder(s,{mimeType:mt}):new MediaRecorder(s)}catch(e){s.getTracks().forEach(t=>t.stop());warn('Không ghi âm được','Không khởi tạo được bộ ghi âm.');return}
    stream=s; mr=rec; hist=[];
    rec.ondataavailable=e=>{if(e.data&&e.data.size)cs.push(e.data)};
    rec.onstop=()=>save(rec,cs,mt);
    rec.start(250);
    try{ac=new (window.AudioContext||window.webkitAudioContext)();an=ac.createAnalyser();an.fftSize=512;ac.createMediaStreamSource(s).connect(an)}catch(e){an=null}
    acc=0; t0=Date.now(); st='rec';
    tmr=setInterval(()=>{if(w.isConnected)tm.textContent=fmt(elapsed())},100);
    if(!raf)draw();
    ui();
  }
  function stopRec(){
    if(st==='idle'||!mr)return;
    const dur=elapsed(); mr._dur=dur;
    st='idle'; clearInterval(tmr); tmr=null;
    try{if(mr.state!=='inactive')mr.stop()}catch(e){}
    try{stream.getTracks().forEach(t=>t.stop())}catch(e){}
    try{if(ac)ac.close()}catch(e){}
    ac=null; an=null; stream=null; mr=null; hist=[];
    if(w.isConnected){tm.textContent='00:00.0';ui();if(!raf)draw()}
  }
  function togglePause(){
    if(!mr)return;
    try{
      if(st==='rec'){mr.pause();acc+=Date.now()-t0;st='pause'}
      else if(st==='pause'){mr.resume();t0=Date.now();st='rec'}
    }catch(e){}
    ui();
  }
  async function save(rec,cs,mt){
    const type=rec.mimeType||mt||'audio/webm', blob=new Blob(cs,{type:type});
    if(!blob.size)return;
    const n=(jget('dm-recn',0)||0)+1; jset('dm-recn',n);
    const d=new Date();
    const it={id:Date.now(),name:'Bản ghi '+n+' — '+pad(d.getHours())+':'+pad(d.getMinutes()),dur:rec._dur||0,mime:type,blob:blob,t:Date.now()};
    items.unshift(it);
    await RECDB.put(it);
    if(w.isConnected)renderList();
  }

  /* danh sách và phát */
  const rowOf=it=>ls.querySelector('[data-id="'+it.id+'"]');
  function renderList(){
    if(!items.length){ls.innerHTML='<div class="rce">Chưa có bản ghi nào</div>';return}
    ls.innerHTML=items.map(it=>'<div class="rci" data-id="'+it.id+'"><div class="rcn">'+esc(it.name)+'</div><div class="rcc"><button type="button" class="rcpl" aria-label="Phát">'+PLAY+'</button><div class="rcpr"><b><i></i></b></div><span class="rctm">'+fmt(it.dur)+'</span><button type="button" class="rcsp" aria-label="Tốc độ phát">'+rl(it.sp||1)+'</button><button type="button" class="rcmn" aria-label="Tuỳ chọn">&#8942;</button></div></div>').join('');
    if(cur){cur.el=rowOf(cur.it);paint()}
  }
  function paint(){
    if(!cur||!cur.el||!cur.el.isConnected)return;
    const a=cur.a, d=(cur.it.dur/1000)||1, f=Math.min(1,a.currentTime/d);
    $('.rcpr i',cur.el).style.width=(f*100)+'%';
    $('.rctm',cur.el).textContent=fmt((a.paused&&a.currentTime===0)?cur.it.dur:a.currentTime*1000);
    $('.rcpl',cur.el).innerHTML=a.paused?PLAY:PAUSE;
  }
  function stopPlay(){
    if(!cur)return;
    const c=cur; cur=null;
    try{c.a.pause()}catch(e){} URL.revokeObjectURL(c.url);
    const el=c.el;
    if(el&&el.isConnected){$('.rcpr i',el).style.width='0';$('.rctm',el).textContent=fmt(c.it.dur);$('.rcpl',el).innerHTML=PLAY}
  }
  function ensure(it){
    if(cur&&cur.it===it)return cur;
    stopPlay();
    const url=URL.createObjectURL(it.blob), a=new Audio(url);
    a.playbackRate=it.sp||1;
    cur={it:it,a:a,url:url,el:rowOf(it)};
    a.ontimeupdate=paint; a.onplay=paint; a.onpause=paint;
    a.onended=()=>{a.currentTime=0;paint()};
    return cur;
  }
  function toggle(it){const c=ensure(it);if(c.a.paused){const p=c.a.play();if(p&&p.catch)p.catch(()=>{})}else c.a.pause()}
  function seek(it,e){
    const bar=e.target.closest('.rcpr'), r=bar.getBoundingClientRect(), f=Math.max(0,Math.min(1,(e.clientX-r.left)/r.width));
    const c=ensure(it); try{c.a.currentTime=f*(it.dur/1000)}catch(x){} paint();
  }
  function speed(it){
    it.sp=RATES[(RATES.indexOf(it.sp||1)+1)%RATES.length];
    if(cur&&cur.it===it)cur.a.playbackRate=it.sp;
    const el=rowOf(it); if(el)$('.rcsp',el).textContent=rl(it.sp);
  }

  /* menu ⋮ */
  function openMenu(it,btn){
    menuIt=it; menu.hidden=false;
    const rr=root.getBoundingClientRect(), br2=btn.getBoundingClientRect();
    menu.style.left=Math.max(8,Math.min(rr.width-198,br2.right-rr.left-190))+'px';
    const mh=menu.offsetHeight||134; let top=br2.bottom-rr.top+4; if(top+mh>rr.height-8)top=Math.max(8,br2.top-rr.top-mh-4);
    menu.style.top=top+'px';
  }
  function rename(it){
    const row=rowOf(it); if(!row)return;
    const n=$('.rcn',row), inp=document.createElement('input');
    inp.className='rcin'; inp.value=it.name; inp.maxLength=40; inp.setAttribute('aria-label','Tên bản ghi'); inp.autocomplete='off';
    n.replaceWith(inp); inp.focus(); inp.select();
    let done=false;
    const ok=()=>{if(done)return;done=true;const v=inp.value.trim();if(v&&v!==it.name){it.name=v;RECDB.put(it)}renderList()};
    inp.addEventListener('keydown',e=>{e.stopPropagation();if(e.key==='Enter')ok();else if(e.key==='Escape'){done=true;renderList()}});
    inp.addEventListener('blur',ok);
    inp.addEventListener('pointerdown',e=>e.stopPropagation());
  }
  function share(it){
    const A=window.AndroidLauncher, ext=/mp4/.test(it.mime)?'m4a':'webm', nm=it.name.replace(/[\\/:*?"<>|]+/g,'_');
    if(A&&A.shareAudio){
      const fr=new FileReader();
      fr.onload=()=>{let ok=false;try{ok=A.shareAudio(fr.result,nm,it.mime)}catch(e){}if(!ok)warn('Không chia sẻ được','Thử lại sau.')};
      fr.readAsDataURL(it.blob); return;
    }
    try{
      const f=new File([it.blob],nm+'.'+ext,{type:it.mime});
      if(navigator.canShare&&navigator.canShare({files:[f]})){navigator.share({files:[f]}).catch(()=>{});return}
    }catch(e){}
    const a=document.createElement('a'); a.href=URL.createObjectURL(it.blob); a.download=nm+'.'+ext; a.click();
  }
  function remove(it){
    if(cur&&cur.it===it)stopPlay();
    items=items.filter(x=>x!==it); RECDB.del(it.id); renderList();
  }

  br.addEventListener('click',()=>{st==='idle'?start():stopRec()});
  bp.addEventListener('click',togglePause);
  ls.addEventListener('click',e=>{
    const row=e.target.closest('.rci'); if(!row)return;
    const it=items.find(x=>String(x.id)===row.dataset.id); if(!it)return;
    if(e.target.closest('.rcpl'))toggle(it);
    else if(e.target.closest('.rcsp'))speed(it);
    else if(e.target.closest('.rcmn'))openMenu(it,e.target.closest('.rcmn'));
    else if(e.target.closest('.rcpr'))seek(it,e);
  });
  menu.addEventListener('click',e=>{
    const a=e.target.closest('[data-a]'); if(!a||!menuIt)return;
    const it=menuIt; menu.hidden=true; menuIt=null;
    if(a.dataset.a==='ren')rename(it); else if(a.dataset.a==='shr')share(it); else remove(it);
  });
  b.addEventListener('pointerdown',e=>{if(!menu.hidden&&!e.target.closest('.rcmenu')&&!e.target.closest('.rcmn'))menu.hidden=true},true);
  ls.addEventListener('scroll',()=>{menu.hidden=true});

  renderList(); draw();
  RECDB.all().then(a=>{items=a.sort((x,y)=>y.t-x.t);if(w.isConnected)renderList()});
  return {destroy(){
    if(st!=='idle')stopRec();
    clearInterval(tmr); cancelAnimationFrame(raf); stopPlay();
  }};
}
};
