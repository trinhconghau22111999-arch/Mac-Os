/* Nạp lười: mã app "calc". Không chạy trực tiếp: index.html (lazyBuild) lấy nội dung hàm bọc rồi eval trong phạm vi của nó, nên dùng chung mọi biến của index.html. */
window.__LZ=window.__LZ||{};
window.__LZ.calc=function(){
/* ---------- Máy tính ---------- */
function buildCalc(b,w){
  b.innerHTML='<div class="calc"><div class="bar"><button class="hbtn" type="button" aria-label="Lịch sử phép tính"><svg viewBox="0 0 24 24"><path d="M3.5 12a8.5 8.5 0 1 0 2.6-6.1M3.5 4v4.5H8M12 7.5V12l3 2"/></svg></button></div><div class="expr" aria-live="polite"></div><div class="disp"><span>0</span></div><div class="keys"></div><div class="chist" hidden><div class="chh"><b>Lịch sử</b><span><button type="button" class="hclr">Xóa</button><button type="button" class="hx" aria-label="Đóng">✕</button></span></div><ol class="chl"></ol></div></div>';
  const calcEl=$('.calc',b), disp=$('.disp',b), ds=$('span',disp), keys=$('.keys',b), exprEl=$('.expr',b);
  const hist=$('.chist',b), hl=$('.chl',b), HK='dm-calchist';
  let done='';
  let cur='0', prev=null, op=null, fresh=false, opBtn=null;
  let HIST=[]; try{const v=JSON.parse(lsGet(HK)||'[]');if(Array.isArray(v))HIST=v}catch(e){}
  const saveHist=()=>{try{lsSet(HK,JSON.stringify(HIST))}catch(e){}};
  function fmt(s){
    if(s==='Lỗi')return s;
    let neg=s[0]==='-'; if(neg)s=s.slice(1);
    const p=s.split('.'), i=p[0];
    let out=/^\d+$/.test(i)?Number(i).toLocaleString('vi-VN'):i;
    if(p.length>1)out+=','+p[1];
    return (neg?'-':'')+out;
  }
  /* Chữ kết quả to hết cỡ theo chiều cao cửa sổ, tự thu nhỏ khi số dài */
  function fit(){
    const H=calcEl.clientHeight, W=disp.clientWidth-16; if(!H||W<=0)return;
    let sz=H>340?Math.max(44,Math.min(150,H*0.2)):Math.max(30,Math.min(96,H*0.12)), n=0;
    disp.style.fontSize=sz+'px';
    while(ds.offsetWidth>W&&sz>16&&n++<40){sz*=0.93;disp.style.fontSize=sz+'px'}
  }
  const render=()=>{
    const t=fmt(cur);ds.textContent=t;
    let e=done;
    if(!e&&op!==null&&prev!==null)e=fmt(prev)+' '+op+(fresh?'':' '+t);
    exprEl.textContent=e||'\u00a0';
    fit();
  };
  const ro=window.ResizeObserver?new ResizeObserver(()=>fit()):null; if(ro)ro.observe(calcEl);
  /* Lịch sử: ghi lại sau mỗi lần bấm "=" */
  const hfmt=t=>{const d=new Date(t);return pad(d.getHours())+':'+pad(d.getMinutes())+' · '+pad(d.getDate())+'/'+pad(d.getMonth()+1)};
  function drawHist(){
    hl.innerHTML=HIST.length?HIST.map((h,i)=>'<li data-i="'+i+'"><small>'+esc(h.e)+'</small><em>'+esc(h.r)+'</em><small>'+hfmt(h.t)+'</small></li>').join(''):'<li class="non">Chưa có phép tính nào</li>';
  }
  function addHist(e,raw){
    if(raw==='Lỗi')return;
    HIST.unshift({e:e,r:fmt(raw),raw:raw,t:Date.now()}); if(HIST.length>500)HIST.length=500; saveHist();
  }
  $('.hbtn',b).addEventListener('click',()=>{drawHist();hist.hidden=false});
  $('.hx',b).addEventListener('click',()=>{hist.hidden=true});
  $('.hclr',b).addEventListener('click',()=>{HIST=[];saveHist();drawHist()});
  hl.addEventListener('click',e=>{
    const li=e.target.closest('li[data-i]'); if(!li)return;
    const h=HIST[+li.dataset.i]; if(!h)return;
    cur=h.raw; prev=null; op=null; fresh=true; done=''; clearOp(); hist.hidden=true; render();
  });
  const clearOp=()=>{if(opBtn){opBtn.classList.remove('on');opBtn=null}};
  function compute(){
    const a=parseFloat(prev), c=parseFloat(cur); let r;
    if(op==='+')r=a+c; else if(op==='−')r=a-c; else if(op==='×')r=a*c; else r=c===0?NaN:a/c;
    return isFinite(r)?String(+r.toPrecision(12)):'Lỗi';
  }
  const btns={};
  function press(l){
    const btn=btns[l];
    if(l!=='='&&l!=='%')done='';
    if(/^\d$/.test(l)){
      if(cur==='Lỗi'||fresh){cur=l;fresh=false}else cur=cur==='0'?l:(cur.replace(/[-.]/g,'').length>=12?cur:cur+l);
      clearOp();
    }else if(l===','){
      if(cur==='Lỗi'||fresh){cur='0.';fresh=false}else if(cur.indexOf('.')<0)cur+='.';
    }else if(l==='AC'){cur='0';prev=null;op=null;fresh=false;done='';clearOp()}
    else if(l==='D'){if(cur==='Lỗi'||fresh)cur='0';else{cur=cur.slice(0,-1);if(cur===''||cur==='-')cur='0'}}
    else if(l==='%'){if(cur!=='Lỗi')cur=String(+(parseFloat(cur)/100).toPrecision(12))}
    else if(l==='='){if(op!==null&&prev!==null){const a=prev,o=op,c=cur;cur=compute();done=fmt(a)+' '+o+' '+fmt(c)+' =';addHist(done,cur);prev=null;op=null;fresh=true}clearOp()}
    else{
      if(op!==null&&prev!==null&&!fresh){cur=compute()}
      prev=cur; op=l; fresh=true; clearOp(); opBtn=btn; btn.classList.add('on');
    }
    render();
  }
  [['AC','fn'],['D','fn'],['%','fn'],['÷','op'],['7',''],['8',''],['9',''],['×','op'],['4',''],['5',''],['6',''],['−','op'],['1',''],['2',''],['3',''],['+','op'],['0','wide'],[',',''],['=','op']].forEach(k=>{
    const e=document.createElement('button'); e.className='k '+k[1]; e.textContent=k[0]==='D'?'⌫':k[0]; btns[k[0]]=e;
    e.addEventListener('click',()=>press(k[0]));
    keys.appendChild(e);
  });
  const KM={'+':'+','-':'−','*':'×','/':'÷','Enter':'=','=':'=',',':',','.':',','%':'%','Escape':'AC','Backspace':'D','c':'AC','C':'AC'};
  const kd=e=>{
    if(!w.isConnected||!w.classList.contains('focus'))return;
    if(e.target.closest&&e.target.closest('input,textarea'))return;
    const l=/^\d$/.test(e.key)?e.key:KM[e.key];
    if(l){e.preventDefault();press(l)}
  };
  document.addEventListener('keydown',kd);
  render();
  return {destroy(){document.removeEventListener('keydown',kd);if(ro)ro.disconnect()}};
}
};
