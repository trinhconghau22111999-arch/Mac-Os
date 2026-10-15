/* Nạp lười: mã app "camera". Không chạy trực tiếp: index.html (lazyBuild) lấy nội dung hàm bọc rồi eval trong phạm vi của nó, nên dùng chung mọi biến của index.html. */
window.__LZ=window.__LZ||{};
window.__LZ.camera=function(){
/* ---------- Webcam ---------- */
function buildCamera(b,w){
  const SWI='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12a8 8 0 0 1 13.5-5.8L20 8.5M20 4v4.5h-4.5M20 12a8 8 0 0 1-13.5 5.8L4 15.5M4 20v-4.5h4.5"/></svg>';
  b.innerHTML='<div class="cm"><h2>Webcam</h2><div class="cmv"><video class="cmvid" playsinline muted autoplay></video><div class="cmmsg" hidden></div><div class="cmblink"></div></div><div class="cmst"></div><div class="cmbt"><button type="button" class="cmth" aria-label="Ảnh vừa chụp"><img alt=""></button><button type="button" class="cmsh" aria-label="Chụp ảnh"><i></i></button><button type="button" class="cmsw" aria-label="Đổi camera trước/sau">'+SWI+'</button></div></div>';
  const vid=$('.cmvid',b), msg=$('.cmmsg',b), blk=$('.cmblink',b), st=$('.cmst',b), th=$('.cmth',b), thi=$('img',th), sh=$('.cmsh',b), sw=$('.cmsw',b);
  let stream=null, face=lsGet('dm-camface')==='user'?'user':'environment', tok=0, pend=0, saved=false;
  const warn=(t,m)=>notify({app:'Webcam',title:t,body:m,icon:'camera'});
  const say=t=>{msg.textContent=t||'';msg.hidden=!t};
  function stopStream(){
    tok++;
    if(stream){try{stream.getTracks().forEach(t=>t.stop())}catch(e){}stream=null}
    try{vid.srcObject=null}catch(e){}
  }
  /* Camera trước: lật gương cả khung xem trước lẫn ảnh lưu. Camera sau: giữ nguyên */
  async function open(){
    stopStream(); const my=tok; pend=my; say('');
    st.textContent=face==='user'?'Camera trước':'Camera sau'; vid.classList.toggle('mir',face==='user');
    try{
      if(!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia)){say('Thiết bị này không hỗ trợ camera trong ứng dụng.');return}
      let s;
      try{s=await navigator.mediaDevices.getUserMedia({audio:false,video:{facingMode:{ideal:face},width:{ideal:2560},height:{ideal:1920}}})}
      catch(e){if(my===tok)say('Chưa mở được Camera. Hãy cấp quyền Camera cho Desktop Mini rồi bấm vào đây để thử lại.');return}
      if(my!==tok||!w.isConnected){s.getTracks().forEach(t=>t.stop());return}
      stream=s; vid.srcObject=s;
      const p=vid.play(); if(p&&p.catch)p.catch(()=>{});
    }finally{if(pend===my)pend=0}
  }
  let shotSeq=0, lastSeq=0, lastId=0;
  function blink(){blk.classList.add('on');setTimeout(()=>blk.classList.remove('on'),120)}
  function shoot(){
    if(!stream||!vid.videoWidth)return;
    const vw=vid.videoWidth, vh=vid.videoHeight, mir=face==='user';
    /* Chụp ngay khung hình hiện tại (nhanh), phần mã hóa JPEG và lưu chạy nền để chụp tiếp được liền */
    const c=document.createElement('canvas'); c.width=vw; c.height=vh;
    const g=c.getContext('2d');
    if(mir){g.translate(vw,0);g.scale(-1,1)}
    g.drawImage(vid,0,0,vw,vh);
    blink();
    try{
      const k=96/Math.max(vw,vh), t=document.createElement('canvas');
      t.width=Math.max(1,Math.round(vw*k)); t.height=Math.max(1,Math.round(vh*k));
      t.getContext('2d').drawImage(c,0,0,t.width,t.height);
      thi.src=t.toDataURL('image/jpeg',0.7); th.classList.add('has');
    }catch(e){}
    const A=window.AndroidLauncher, seq=++shotSeq;
    const done=url=>{
      if(!url){warn('Không chụp được','Thử lại.');return}
      if(A&&A.savePhoto){
        let p=''; try{p=A.savePhoto(url)}catch(e){}
        if(!p){warn('Không lưu được ảnh','Kiểm tra quyền truy cập ảnh/tệp rồi thử lại.');return}
        const m=/\/(\d+)$/.exec(String(p));
        if(seq>=lastSeq){lastSeq=seq;lastId=m?+m[1]:0}
        saved=true; bus.emit('files');
      }else{
        const a=document.createElement('a'); a.href=url; a.download='IMG_'+Date.now()+'.jpg'; a.click();
      }
    };
    if(c.toBlob){
      c.toBlob(bl=>{
        if(!bl){done('');return}
        const fr=new FileReader(); fr.onload=()=>done(String(fr.result)); fr.onerror=()=>done(''); fr.readAsDataURL(bl);
      },'image/jpeg',0.9);
    }else{
      let u=''; try{u=c.toDataURL('image/jpeg',0.9)}catch(e){}
      setTimeout(()=>done(u),0);
    }
  }
  sh.addEventListener('click',shoot);
  sw.addEventListener('click',()=>{face=face==='user'?'environment':'user';lsSet('dm-camface',face);open()});
  th.addEventListener('click',()=>{if(!th.classList.contains('has')||!saved)return;if(lastId)openApp('photos',{pid:lastId,name:'Ảnh vừa chụp'});else openApp('photos')});
  msg.addEventListener('click',open);
  /* Nhả camera khi app ra nền hoặc cửa sổ bị thu nhỏ để không giữ camera và đèn báo */
  const onVis=()=>{if(document.hidden)stopStream();else if(w.isConnected&&!w.classList.contains('min'))open()};
  document.addEventListener('visibilitychange',onVis);
  const mon=setInterval(()=>{
    if(!w.isConnected)return;
    const mn=w.classList.contains('min');
    if(mn&&stream)stopStream();
    else if(!mn&&!stream&&!pend&&!document.hidden&&msg.hidden)open();
  },700);
  open();
  return {destroy(){clearInterval(mon);document.removeEventListener('visibilitychange',onVis);stopStream()}};
}
};
