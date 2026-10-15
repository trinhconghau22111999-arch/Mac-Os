/* Nạp lười: mã app "Đồng hồ". Không chạy trực tiếp: index.html (lazyBuild) lấy nội dung hàm bọc rồi eval trong phạm vi của nó, nên dùng chung mọi biến của index.html. */
window.__LZ=window.__LZ||{};
window.__LZ.clock=function(){
/* ---------- Đồng hồ ---------- */
function buildClock(b,w){
  const CITIES=[['Hà Nội','Asia/Ho_Chi_Minh']];
  b.innerHTML='<div class="app clk2"><div class="bar"><div class="nv seg3"><button data-k="world">Giờ thế giới</button><button data-k="sw">Bấm giờ</button><button data-k="tm">Hẹn giờ</button></div></div><div class="cbody"></div></div>';
  const body=$('.cbody',b); let tab='world', timers=[];
  const stop=()=>{timers.forEach(clearInterval);timers=[]};
  function face(i){return '<svg class="af" viewBox="0 0 100 100"><circle cx="50" cy="50" r="46" fill="var(--field)" stroke="var(--line)"/>'+[...Array(12)].map((_,k)=>'<line x1="50" y1="8" x2="50" y2="'+(k%3?12:15)+'" stroke="var(--muted)" stroke-width="'+(k%3?1:2)+'" transform="rotate('+k*30+' 50 50)"/>').join('')+'<line class="hh" x1="50" y1="50" x2="50" y2="28" stroke="var(--fg)" stroke-width="3.2" stroke-linecap="round"/><line class="mh" x1="50" y1="50" x2="50" y2="17" stroke="var(--fg)" stroke-width="2.4" stroke-linecap="round"/><line class="sh" x1="50" y1="56" x2="50" y2="12" stroke="#ff9f0a" stroke-width="1.2" stroke-linecap="round"/><circle cx="50" cy="50" r="2.4" fill="#ff9f0a"/></svg>'}
  function parts(tz){
    const f=new Intl.DateTimeFormat('en-GB',{timeZone:tz,hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});
    const o={}; f.formatToParts(new Date()).forEach(p=>{o[p.type]=+p.value}); if(o.hour===24)o.hour=0; return o;
  }
  const ymd=tz=>new Date().toLocaleDateString('en-CA',{timeZone:tz});
  function worldTick(){
    $$('.wc',body).forEach(c=>{
      const tz=c.dataset.tz, p=parts(tz);
      $('.hh',c).setAttribute('transform','rotate('+((p.hour%12)*30+p.minute*.5)+' 50 50)');
      $('.mh',c).setAttribute('transform','rotate('+(p.minute*6+p.second*.1)+' 50 50)');
      $('.sh',c).setAttribute('transform','rotate('+p.second*6+' 50 50)');
      $('.wt',c).textContent=pad(p.hour)+':'+pad(p.minute);
      const a=ymd(tz), l=ymd(Intl.DateTimeFormat().resolvedOptions().timeZone);
      $('.wd',c).textContent=a===l?'Hôm nay':(a<l?'Hôm qua':'Ngày mai');
    });
  }
  function show(){
    stop();
    $$('.seg3 button',b).forEach(x=>x.setAttribute('aria-pressed',String(x.dataset.k===tab)));
    if(tab==='world'){
      body.innerHTML='<div class="wgrid">'+CITIES.map(c=>'<div class="wc" data-tz="'+c[1]+'">'+face()+'<b>'+c[0]+'</b><span class="wt"></span><small class="wd"></small></div>').join('')+'</div>';
      worldTick(); timers.push(setInterval(()=>{if(!document.hidden)worldTick()},1000));
    }else if(tab==='sw'){
      let run=false, t0=0, el=0, laps=[];
      body.innerHTML='<div class="swb"><div class="big sd">00:00.00</div><div class="swc"><button class="btn lp2">Vòng</button><button class="btn pri st">Bắt đầu</button></div><ol class="laps"></ol></div>';
      const sd=$('.sd',body), st=$('.st',body), lp=$('.lp2',body), ol=$('.laps',body);
      const f=ms=>{const c=Math.floor(ms/10)%100,s=Math.floor(ms/1000)%60,m=Math.floor(ms/60000);return pad(m)+':'+pad(s)+'.'+pad(c)};
      const upd=()=>{sd.textContent=f(run?el+(performance.now()-t0):el)};
      st.addEventListener('click',()=>{if(run){el+=performance.now()-t0;run=false;st.textContent='Tiếp tục';lp.textContent='Đặt lại'}else{t0=performance.now();run=true;st.textContent='Dừng';lp.textContent='Vòng'}upd()});
      lp.addEventListener('click',()=>{
        if(run){laps.unshift(f(el+performance.now()-t0))}else{el=0;laps=[];st.textContent='Bắt đầu';lp.textContent='Vòng'}
        ol.innerHTML=laps.map((x,i)=>'<li><span>Vòng '+(laps.length-i)+'</span><span>'+x+'</span></li>').join(''); upd();
      });
      timers.push(setInterval(()=>{if(!document.hidden)upd()},47));
    }else{
      const NA2=()=>window.AndroidLauncher||null;
      let AL=jget('dm-alarms',[]), SND=[{name:'Mặc định',uri:''}], sel=0, rep=false;
      try{const n=NA2();if(n&&n.getAlarmSounds){const l=JSON.parse(n.getAlarmSounds());if(l.length)SND=l}}catch(x){}
      const now0=new Date(); let hh=now0.getHours(), mm=(now0.getMinutes()+1)%60; if(mm===0)hh=(hh+1)%24;
      const saveAL=()=>jset('dm-alarms',AL);
      body.innerHTML='<div class="alm"><div class="whl"><div class="wcol" data-w="h" role="listbox" aria-label="Chọn giờ"></div><b class="wsep">:</b><div class="wcol" data-w="m" role="listbox" aria-label="Chọn phút"></div><i class="wband"></i></div>'+
        '<div class="alr"><span>Lặp lại hằng ngày</span>'+tgl(false,'','Lặp lại hằng ngày')+'</div>'+
        '<div class="als"><b>Âm thanh báo thức</b><ul class="snl"></ul></div>'+
        '<button class="btn pri aset" type="button">Đặt báo thức</button>'+
        '<div class="als"><b>Báo thức đã đặt</b><ul class="all"></ul></div></div>';
      const cols={h:$('[data-w="h"]',body), m:$('[data-w="m"]',body)};
      const IH=40;
      const fillCol=(c,max)=>{c.innerHTML=[...Array(max)].map((_,i)=>'<div class="wi" data-v="'+i+'">'+pad(i)+'</div>').join('')};
      fillCol(cols.h,24); fillCol(cols.m,60);
      const setCol=(c,v)=>{c.scrollTop=v*IH};
      const getCol=c=>Math.max(0,Math.round(c.scrollTop/IH));
      requestAnimationFrame(()=>{setCol(cols.h,hh);setCol(cols.m,mm)});
      ['h','m'].forEach(k=>{
        const c=cols[k]; let t=null;
        c.addEventListener('scroll',()=>{clearTimeout(t);t=setTimeout(()=>{const v=getCol(c);if(k==='h')hh=Math.min(23,v);else mm=Math.min(59,v);$$('.wi',c).forEach(x=>x.classList.toggle('on',+x.dataset.v===v))},80)});
        c.addEventListener('click',e=>{const x=e.target.closest('.wi');if(x)c.scrollTo({top:+x.dataset.v*IH,behavior:'smooth'})});
      });
      setTimeout(()=>{$$('.wi',cols.h)[hh]&&$$('.wi',cols.h)[hh].classList.add('on');$$('.wi',cols.m)[mm]&&$$('.wi',cols.m)[mm].classList.add('on')},60);
      const rtg=$('.alr .tg',body);
      rtg.addEventListener('click',()=>{rep=!rep;rtg.setAttribute('aria-checked',String(rep))});
      const snl=$('.snl',body);
      const drawSnd=()=>{snl.innerHTML=SND.map((x,i)=>'<li data-i="'+i+'" aria-selected="'+(i===sel)+'"><span class="rd"></span><span class="sn">'+esc(x.name)+'</span><button type="button" class="apv" data-p="'+i+'" aria-label="Nghe thử">▶</button></li>').join('')};
      drawSnd();
      snl.addEventListener('click',e=>{
        const pv=e.target.closest('.apv');
        if(pv){const n=NA2();const u=SND[+pv.dataset.p].uri;if(n&&n.previewSound)n.previewSound(u);else beep();return}
        const li=e.target.closest('li[data-i]'); if(li){sel=+li.dataset.i;drawSnd()}
      });
      const all=$('.all',body);
      const drawAL=()=>{
        all.innerHTML=AL.length?AL.slice().sort((p,q)=>p.h*60+p.m-(q.h*60+q.m)).map(x=>'<li data-id="'+x.id+'"><span class="at">'+pad(x.h)+':'+pad(x.m)+'<small>'+(x.rep?'Hằng ngày':'Một lần')+' · '+esc(x.name)+'</small></span>'+tgl(x.on,'','Bật tắt báo thức')+'<button type="button" class="adl" aria-label="Xóa báo thức"><svg viewBox="0 0 14 14" width="12" height="12" aria-hidden="true"><path d="M2 2L12 12M12 2L2 12" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg></button></li>').join(''):'<li class="non">Chưa có báo thức nào</li>';
      };
      drawAL();
      const arm=x=>{const n=NA2();if(n&&n.setAlarm){const ok=n.setAlarm(x.id,x.h,x.m,x.uri||'',!!x.rep,'');if(!ok){let why='';try{why=(n.getAlarmError&&n.getAlarmError())||''}catch(e){}notify({app:'Đồng hồ',title:'Không đặt được báo thức',body:why?('Lỗi: '+why):'Hãy cấp quyền thông báo cho ứng dụng rồi thử lại.',icon:'clock'})}return !!ok}return true};
      const disarm=x=>{const n=NA2();if(n&&n.cancelAlarm)n.cancelAlarm(x.id)};
      $('.aset',body).addEventListener('click',()=>{
        const x={id:(Date.now()%2000000000)|0,h:hh,m:mm,uri:SND[sel].uri,name:SND[sel].name,rep:rep,on:true};
        if(arm(x)){AL.push(x); saveAL(); drawAL();
          notify({app:'Đồng hồ',title:'Đã đặt báo thức '+pad(hh)+':'+pad(mm),body:SND[sel].name,icon:'clock'})}
      });
      all.addEventListener('click',e=>{
        const li=e.target.closest('li[data-id]'); if(!li)return;
        const x=AL.find(q=>String(q.id)===li.dataset.id); if(!x)return;
        if(e.target.closest('.adl')){disarm(x);AL=AL.filter(q=>q!==x);saveAL();drawAL();return}
        if(e.target.closest('.tg')){x.on=!x.on;saveAL();if(x.on)arm(x);else disarm(x);drawAL()}
      });
      /* Bản chạy trên trình duyệt (không có app Android): báo bằng âm bíp khi đúng giờ */
      timers.push(setInterval(()=>{
        if(NA2()&&NA2().setAlarm)return;
        const d=new Date(); if(d.getSeconds()!==0)return;
        AL.forEach(x=>{if(x.on&&x.h===d.getHours()&&x.m===d.getMinutes()){beep();notify({app:'Đồng hồ',title:'Báo thức '+pad(x.h)+':'+pad(x.m),body:x.name,icon:'clock'});if(!x.rep){x.on=false;saveAL()}}});
      },1000));
    }
  }
  $$('.seg3 button',b).forEach(x=>x.addEventListener('click',()=>{tab=x.dataset.k;show()}));
  show();
  return {destroy:stop};
}
};
