/* Nạp lười: mã app "settings". Không chạy trực tiếp: index.html (lazyBuild) lấy nội dung hàm bọc rồi eval trong phạm vi của nó, nên dùng chung mọi biến của index.html. */
window.__LZ=window.__LZ||{};
window.__LZ.settings=function(){
const SVK=[['alarm','Âm báo thức'],['ring','Chuông cuộc gọi'],['notif','Âm thanh thông báo']];
function svRows(){
  const A=window.AndroidLauncher; if(!A||!A.getStreamVol)return '';
  return SVK.map(k=>{let v=-1;try{v=A.getStreamVol(k[0])}catch(e){} if(v<0)return '';
    return '<div class="rw"><span><b>'+k[1]+'</b><small>'+v+'%</small></span><span class="rc2"><input type="range" class="rg sv" data-sv="'+k[0]+'" min="0" max="100" value="'+v+'" aria-label="'+k[1]+'"></span></div>'}).join('');
}
function buildSettings(b,w,arg){
  const NAV=[['wifi','Wi-Fi','#2f8bff'],['bt','Bluetooth','#2f8bff'],['ap','Giao diện','#5b5fe0'],['wp','Hình nền','#2fb3d6'],['dk','Màn hình nền và Dock','#4a4d57'],['dp','Màn hình','#2f8bff'],['sd','Âm thanh','#ff3b5c'],['bt2','Pin','#34c759'],['lk','Mật khẩu màn hình','#8e8e93'],['us','Người dùng','#8e8e93'],['pm','Quyền truy cập','#34c759'],['ab','Giới thiệu','#8e8e93']];
  const rw=(l,c,s)=>'<div class="rw"><span><b>'+l+'</b>'+(s?'<small>'+s+'</small>':'')+'</span><span class="rc2">'+c+'</span></div>';
  const sld=(id,v,lo,hi)=>'<input type="range" class="rg" data-s="'+id+'" min="'+lo+'" max="'+hi+'" value="'+v+'" aria-label="'+id+'">';
  b.innerHTML='<div class="sset"><nav class="side"></nav><div class="main"><div class="bar"><strong class="t pt"></strong></div><div class="pane"></div></div></div>';
  const nav=$('.side',b), pane=$('.pane',b), pt=$('.pt',b); let key=(arg&&arg.pane)||'ap';
  NAV.forEach(n=>{
    const s=document.createElement('button'); s.className='si'; s.dataset.p=n[0];
    s.innerHTML='<i class="sq" style="--c:'+n[2]+'"></i><span>'+n[1]+'</span>';
    s.addEventListener('click',()=>{key=n[0];render()});
    nav.appendChild(s);
  });
  function info(){
    const nr=nativeRows(); if(nr)return [['Hệ thống','Desktop Bắp OS 26']].concat(nr);
    const nv=navigator, sc=window.screen;
    return [['Hệ thống','Desktop Bắp OS 26'],['Lõi',(nv.userAgentData&&nv.userAgentData.platform)||nv.platform||'Không rõ'],['Ngôn ngữ',nv.language||'vi'],['Màn hình',sc.width+' × '+sc.height],['Số lõi xử lý',nv.hardwareConcurrency||'Không rõ'],['Bộ nhớ',nv.deviceMemory?nv.deviceMemory+' GB':'Không rõ']];
  }
  function panel(){
    const dm=themeMode(), wl=lsGet('dm-photowall')?'':stage.dataset.wall;
    switch(key){
      case 'pm':{
        const A=window.AndroidLauncher; if(!A)return ['Quyền truy cập','<div class="grp"><div class="rw"><span><b>Chỉ có trong app Android</b><small>Bản web không có quyền hệ thống để cấp.</small></span></div></div>'];
        let st={}; try{st=JSON.parse(A.getStatus())}catch(e){}
        const R=[['write','Sửa cài đặt hệ thống','Đổi độ sáng màn hình'],['notif','Đọc thông báo','Hiện thông báo của ứng dụng khác'],['a11y','Trợ năng','Khóa màn hình, menu tắt máy/khởi động lại'],['files','Truy cập mọi tệp','Ứng dụng Tệp và Tải xuống'],['calendar','Lịch','Đọc sự kiện trong lịch của máy'],['dnd','Không làm phiền','Chỉnh âm lượng chuông và thông báo']];
        return ['Quyền truy cập','<div class="grp">'+R.map(r=>rw(r[1],st[r[0]]?'<span style="color:#34c759">Đã cấp</span>':'<button class="btn" data-setup="'+r[0]+'">Cấp quyền</button>',r[2])).join('')+'</div>'];
      }
      case 'wifi':{
        const A=window.AndroidLauncher;
        if(!A||!A.getWifiInfo)return ['Wi-Fi','<div class="grp">'+rw('Wi-Fi',tgl(pref('wifi',true),'wifi','Wi-Fi'),navigator.onLine?'Đã kết nối':'Không có kết nối mạng')+'</div>'];
        let o={}; try{o=JSON.parse(A.getWifiInfo())}catch(e){}
        if(Date.now()-(window._wfScan||0)>9000){window._wfScan=Date.now();try{A.wifiScan()}catch(e){}}
        const on=!!o.on, L=o.list||[], cn=L.filter(n=>n.connected), ot=L.filter(n=>!n.connected);
        const bars=l=>'<span style="letter-spacing:1px;color:var(--muted)">'+'▂▄▆█'.slice(0,Math.max(1,Math.min(4,l+1)))+'</span>';
        const net=n=>'<div class="rw" data-wf="'+esc(n.ssid)+'" style="cursor:pointer"><span><b>'+esc(n.ssid)+'</b><small>'+(n.connected?'Đã kết nối':(n.saved?'Đã lưu · chưa kết nối':'Chưa kết nối'))+'</small></span><span class="rc2"'+(n.connected?' style="color:#34c759"':'')+'>'+(n.secure?'🔒 ':'')+bars(n.level)+'</span></div>';
        const hd=t=>'<p style="margin:16px 6px 6px;color:var(--muted);font-size:12px">'+t+'</p>';
        let h='<div class="grp">'+rw('Wi-Fi',tgl(on,'wifi','Wi-Fi'),!on?'Đã tắt':(cn.length?'Đã kết nối: '+esc(cn[0].ssid):'Chưa kết nối mạng nào'))+'</div>';
        if(on){
          if(cn.length)h+=hd('Đang kết nối')+'<div class="grp">'+cn.map(net).join('')+'</div>';
          if(ot.length)h+=hd('Mạng khác')+'<div class="grp">'+ot.map(net).join('')+'</div>';
          if(!o.loc)h+=hd('Cấp quyền Vị trí cho Desktop Mini (Cài đặt → Quyền) để thấy danh sách mạng xung quanh.');
          else if(o.locOn===false)h+=hd('Android chỉ cho quét mạng khi Vị trí của máy đang bật. Hãy bật Vị trí rồi quay lại đây.');
          else if(!ot.length)h+=hd('Đang quét mạng… Nếu vẫn trống, hãy đợi vài giây.');
        }
        return ['Wi-Fi',h];
      }
      case 'bt':{
        const A=window.AndroidLauncher;
        if(!A||!A.getBtInfo)return ['Bluetooth','<div class="grp">'+rw('Bluetooth',tgl(pref('bt',true),'bt','Bluetooth'),pref('bt',true)?'Đang bật':'Đã tắt')+'</div>'];
        let o={}; try{o=JSON.parse(A.getBtInfo())}catch(e){}
        if(o.none)return ['Bluetooth','<div class="grp"><div class="rw"><span><b>Máy không có Bluetooth</b></span></div></div>'];
        if(o.on&&Date.now()-(window._btScan||0)>20000){window._btScan=Date.now();try{A.btScan()}catch(e){}}
        const on=!!o.on, P2=o.paired||[], F=o.found||[], cn=P2.filter(d=>d.connected), ds=P2.filter(d=>!d.connected);
        const dev=(d,st,c)=>'<div class="rw" data-bt="1" style="cursor:pointer"><span><b>'+esc(d.name)+'</b><small>'+st+'</small></span><span class="rc2"'+(c?' style="color:#34c759"':'')+'>'+(c?'●':'')+'</span></div>';
        const hd=t=>'<p style="margin:16px 6px 6px;color:var(--muted);font-size:12px">'+t+'</p>';
        let h='<div class="grp">'+rw('Bluetooth',tgl(on,'bt','Bluetooth'),!on?'Đã tắt':(cn.length?'Đang kết nối '+cn.length+' thiết bị':'Đang bật · chưa kết nối thiết bị nào'))+'</div>';
        if(on){
          if(cn.length)h+=hd('Đã kết nối')+'<div class="grp">'+cn.map(d=>dev(d,'Đã kết nối',true)).join('')+'</div>';
          if(ds.length)h+=hd('Đã ghép đôi · chưa kết nối')+'<div class="grp">'+ds.map(d=>dev(d,'Chưa kết nối',false)).join('')+'</div>';
          if(F.length)h+=hd('Thiết bị xung quanh')+'<div class="grp">'+F.map(d=>dev(d,'Chưa ghép đôi',false)).join('')+'</div>';
          if(o.perm===false)h+=hd('Cấp quyền Bluetooth cho Desktop Mini để thấy thiết bị đã ghép đôi.');
          else if(o.scanPerm===false)h+=hd('Cấp quyền “Thiết bị ở gần” cho Desktop Mini để tìm thiết bị Bluetooth xung quanh (đang hỏi quyền…).');
          else if(!o.loc)h+=hd('Cấp quyền Vị trí cho Desktop Mini (Cài đặt → Quyền) để tìm thiết bị xung quanh.');
          else if(o.locOn===false)h+=hd('Android chỉ cho tìm thiết bị Bluetooth khi Vị trí của máy đang bật. Hãy bật Vị trí rồi quay lại đây.');
          else if(!F.length)h+=hd(o.scanning?'Đang tìm thiết bị xung quanh…':'Chưa thấy thiết bị nào xung quanh. Hãy bật chế độ ghép đôi trên thiết bị kia.');
          else if(o.scanning)h+=hd('Đang tìm thêm thiết bị…');
        }
        return ['Bluetooth',h];
      }
      case 'ap':return ['Giao diện','<div class="tiles"><button class="ap" data-m="auto" aria-pressed="'+(dm==='auto')+'"><span class="th a"></span><span>Tự động</span></button><button class="ap" data-m="light" aria-pressed="'+(dm==='light')+'"><span class="th l"></span><span>Sáng</span></button><button class="ap" data-m="dark" aria-pressed="'+(dm==='dark')+'"><span class="th d"></span><span>Tối</span></button></div>'];
      case 'wp':return ['Hình nền','<div class="tiles"><button class="ap" data-n="0" aria-pressed="'+(wl==='0')+'"><span class="wl w0"></span><span>Bầu trời</span></button><button class="ap" data-n="1" aria-pressed="'+(wl==='1')+'"><span class="wl w1"></span><span>Biển</span></button><button class="ap" data-n="2" aria-pressed="'+(wl==='2')+'"><span class="wl w2"></span><span>Hoàng hôn</span></button><button class="ap" data-n="3" aria-pressed="'+(wl==='3')+'"><span class="wl w3"></span><span>Đêm</span></button>'+(NA()&&NA().getPhotos?'<button class="ap" data-pick="1" aria-pressed="'+(!!lsGet('dm-photowall'))+'"><span class="wl" style="background:linear-gradient(135deg,#5b6577,#2b303b);display:grid;place-items:center;font-size:30px;color:#fff">▣</span><span>Ảnh trên máy</span></button>':'')+'</div>'];
      case 'dk':return ['Màn hình nền và Dock','<div class="grp">'+rw('Kích thước',sld('dockSize',Math.max(18,Math.min(45,pref('dockSize',27))),18,45),Math.round(Math.max(18,Math.min(45,pref('dockSize',27)))/45*100)+'%')+rw('Phóng đại',tgl(pref('magnify',true),'magnify','Phóng đại'))+rw('Thanh tìm kiếm Google',tgl(pref('gbar',true),'gbar','Thanh tìm kiếm Google'),'Hiện thanh tìm kiếm Google trên màn hình nền, tìm bằng ứng dụng Duyệt web')+rw('Chế độ nhẹ (máy yếu)',tgl(pref('lite',false),'lite','Chế độ nhẹ'),'Tắt hiệu ứng mờ kính và bóng lớn để mượt hơn trên máy cấu hình thấp')+rw('Tự động ẩn và hiện Dock',tgl(pref('hideDock',false),'hideDock','Tự động ẩn Dock'),'Rê chuột xuống đáy màn hình để hiện')+rw('Khóa thanh thông báo và đa nhiệm',tgl(pref('barLock',true),'barLock','Khóa thanh hệ thống'),'Tự đóng ngay thanh thông báo (kéo từ trên xuống) và màn hình đa nhiệm khi bị mở. Cần bật dịch vụ trợ năng. Tắt nếu cần dùng lại hai thanh này')+rw('Nút Desktop nổi trên ứng dụng khác',tgl(pref('dockFloat',true),'dockFloat','Nút Desktop nổi')+rw('Thanh menu nổi trên ứng dụng khác',tgl(pref('menuFloat',true),'menuFloat','Thanh menu nổi'),'Thanh menu của Desktop Mini phủ lên thanh trạng thái của hệ thống khi bạn dùng ứng dụng khác, hoạt động độc lập với nút Desktop. Cần bật dịch vụ trợ năng'),'Hiện nút tròn "Desktop" trên ứng dụng khác: bấm 1 lần để về màn hình Desktop, nhấn giữ rồi kéo để đổi vị trí. Cần bật dịch vụ trợ năng. Muốn ẩn luôn thanh trạng thái và thanh điều hướng của hệ thống (Android 10 trở xuống) cần cấp quyền WRITE_SECURE_SETTINGS qua adb')+'</div>'];
      case 'dp':return ['Màn hình','<div class="grp">'+rw('Độ sáng',sld('bright',pref('bright',100),1,100))+rw('Tự xoay màn hình',tgl(rotOn(),'rot','Tự xoay màn hình'),rotOn()?'Đang bật':'Đã tắt')+rw('Độ sáng thấp nhất khi không dùng',tgl(stoOn(),'sto','Độ sáng thấp nhất khi không dùng'),stoOn()?'Sau '+stoMin()+' phút không chạm, màn hình hạ xuống độ sáng thấp nhất; chạm lại để sáng như cũ':'Đã tắt')+(stoOn()?rw('Thời gian chờ',['5','10','30'].map(m=>'<button type="button" data-sto="'+m+'" aria-pressed="'+(stoMin()===+m)+'">'+m+' phút</button>').join('').replace(/^/,'<span class="nv seg3">')+'</span>'):'')+'</div>'];
      case 'sd':return ['Âm thanh','<div class="grp">'+rw('Âm lượng đầu ra',sld('vol',pref('vol',60),0,100),pref('vol',60)+'%')+svRows()+'</div>'+(svRows()?'<small class="svn" hidden style="display:block;margin:8px 4px;opacity:.7">Không đổi được âm lượng này. Hãy tắt chế độ Không làm phiền của máy rồi thử lại.</small>':'')];
      case 'bt2':{
        const A=window.AndroidLauncher, ps=!!(A&&A.isPowerSave&&A.isPowerSave());
        return ['Pin','<div class="grp">'+rw('Mức pin',Math.round(BAT.level*100)+'%',BAT.charging?'Đang sạc':(BAT.real?'Đang dùng pin':'Giá trị mẫu, trình duyệt không cung cấp thông tin pin'))+(A&&A.isPowerSave?rw('Tiết kiệm pin',tgl(ps,'psave','Tiết kiệm pin'),ps?'Đang bật: giảm hoạt động nền để dùng được lâu hơn':'Đang tắt'):'')+'</div>'];
      }
      case 'lk':{
        const o=lk(), pm='pin', AL=window.AndroidLauncher; let isHome=false; try{isHome=!!(AL&&AL.isHomeDefault&&AL.isHomeDefault())}catch(e){}
        const st=k=>o[k]?'<span style="color:#34c759;margin-right:8px">Đã đặt</span>':'<span style="opacity:.6;margin-right:8px">Chưa đặt</span>';
        const bt=k=>'<button class="btn" data-lk="set:'+k+'">'+(o[k]?'Đổi':'Đặt')+'</button>'+(o[k]?'<button class="btn" data-lk="del:'+k+'" style="margin-left:6px">Xóa</button>':'');
        return ['Mật khẩu màn hình','<div class="grp">'+rw('Mật khẩu ký tự',st('pw')+bt('pw'),'Dùng khi màn hình nằm ngang')+rw('Mã PIN',st('pin')+bt('pin'),'Dùng khi màn hình để dọc')+(o.pat?rw('Hình vẽ mở khóa',st('pat')+bt('pat'),'Còn đặt từ trước nên vẫn hỏi mở khóa — bấm Xóa để bỏ'):'')+rw('Khóa khi tắt màn hình',tgl(pref('lockOnOff',true),'lockOnOff','Khóa khi tắt màn hình'),'Bật lại màn hình phải nhập mới vào được')+(AL?rw('Màn hình chính mặc định',isHome?'<span style="color:#34c759">Đang bật</span>':'<button class="btn" data-setup="home">Đặt mặc định</button>',isHome?'Nút Home luôn về Desktop Mini':'Chưa đặt: nút Home vẫn về launcher cũ'):'')+'</div><small style="display:block;margin:10px 4px;opacity:.7">'+(isHome?'Desktop Mini đang là màn hình chính, nên mật khẩu chặn được người khác dùng màn hình chính khi bật lại máy. ':'')+'Mật khẩu chỉ khóa giao diện Desktop Mini, không thay thế khóa màn hình của Android: người khác vẫn mở được ứng dụng khác qua thanh thông báo hoặc màn hình đa nhiệm. Nên giữ khóa màn hình Android song song. Nên đặt cả mật khẩu ký tự và mã PIN; nếu kiểu cần dùng chưa đặt thì hệ thống dùng kiểu đã đặt. Quên mật khẩu: xóa dữ liệu ứng dụng trong Cài đặt Android'+(isHome?' (vào qua thanh thông báo → biểu tượng bánh răng, hoặc ngăn kéo ứng dụng của launcher cũ)':'')+'.</small>'];
      }
      case 'us':return ['Người dùng','<div class="grp">'+rw('Tên hiển thị','<input class="txt" id="un" value="'+esc(userName())+'" aria-label="Tên hiển thị" maxlength="24">')+'</div>'];
      default:return ['Giới thiệu','<div class="grp">'+info().map(r=>rw(r[0],esc(String(r[1])))).join('')+'</div>'];
    }
  }
  pane.addEventListener('click',e=>{const x=e.target.closest('[data-setup]');if(x&&window.AndroidLauncher)AndroidLauncher.openSetup(x.dataset.setup);
    const so=e.target.closest('[data-sto]');if(so){setStoMin(+so.dataset.sto);render();return}
    const l=e.target.closest('[data-lk]');if(l){const [act,k]=l.dataset.lk.split(':');if(act==='pm'){setPref('lockPm',k);render()}else if(act==='set')lkSetFlow(k,render);else if(act==='del')lkDelFlow(k,render)}});
  function render(){
    const p=panel(), sc0=pane.scrollTop, sc1=$('.main',b).scrollTop; pt.textContent=p[0]; pane.innerHTML='<h2>'+p[0]+'</h2>'+p[1]; pane.scrollTop=sc0; $('.main',b).scrollTop=sc1;
    $$('.si',nav).forEach(s=>s.setAttribute('aria-current',s.dataset.p===key?'true':'false'));
    $$('[data-m]',pane).forEach(x=>x.addEventListener('click',()=>setTheme(x.dataset.m)));
    $$('[data-n]',pane).forEach(x=>x.addEventListener('click',()=>setWall(x.dataset.n)));
    $$('[data-pick]',pane).forEach(x=>x.addEventListener('click',()=>pickPhotoWall()));
    $$('.tg[data-t="psave"]',pane).forEach(x=>x.addEventListener('click',e=>{
      e.stopImmediatePropagation();
      const A=window.AndroidLauncher; let on=false; try{on=A.isPowerSave()}catch(err){}
      try{A.setPowerSave(!on)}catch(err){}
      setTimeout(()=>bus.emit('ui'),600);
    },true));
    $$('[data-wf]',pane).forEach(x=>x.addEventListener('click',()=>{const A=window.AndroidLauncher;let ok=false;try{ok=A.connectWifi(x.dataset.wf)}catch(e){} if(ok)setTimeout(()=>bus.emit('ui'),2000);else{try{A.openPanel('wifi')}catch(e){}}}));
    $$('[data-bt]',pane).forEach(x=>x.addEventListener('click',()=>{try{window.AndroidLauncher.openPanel('bt')}catch(e){}}));
    $$('.tg',pane).forEach(x=>x.addEventListener('click',()=>{const k=x.dataset.t;if(k==='rot'){toggleRot();render();return}if(k==='sto'){toggleSto();render();return}setPref(k,!pref(k,k==='wifi'||k==='bt'||k==='magnify'||k==='gbar'||k==='lockOnOff'||k==='dockFloat'||k==='barLock'||k==='menuFloat'))}));
    $$('.sv',pane).forEach(x=>{
      const A=window.AndroidLauncher, sm=x.closest('.rw').querySelector('small'), nt=$('.svn',pane); let t=null;
      x.addEventListener('input',()=>{
        sm.textContent=x.value+'%';
        let ok=false; try{ok=A.setStreamVol(x.dataset.sv,+x.value)}catch(e){}
        if(nt)nt.hidden=ok;
        clearTimeout(t); t=setTimeout(()=>{try{A.playSound(x.dataset.sv)}catch(e){}},350);
      });
    });
    $$('.rg:not(.sv)',pane).forEach(x=>x.addEventListener('input',()=>{setPrefQuiet(x.dataset.s,+x.value);const sm=x.closest('.rw').querySelector('small');if(sm&&x.dataset.s==='vol')sm.textContent=x.value+'%';if(sm&&x.dataset.s==='dockSize')sm.textContent=Math.round(x.value/45*100)+'%'}));
    const un=$('#un',pane); if(un){un.addEventListener('input',()=>{lsSet('dm-user',un.value.trim()||'Người dùng')});un.addEventListener('keydown',e=>{e.stopPropagation();if(e.key==='Enter')un.blur()});un.addEventListener('pointerdown',e=>e.stopPropagation())}
  }
  function setPrefQuiet(k,v){P[k]=v;jset('dm-prefs',P);applyPrefs();bus.emit('slider')}
  live(w,'ui',()=>{const f=document.activeElement;/* đang kéo thanh trượt hoặc đang gõ chữ thì không vẽ lại, nếu không ô nhập bị mất focus và bàn phím tắt */if(f&&((f.classList&&f.classList.contains('rg'))||f.tagName==='INPUT'||f.tagName==='TEXTAREA'||f.tagName==='SELECT'))return;render()});
  /* Trang Wi-Fi / Bluetooth: tự làm mới 3 giây/lần để mạng và thiết bị mới quét được hiện ra ngay */
  const rfT=setInterval(()=>{if(!document.body.contains(w)){clearInterval(rfT);return}if(key==='wifi'||key==='bt')bus.emit('ui')},3000);
  render();
  return {open(a){if(a&&a.pane){key=a.pane;render()}}};
}
};
