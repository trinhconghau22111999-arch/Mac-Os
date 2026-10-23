/* Ổ mềm (Gecko): dọn giao diện Drive + tự bấm Tải tệp lên */
(function(){
  if(window.__d3) return; window.__d3=true;
  var gridDone=false, promoClicked=false;
  function norm(s){return (s||'').replace(/\s+/g,' ').trim().toLowerCase();}
  function txtOf(e){return norm(e.innerText||e.textContent);}
  // Tìm phần tử chứa đúng đoạn chữ (duyệt theo text node, không phụ thuộc class của Drive)
  function find(labels, after){
    var w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var n;
    while((n=w.nextNode())){
      if(labels.indexOf(norm(n.nodeValue))<0) continue;
      var e=n.parentElement;
      if(!e) continue;
      if(after && !(after.compareDocumentPosition(e) & 4)) continue;
      return e;
    }
    return null;
  }
  function hide(e){
    if(e && e!==document.body && e!==document.documentElement)
      e.style.setProperty('display','none','important');
  }
  // Ẩn nguyên 1 dòng menu có chữ này
  function hideRow(labels){
    var e=find(labels);
    if(!e) return;
    var t=txtOf(e), p=e;
    while(p.parentElement && p.parentElement!==document.body && txtOf(p.parentElement)===t) p=p.parentElement;
    hide(p);
  }
  // Bảng đề xuất "Tải ứng dụng Drive": ẩn và bấm "Không, cảm ơn" một lần
  function hidePromo(){
    var btn=find(['không, cảm ơn','no, thanks','no thanks']);
    if(!btn) return;
    var box=null, el=btn;
    while(el && el!==document.body){
      var role=el.getAttribute('role');
      if(getComputedStyle(el).position==='fixed'||role==='dialog'||role==='alertdialog'){box=el;break;}
      el=el.parentElement;
    }
    if(box && txtOf(box).length>=300) box=null;
    if(!box){
      var h=find(['tải ứng dụng drive','get the drive app']);
      if(h){
        var p=h;
        while(p.parentElement && !p.parentElement.contains(btn)) p=p.parentElement;
        box=p.parentElement;
        if(box && txtOf(box).length>=300) box=null;
      }
    }
    hide(box);
    if(!promoClicked){promoClicked=true;try{btn.click();}catch(x){}}
  }
  function hideFolders(){
    var f=find(['folders','thư mục']);
    if(!f) return;
    var g=find(['files','tệp'], f);
    if(!g) return;
    var p=f;
    while(p.parentElement && !p.parentElement.contains(g)) p=p.parentElement;
    if(p!==document.body && !p.contains(g)) hide(p);
  }
  function ensureGrid(){
    if(gridDone) return;
    var n=document.querySelectorAll('[aria-label],[data-tooltip]');
    for(var i=0;i<n.length;i++){
      var e=n[i];
      var t=(e.getAttribute('aria-label')||e.getAttribute('data-tooltip')||'').toLowerCase();
      var toGrid=(t.indexOf('grid')>=0&&t.indexOf('switch')>=0)||(t.indexOf('lưới')>=0&&t.indexOf('chuyển')>=0);
      if(toGrid){e.click();gridDone=true;return;}
    }
  }
  setInterval(function(){
    if(!document.body) return;
    try{
      hidePromo();
      hideRow(['tải ứng dụng google drive','get the drive app','get drive for desktop']);
      hideFolders();
      ensureGrid();
    }catch(x){}
  },900);
})();

/* ---- Tự bấm "Mới" -> "Tải tệp lên" khi phần gốc đổi địa chỉ thành #dm-upload-... (nút "Tải lên" của Ổ mềm) ---- */
(function(){
  if(window.__dmUp) return; window.__dmUp=true;
  var running=false;
  function clickNew(){
    var L=['new','mới','tạo mới'];
    var n=document.querySelectorAll('[aria-label],[data-tooltip],div[role=button],button');
    for(var i=0;i<n.length;i++){
      var e=n[i];
      var t=(e.getAttribute('aria-label')||e.getAttribute('data-tooltip')||e.innerText||'').trim().toLowerCase();
      for(var j=0;j<L.length;j++){
        if(t===L[j]||t.endsWith('\n'+L[j])){e.click();return true;}
      }
    }
    return false;
  }
  function clickUpload(){
    var L=['file upload','tải tệp lên','upload files','upload file','tải lên tệp','upload','tải lên'];
    var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),n;
    while((n=w.nextNode())){
      var t=(n.nodeValue||'').replace(/\s+/g,' ').trim().toLowerCase();
      if(L.indexOf(t)<0) continue;
      var e=n.parentElement; if(!e) continue;
      var c=e.closest('[role=menuitem],[role=button],button,li')||e;
      c.click(); return true;
    }
    return false;
  }
  function step(fn,tries,next){
    if(fn()){ if(next) setTimeout(next,800); else running=false; return; }
    if(tries>0) setTimeout(function(){step(fn,tries-1,next);},800); else running=false;
  }
  function want(){ return location.hash.indexOf('dm-upload')>=0; }
  function run(){
    if(running) return; running=true;
    try{ history.replaceState(null,'',location.pathname+location.search); }catch(x){}
    step(clickNew,8,function(){ step(clickUpload,6,null); });
  }
  if(want()) setTimeout(run,1500);
  window.addEventListener('hashchange',function(){ if(want()) run(); });
})();
