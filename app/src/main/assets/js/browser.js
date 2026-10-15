/* Nạp lười: mã app "browser". Không chạy trực tiếp: index.html (lazyBuild) lấy nội dung hàm bọc rồi eval trong phạm vi của nó, nên dùng chung mọi biến của index.html. */
window.__LZ=window.__LZ||{};
window.__LZ.browser=function(){
/* ---------- Trình duyệt ---------- */
function buildBrowser(b,w){
  const FAV=[['Wikipedia','https://vi.wikipedia.org','#4a4d57'],['GitHub','https://github.com','#24292f'],['Anthropic','https://www.anthropic.com','#d97757'],['MDN','https://developer.mozilla.org','#1b6ac9'],['OpenStreetMap','https://www.openstreetmap.org','#3a9d54'],['Hacker News','https://news.ycombinator.com','#ff6600']];
  let tabs=[{id:1,hist:['dm://start'],pos:0}], act=1, nid=2; const visited=[];
  b.innerHTML='<div class="app br"><div class="bbar"><div class="nv"><button class="bk" aria-label="Quay lại"><svg viewBox="0 0 16 16"><path d="M10 3L5 8l5 5"/></svg></button><button class="fw" aria-label="Tiến tới"><svg viewBox="0 0 16 16"><path d="M6 3l5 5-5 5"/></svg></button></div><label class="addr"><svg viewBox="0 0 16 16"><circle cx="7" cy="7" r="4.6"/><path d="M10.5 10.5L14 14"/></svg><input type="text" aria-label="Địa chỉ" placeholder="Tìm kiếm hoặc nhập tên trang web" autocomplete="off"></label><button class="ib rl" aria-label="Tải lại"><svg viewBox="0 0 16 16"><path d="M13 8a5 5 0 1 1-1.6-3.7M13 2.5v3h-3"/></svg></button><button class="ib nt2" aria-label="Thẻ mới"><svg viewBox="0 0 16 16"><path d="M8 3v10M3 8h10"/></svg></button></div><div class="tabs"></div><div class="pg"></div></div>';
  const tabsEl=$('.tabs',b), pg=$('.pg',b), addr=$('.addr input',b), bk=$('.bk',b), fw=$('.fw',b);
  const tab=()=>tabs.find(t=>t.id===act), url=()=>tab().hist[tab().pos];
  function label(u){
    if(u==='dm://start')return 'Trang chủ'; if(u==='dm://gioi-thieu')return 'Giới thiệu'; if(u==='dm://lich-su')return 'Lịch sử';
    if(u.indexOf('dm://search')===0)return 'Tìm: '+decodeURIComponent(u.split('q=')[1]||'');
    try{return new URL(u).host}catch(e){return u}
  }
  function resolve(text){
    text=text.trim(); if(!text)return null;
    if(text.indexOf('dm://')===0)return text;
    if(/^https?:\/\/\S+$/i.test(text))return text;
    if(/^[\w-]+(\.[\w-]+)+(\/\S*)?$/.test(text))return 'https://'+text;
    return 'dm://search?q='+encodeURIComponent(text);
  }
  function nav(text){
    const u=resolve(text); if(!u)return;
    const t=tab(); t.hist=t.hist.slice(0,t.pos+1); t.hist.push(u); t.pos=t.hist.length-1; visited.unshift(u); render();
  }
  function localHits(term){
    const n=norm(term), out=[];
    LP_APPS.forEach(id=>{if(norm(APPS[id].name).indexOf(n)>=0)out.push({t:APPS[id].name,s:'Ứng dụng',fn:()=>openApp(id)})});
    return out.slice(0,8);
  }
  function page(u){
    if(u==='dm://start')return '<div class="bst"><h1>Trang chủ</h1><form class="bsf"><input type="text" placeholder="Tìm kiếm hoặc nhập địa chỉ" aria-label="Tìm kiếm" autocomplete="off"></form><h3>Mục yêu thích</h3><div class="favs">'+FAV.map(f=>'<a class="fv" href="'+f[1]+'" target="_blank" rel="noopener noreferrer"><i style="background:'+f[2]+'">'+f[0][0]+'</i><span>'+f[0]+'</span></a>').join('')+'</div><p class="note">Trang web bên ngoài sẽ mở trong thẻ trình duyệt thật, vì cửa sổ này không nhúng được trang web khác.</p><div class="lks"><button class="lnk" data-go="dm://gioi-thieu">Giới thiệu</button><button class="lnk" data-go="dm://lich-su">Lịch sử</button></div></div>';
    if(u==='dm://gioi-thieu')return '<div class="bst"><h1>Giới thiệu</h1><p>Đây là trình duyệt mô phỏng trong Desktop Mini. Nó có thẻ, lịch sử, tìm kiếm trong máy và liên kết ra web thật.</p></div>';
    if(u==='dm://lich-su')return '<div class="bst"><h1>Lịch sử</h1>'+(visited.length?'<ul class="hl">'+visited.map(v=>'<li><button class="lnk" data-go="'+esc(v)+'">'+esc(label(v))+'</button></li>').join('')+'</ul>':'<p>Chưa có trang nào.</p>')+'</div>';
    if(u.indexOf('dm://search')===0){
      const q=decodeURIComponent(u.split('q=')[1]||''), hits=localHits(q);
      return '<div class="bst"><h1>Kết quả cho "'+esc(q)+'"</h1><div class="sbtn"><a class="btn" href="https://www.google.com/search?q='+encodeURIComponent(q)+'" target="_blank" rel="noopener noreferrer">Tìm trên web</a><a class="btn" href="https://vi.wikipedia.org/w/index.php?search='+encodeURIComponent(q)+'" target="_blank" rel="noopener noreferrer">Tìm trên Wikipedia</a></div><h3>Trong máy này</h3>'+(hits.length?'<ul class="hl">'+hits.map((h,i)=>'<li><button class="lnk" data-hit="'+i+'">'+esc(h.t)+'</button> <small>'+esc(h.s)+'</small></li>').join('')+'</ul>':'<p>Không có ứng dụng hay tệp nào khớp.</p>')+'</div>';
    }
    let host=u; try{host=new URL(u).host}catch(e){}
    return '<div class="bst"><h1>'+esc(host)+'</h1><p>Trang web bên ngoài không nhúng được vào cửa sổ này.</p><a class="btn pri" href="'+esc(u)+'" target="_blank" rel="noopener noreferrer">Mở trong thẻ mới</a></div>';
  }
  function render(){
    const u=url(), t=tab();
    tabsEl.hidden=tabs.length<2; tabsEl.innerHTML='';
    tabs.forEach(x=>{
      const e=document.createElement('div'); e.className='tb2'; e.setAttribute('aria-current',String(x.id===act));
      e.innerHTML='<button class="tt">'+esc(label(x.hist[x.pos]))+'</button><button class="tx" aria-label="Đóng thẻ">×</button>';
      $('.tt',e).addEventListener('click',()=>{act=x.id;render()});
      $('.tx',e).addEventListener('click',()=>{tabs=tabs.filter(y=>y!==x);if(!tabs.length)tabs=[{id:nid++,hist:['dm://start'],pos:0}];if(!tabs.find(y=>y.id===act))act=tabs[0].id;render()});
      tabsEl.appendChild(e);
    });
    addr.value=u==='dm://start'?'':(u.indexOf('dm://search')===0?decodeURIComponent(u.split('q=')[1]||''):(u.indexOf('dm://')===0?label(u):u));
    bk.disabled=t.pos===0; fw.disabled=t.pos>=t.hist.length-1;
    pg.innerHTML=page(u);
    $$('[data-go]',pg).forEach(x=>x.addEventListener('click',()=>nav(x.dataset.go)));
    const hits=u.indexOf('dm://search')===0?localHits(decodeURIComponent(u.split('q=')[1]||'')):[];
    $$('[data-hit]',pg).forEach(x=>x.addEventListener('click',()=>hits[+x.dataset.hit].fn()));
    const sf=$('.bsf',pg); if(sf)sf.addEventListener('submit',e=>{e.preventDefault();nav($('input',sf).value)});
  }
  addr.addEventListener('keydown',e=>{if(e.key==='Enter')nav(addr.value)});
  addr.addEventListener('focus',()=>addr.select());
  bk.addEventListener('click',()=>{const t=tab();if(t.pos>0){t.pos--;render()}});
  fw.addEventListener('click',()=>{const t=tab();if(t.pos<t.hist.length-1){t.pos++;render()}});
  $('.rl',b).addEventListener('click',render);
  $('.nt2',b).addEventListener('click',()=>{const t={id:nid++,hist:['dm://start'],pos:0};tabs.push(t);act=t.id;render()});
  render();
  return {};
}
};
