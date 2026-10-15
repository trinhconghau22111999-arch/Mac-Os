/* Nạp lười: mã app "photos". Không chạy trực tiếp: index.html (lazyBuild) lấy nội dung hàm bọc rồi eval trong phạm vi của nó, nên dùng chung mọi biến của index.html. */
window.__LZ=window.__LZ||{};
window.__LZ.photos=function(){
let SCN=0;
function scene(i){
  if(PH[i%PH.length].pid)return '<img class="rp" data-pid="'+PH[i%PH.length].pid+'" alt="" style="width:100%;height:100%;object-fit:cover;display:block;background:#8e8e93">';
  const p=PH[i%PH.length], u='ph'+(SCN++);
  return '<svg viewBox="0 0 160 120" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="'+u+'" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="'+p[0]+'"/><stop offset="1" stop-color="'+p[1]+'"/></linearGradient></defs><rect width="160" height="120" fill="url(#'+u+')"/><circle cx="'+(40+(i*37)%90)+'" cy="'+(34+(i*11)%22)+'" r="'+(9+(i%3)*3)+'" fill="'+p[2]+'" opacity=".95"/><path d="M0 '+(84-(i%4)*4)+' Q40 '+(60+(i%5)*3)+' 80 80 T160 '+(72-(i%3)*5)+' V120 H0Z" fill="'+p[3]+'"/><path d="M0 100 Q50 '+(82-(i%4)*3)+' 100 98 T160 92 V120 H0Z" fill="'+p[4]+'"/></svg>';
}
function buildPhotos(b,w,arg){
  let fav=jget('dm-pfav',[]), tab='all', view=-1;
  b.innerHTML='<div class="ph"><nav class="side"><h4>Thư viện</h4></nav><div class="main"><div class="bar"><strong class="t pht"></strong><span class="grow"></span><span class="cn phc"></span></div><div class="pgs"><div class="pgd"></div></div></div><div class="pv" hidden></div></div>';
  const side=$('.side',b), pgs=$('.pgs',b), pgd=$('.pgd',b), pv=$('.pv',b), pht=$('.pht',b), phc=$('.phc',b);
  let pio=null, ptq=[], ptb=false, pgShown=0;
  [['all','Tất cả ảnh'],['fav','Yêu thích'],['cam','Ảnh chụp'],['shot','Ảnh chụp màn hình']].forEach(x=>{
    const s=document.createElement('button'); s.className='si'; s.dataset.t=x[0]; s.innerHTML='<span>'+x[1]+'</span>';
    s.addEventListener('click',()=>{tab=x[0];view=-1;pgShown=0;pgs.scrollTop=0;render()}); side.appendChild(s);
  });
  const TABN={all:'Tất cả ảnh',fav:'Yêu thích',cam:'Ảnh chụp',shot:'Ảnh chụp màn hình'}, TABE={all:'Chưa có ảnh nào',fav:'Chưa có ảnh yêu thích',cam:'Chưa có ảnh chụp',shot:'Chưa có ảnh chụp màn hình'};
  const ids=()=>PH.map((_,i)=>i).filter(i=>tab==='all'||(tab==='fav'?fav.indexOf(i)>=0:PH[i].k===tab));
  /* Lưới ảnh dạng thư viện như app Tệp: ô vuông, ảnh nạp từng phần khi cuộn tới */
  function ptPump(){
    if(ptb)return; const j=ptq.shift(); if(!j)return; ptb=true;
    setTimeout(()=>{
      if(j.isConnected&&j.__vis){
        let d=''; try{d=PHA().getPhotoData(+j.dataset.pid,240)}catch(e){}
        if(d){j.src=d;j.classList.add('ld')}
      }else j.__q=0;
      ptb=false; ptPump();
    },0);
  }
  function renderGrid(){
    $$('.si',side).forEach(s=>s.setAttribute('aria-current',String(s.dataset.t===tab)));
    const l=ids(); pht.textContent=TABN[tab]||'Tất cả ảnh'; phc.textContent=l.length+' ảnh';
    const st=pgs.scrollTop;
    if(pio){pio.disconnect();pio=null} ptq.length=0; ptb=false; pgs.onscroll=null;
    if(!l.length){pgd.innerHTML='<div class="none">'+(TABE[tab]||TABE.all)+'</div>';return}
    pgd.innerHTML='';
    const tile=i=>{
      const p=PH[i], real=!!p.pid;
      return '<button class="pt" data-i="'+i+'" aria-label="'+esc(String(p[5]||''))+'">'+(real?'<img alt="" data-pid="'+p.pid+'">':scene(i))+(fav.indexOf(i)>=0?'<em>♥</em>':'')+'</button>';
    };
    pio=new IntersectionObserver(es=>es.forEach(e=>{
      const el=e.target; el.__vis=e.isIntersecting;
      if(e.isIntersecting&&!el.__q&&!el.classList.contains('ld')){el.__q=1;ptq.push(el);ptPump()}
    }),{root:pgs,rootMargin:'240px'});
    let shown=0;
    const addChunk=to=>{
      const end=Math.min(l.length,to); let h='';
      for(let k=shown;k<end;k++)h+=tile(l[k]);
      pgd.insertAdjacentHTML('beforeend',h); shown=end; pgShown=Math.max(pgShown,shown);
      if(pio)$$('img[data-pid]:not([data-o])',pgd).forEach(im=>{im.dataset.o='1';pio.observe(im)});
    };
    addChunk(Math.max(240,pgShown));
    if(st)pgs.scrollTop=st;
    pgs.onscroll=()=>{if(shown<l.length&&pgs.scrollTop+pgs.clientHeight>pgs.scrollHeight-700)addChunk(shown+240)};
  }
  pgd.addEventListener('click',e=>{const x=e.target.closest('.pt');if(x){view=+x.dataset.i;renderViewer()}});
  function render(){renderGrid();renderViewer()}

  /* ---------- Trình xem + chỉnh sửa ảnh ---------- */
  const PHA=()=>window.AndroidLauncher||null, RED='#ff3b30';
  let ED=null;
  const TOOLS=[
    ['crop','Cắt','Cắt ảnh','<path d="M6 2v14a2 2 0 0 0 2 2h14M2 6h14a2 2 0 0 1 2 2v14"/>'],
    ['rot','Xoay','Xoay ảnh','<path d="M20 12a8 8 0 1 1-2.6-5.9M20 4v5h-5"/>'],
    ['flip','Lật','Lật ảnh','<path d="M12 3v18M8 7L3 12l5 5V7zM16 7l5 5-5 5V7z"/>'],
    ['rect','Vuông','Đánh dấu vuông','<rect x="4" y="5" width="16" height="14" rx="1.5"/>'],
    ['oval','Tròn','Đánh dấu tròn','<circle cx="12" cy="12" r="8"/>'],
    ['pen','Vẽ','Vẽ','<path d="M4 20l1-4L16.5 4.5a2 2 0 0 1 3 3L8 19l-4 1zM14 7l3 3"/>'],
    ['del','Xóa','Xóa ảnh','<path d="M5 7h14M10 7V5h4v2M7 7l1 12h8l1-12M10 11v5M14 11v5"/>'],
    ['share','Chia sẻ','Chia sẻ ảnh','<path d="M12 15V3M8 7l4-4 4 4M5 12v7a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-7"/>']
  ];
  const cloneCv=c=>{const n=document.createElement('canvas');n.width=c.width;n.height=c.height;n.getContext('2d').drawImage(c,0,0);return n};
  function dispRect(){
    const r=ED.cv.getBoundingClientRect(), s=Math.min(r.width/ED.cv.width,r.height/ED.cv.height), dw=ED.cv.width*s, dh=ED.cv.height*s;
    return {x:r.left+(r.width-dw)/2,y:r.top+(r.height-dh)/2,w:dw,h:dh,s:s};
  }
  const toImg=e=>{const d=dispRect();return [(e.clientX-d.x)/d.s,(e.clientY-d.y)/d.s]};
  const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
  const edited=()=>!!ED&&(ED.edited||ED.hist.length>0);
  function pushHist(base){ED.hist.push(base||cloneCv(ED.cv));if(ED.hist.length>6)ED.hist.shift();ED.edited=true}
  function restore(c){ED.cv.width=c.width;ED.cv.height=c.height;ED.ctx.drawImage(c,0,0)}
  function undo(){if(!ED||!ED.hist.length)return;exitMode();restore(ED.hist.pop());ED.edited=ED.hist.length>0;syncBar()}
  function revert(){if(!ED)return;exitMode();restore(ED.orig);ED.hist=[];ED.edited=false;syncBar()}

  function renderViewer(){
    ED=null;
    if(view<0){pv.hidden=true;return}
    pv.hidden=false;
    pv.innerHTML='<div class="pvh"><button class="btn pvx">Xong</button><span class="grow"></span><b>'+PH[view][5]+'</b><span class="grow"></span><button class="ib pvf" aria-label="Yêu thích" aria-pressed="'+(fav.indexOf(view)>=0)+'"><svg viewBox="0 0 16 16"><path d="M8 13.6S2.2 10.2 2.2 6.3A3 3 0 0 1 8 5a3 3 0 0 1 5.8 1.3C13.8 10.2 8 13.6 8 13.6z"/></svg></button></div>'+
      '<div class="pvm"><button class="ib pvp" aria-label="Ảnh trước"><svg viewBox="0 0 16 16"><path d="M10 3L5 8l5 5"/></svg></button><div class="pvi"><div class="pvl">Đang tải ảnh…</div></div><button class="ib pvn" aria-label="Ảnh sau"><svg viewBox="0 0 16 16"><path d="M6 3l5 5-5 5"/></svg></button></div>'+
      '<div class="pvmb" hidden></div>'+
      '<div class="pvt">'+TOOLS.map(t=>'<button type="button" data-t="'+t[0]+'" aria-label="'+t[2]+'" aria-pressed="false"><svg viewBox="0 0 24 24">'+t[3]+'</svg><span>'+t[1]+'</span></button>').join('')+'</div>';
    const l=ids(), pos=l.indexOf(view);
    $('.pvx',pv).addEventListener('click',()=>{view=-1;renderViewer()});
    $('.pvf',pv).addEventListener('click',e=>{const k=fav.indexOf(view);if(k>=0)fav.splice(k,1);else fav.push(view);jset('dm-pfav',fav);e.currentTarget.setAttribute('aria-pressed',String(fav.indexOf(view)>=0));renderGrid()});
    $('.pvp',pv).addEventListener('click',()=>{view=l[(pos-1+l.length)%l.length];renderViewer()});
    $('.pvn',pv).addEventListener('click',()=>{view=l[(pos+1)%l.length];renderViewer()});
    $('.pvt',pv).addEventListener('click',e=>{const b=e.target.closest('button[data-t]');if(b)tool(b.dataset.t)});
    $('.pvmb',pv).addEventListener('click',e=>{const b=e.target.closest('button[data-a]');if(b)barAct(b.dataset.a)});
    loadEd();
  }
  function loadEd(){
    const i=view, p=PH[i%PH.length], A=PHA(), box=$('.pvi',pv);
    const fail=()=>{if(view===i&&box.isConnected)box.innerHTML='<div class="pvl">Không mở được ảnh</div>'};
    const done=img=>{
      if(view!==i||!box.isConnected)return;
      const cv=document.createElement('canvas'); cv.width=img.naturalWidth||img.width; cv.height=img.naturalHeight||img.height; cv.className='pvc';
      cv.getContext('2d').drawImage(img,0,0);
      box.innerHTML=''; box.appendChild(cv);
      ED={cv:cv,ctx:cv.getContext('2d'),hist:[],mode:null,pid:p.pid||0,edited:false,orig:cloneCv(cv),crop:null};
      bindCanvas(); syncBar();
    };
    setTimeout(()=>{
      let src='';
      try{
        if(p.pid&&A&&A.getPhotoData)src=A.getPhotoData(+p.pid,1440);
        else src='data:image/svg+xml;charset=utf-8,'+encodeURIComponent(scene(i).replace('<svg ','<svg xmlns="http://www.w3.org/2000/svg" width="960" height="720" '));
      }catch(x){}
      if(!src){fail();return}
      const im=new Image(); im.onload=()=>done(im); im.onerror=fail; im.src=src;
    },30);
  }
  function setStroke(){ED.ctx.strokeStyle=RED;ED.ctx.lineWidth=Math.max(3,Math.round(Math.max(ED.cv.width,ED.cv.height)/190));ED.ctx.lineCap='round';ED.ctx.lineJoin='round'}
  function bindCanvas(){
    const cv=ED.cv; let st=null;
    const shape=()=>{
      const c=ED.ctx, x=Math.min(st.x0,st.x1), y=Math.min(st.y0,st.y1), w=Math.abs(st.x1-st.x0), h=Math.abs(st.y1-st.y0);
      setStroke(); c.beginPath();
      if(ED.mode==='rect')c.rect(x,y,w,h); else c.ellipse(x+w/2,y+h/2,Math.max(1,w/2),Math.max(1,h/2),0,0,Math.PI*2);
      c.stroke();
    };
    cv.addEventListener('pointerdown',e=>{
      if(!ED||ED.cv!==cv||['rect','oval','pen'].indexOf(ED.mode)<0)return;
      e.preventDefault(); try{cv.setPointerCapture(e.pointerId)}catch(x){}
      const p=toImg(e); st={x0:p[0],y0:p[1],x1:p[0],y1:p[1],base:cloneCv(cv),moved:false};
      if(ED.mode==='pen'){setStroke();ED.ctx.beginPath();ED.ctx.moveTo(p[0],p[1])}
    });
    cv.addEventListener('pointermove',e=>{
      if(!st)return; const p=toImg(e); st.x1=p[0]; st.y1=p[1];
      if(Math.abs(st.x1-st.x0)+Math.abs(st.y1-st.y0)>6)st.moved=true;
      if(ED.mode==='pen'){ED.ctx.lineTo(p[0],p[1]);ED.ctx.stroke();ED.ctx.beginPath();ED.ctx.moveTo(p[0],p[1])}
      else{ED.ctx.drawImage(st.base,0,0);if(st.moved)shape()}
    });
    const end=()=>{
      if(!st)return; const s=st; st=null;
      if(s.moved){pushHist(s.base)}else ED.ctx.drawImage(s.base,0,0);
      syncBar();
    };
    cv.addEventListener('pointerup',end); cv.addEventListener('pointercancel',end);
  }
  function exitMode(){
    if(!ED)return;
    if(ED.crop){if(ED.crop.ro)ED.crop.ro.disconnect();ED.crop.ov.remove();ED.crop=null}
    ED.mode=null;
  }
  function setMode(m){
    if(!ED)return; const same=ED.mode===m; exitMode();
    if(!same){ED.mode=m; if(m==='crop')startCrop()}
    syncBar();
    if(ED.crop){ED.crop.place();requestAnimationFrame(()=>{if(ED&&ED.crop)ED.crop.place()})}
  }
  function startCrop(){
    const box=$('.pvi',pv), W=ED.cv.width, H=ED.cv.height, r={x:W*.05,y:H*.05,w:W*.9,h:H*.9};
    const ov=document.createElement('div'); ov.className='cbox'; ov.innerHTML='<i data-h="tl"></i><i data-h="tr"></i><i data-h="bl"></i><i data-h="br"></i>'; box.appendChild(ov);
    const place=()=>{const d=dispRect(), br=box.getBoundingClientRect(); ov.style.left=(d.x-br.left+r.x*d.s)+'px'; ov.style.top=(d.y-br.top+r.y*d.s)+'px'; ov.style.width=(r.w*d.s)+'px'; ov.style.height=(r.h*d.s)+'px'};
    ED.crop={r:r,ov:ov,place:place,ro:null}; place();
    if(window.ResizeObserver){const ro=new ResizeObserver(()=>{if(ED&&ED.crop)ED.crop.place()}); ro.observe(box); ED.crop.ro=ro}
    let drag=null;
    ov.addEventListener('pointerdown',e=>{e.preventDefault();e.stopPropagation();try{ov.setPointerCapture(e.pointerId)}catch(x){}drag={h:e.target.dataset.h||'move',p:toImg(e),r0:{x:r.x,y:r.y,w:r.w,h:r.h}}});
    ov.addEventListener('pointermove',e=>{
      if(!drag)return; const p=toImg(e), dx=p[0]-drag.p[0], dy=p[1]-drag.p[1], o=drag.r0, MIN=24;
      if(drag.h==='move'){r.x=clamp(o.x+dx,0,W-o.w);r.y=clamp(o.y+dy,0,H-o.h)}
      else{
        let x1=o.x,y1=o.y,x2=o.x+o.w,y2=o.y+o.h;
        if(drag.h[1]==='l')x1=clamp(o.x+dx,0,x2-MIN); else x2=clamp(x2+dx,x1+MIN,W);
        if(drag.h[0]==='t')y1=clamp(o.y+dy,0,y2-MIN); else y2=clamp(y2+dy,y1+MIN,H);
        r.x=x1;r.y=y1;r.w=x2-x1;r.h=y2-y1;
      }
      place();
    });
    const up=()=>{drag=null}; ov.addEventListener('pointerup',up); ov.addEventListener('pointercancel',up);
  }
  function applyCrop(){
    if(!ED||!ED.crop)return; const r=ED.crop.r, x=Math.round(r.x), y=Math.round(r.y), w=Math.max(1,Math.round(r.w)), h=Math.max(1,Math.round(r.h));
    exitMode(); pushHist(); const c=cloneCv(ED.cv);
    ED.cv.width=w; ED.cv.height=h; ED.ctx.drawImage(c,x,y,w,h,0,0,w,h); syncBar();
  }
  function rotate(){
    exitMode(); pushHist(); const c=cloneCv(ED.cv), cv=ED.cv;
    cv.width=c.height; cv.height=c.width; ED.ctx.save(); ED.ctx.translate(cv.width,0); ED.ctx.rotate(Math.PI/2); ED.ctx.drawImage(c,0,0); ED.ctx.restore(); syncBar();
  }
  function flip(){
    exitMode(); pushHist(); const c=cloneCv(ED.cv);
    ED.ctx.save(); ED.ctx.translate(ED.cv.width,0); ED.ctx.scale(-1,1); ED.ctx.drawImage(c,0,0); ED.ctx.restore(); syncBar();
  }
  function tool(t){
    if(!ED&&t!=='del')return;
    if(t==='rot')rotate(); else if(t==='flip')flip();
    else if(t==='del')askDelete(); else if(t==='share')openShare();
    else setMode(t);
  }
  function barAct(a){
    if(a==='capply')applyCrop(); else if(a==='mdone'||a==='ccancel'){exitMode();syncBar()}
    else if(a==='undo')undo(); else if(a==='revert')revert(); else if(a==='save')saveCopy();
  }
  function syncBar(){
    const bar=$('.pvmb',pv); if(!bar)return;
    $$('.pvt button',pv).forEach(b=>b.setAttribute('aria-pressed',String(!!ED&&b.dataset.t===ED.mode)));
    if(!ED){bar.hidden=true;return}
    const m=ED.mode, ed=edited(), hint={crop:'Kéo khung để chọn vùng cắt',rect:'Kéo trên ảnh để vẽ khung vuông',oval:'Kéo trên ảnh để vẽ khung tròn',pen:'Vẽ tự do trên ảnh'}[m]||'';
    if(!m&&!ed){bar.hidden=true;return}
    let h=hint?'<span class="mh">'+hint+'</span>':'';
    if(m==='crop')h+='<button type="button" data-a="ccancel">Hủy</button><button type="button" class="pr" data-a="capply">Cắt</button>';
    else{
      if(ED.hist.length)h+='<button type="button" data-a="undo">Hoàn tác</button>';
      if(m)h+='<button type="button" data-a="mdone">Xong</button>';
      else if(ed)h+='<button type="button" data-a="revert">Về gốc</button><button type="button" class="pr" data-a="save">Lưu bản sao</button>';
    }
    bar.innerHTML=h; bar.hidden=false;
  }
  const jpg=()=>ED.cv.toDataURL('image/jpeg',.92);
  function saveCopy(){
    const A=PHA(); let ok=false;
    if(A&&A.saveImage){try{ok=A.saveImage(jpg())}catch(x){}}
    notify({app:'Ảnh',title:ok?'Đã lưu bản sao':'Chưa lưu được ảnh',body:ok?'Ảnh nằm trong thư mục Pictures/DesktopMini.':'Hãy cấp quyền truy cập ảnh/tệp rồi thử lại.',icon:'photos'});
    if(ok){loadPhotosSoon()}
  }
  function loadPhotosSoon(){setTimeout(()=>{if(window.nativeReload){window.nativeReload();renderGrid()}},600)}
  function openShare(){
    if(!ED)return; const A=PHA(), M=lkModal('Chia sẻ ảnh');
    const apps=[['com.zing.zalo','Zalo'],['com.facebook.katana','Facebook'],['com.facebook.orca','Messenger'],['com.google.android.gm','Gmail']].filter(x=>{try{return A&&A.hasPackage&&A.hasPackage(x[0])}catch(e){return false}});
    M.el.innerHTML='<div class="shg">'+apps.map(x=>'<button type="button" data-p="'+x[0]+'">'+x[1]+'</button>').join('')+'<button type="button" data-p="">Ứng dụng khác…</button></div>';
    M.el.addEventListener('click',e=>{const b=e.target.closest('button[data-p]');if(!b)return;M.close();doShare(b.dataset.p)});
  }
  function doShare(pkg){
    if(!ED)return; const A=PHA();
    if(A&&A.shareImage){
      const data=(edited()||!ED.pid)?jpg():'';
      let ok=false; try{ok=A.shareImage(pkg,ED.pid?+ED.pid:0,data)}catch(x){}
      if(!ok)notify({app:'Ảnh',title:'Không chia sẻ được',body:'Thử lại hoặc chọn ứng dụng khác.',icon:'photos'});
    }else ED.cv.toBlob(b=>{
      try{const f=new File([b],'anh.jpg',{type:'image/jpeg'});if(navigator.canShare&&navigator.canShare({files:[f]})){navigator.share({files:[f]}).catch(()=>{});return}}catch(x){}
      notify({app:'Ảnh',title:'Chưa chia sẻ được',body:'Chia sẻ ảnh chỉ có trong app Android.',icon:'photos'});
    },'image/jpeg',.92);
  }
  function askDelete(){
    const M=lkModal('Xóa ảnh này?');
    M.el.innerHTML='<div style="font-size:13px;opacity:.8;margin-bottom:12px">Ảnh sẽ vào Thùng rác và tự xóa hẳn sau 7 ngày.</div><button type="button" class="btn" data-x style="background:#ff3b30;color:#fff;padding:8px 26px">Xóa ảnh</button>';
    $('[data-x]',M.el).addEventListener('click',()=>{M.close();doDelete()});
  }
  function doDelete(){
    const A=PHA(), k=view, p=PH[k%PH.length];
    const fin=reload=>{
      if(reload&&window.nativeReload)window.nativeReload(); else PH.splice(k,1);
      fav=fav.filter(x=>x!==k).map(x=>x>k?x-1:x); jset('dm-pfav',fav);
      view=PH.length?Math.min(k,PH.length-1):-1; render();
      notify({app:'Ảnh',title:'Đã chuyển ảnh vào Thùng rác',body:'',icon:'photos'});
    };
    if(p.pid&&A&&A.deletePhoto){
      let r=0; try{r=A.deletePhoto(+p.pid)}catch(x){}
      if(r===1)fin(true);
      else if(r===2)window.nativeDelResult=ok=>{window.nativeDelResult=null;if(ok)fin(true)};
      else notify({app:'Ảnh',title:'Không xóa được ảnh',body:'Hãy cấp quyền "Truy cập mọi tệp" trong Cài đặt → Quyền truy cập.',icon:'photos'});
    }else fin(false);
  }
  const onRs=()=>{if(ED&&ED.crop)ED.crop.place()};
  window.addEventListener('resize',onRs);
  const kd=e=>{
    if(!w.isConnected||view<0)return;
    const l=ids(), pos=l.indexOf(view);
    if(e.key==='ArrowRight'){view=l[(pos+1)%l.length];renderViewer()}
    else if(e.key==='ArrowLeft'){view=l[(pos-1+l.length)%l.length];renderViewer()}
    else if(e.key==='Escape'){if(ED&&ED.mode){exitMode();syncBar()}else{view=-1;renderViewer()}}
  };
  document.addEventListener('keydown',kd);
  function openPid(id,name){
    id=+id; let k=PH.findIndex(p=>+p.pid===id);
    if(k<0){const e=['','','','','',name||'Ảnh'];e.pid=id;PH.unshift(e);fav=fav.map(i=>i+1);jset('dm-pfav',fav);k=0}
    tab='all'; view=k; render();
  }
  function openFile(name){let h=0;for(let i=0;i<name.length;i++)h=(h*31+name.charCodeAt(i))>>>0;tab='all';view=h%PH.length;render()}
  render();
  if(arg&&arg.pid)openPid(arg.pid,arg.name); else if(arg&&arg.file)openFile(arg.file);
  return {open(a){if(a&&a.pid)openPid(a.pid,a.name); else if(a&&a.file)openFile(a.file)},destroy(){document.removeEventListener('keydown',kd);window.removeEventListener('resize',onRs);if(pio)pio.disconnect()}};
}
};
