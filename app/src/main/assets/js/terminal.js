/* Nạp lười: mã app "terminal". Không chạy trực tiếp: index.html (lazyBuild) lấy nội dung hàm bọc rồi eval trong phạm vi của nó, nên dùng chung mọi biến của index.html. */
window.__LZ=window.__LZ||{};
window.__LZ.terminal=function(){
/* ---------- Terminal ---------- */
function buildTerminal(b,w){
  b.innerHTML='<div class="term"><div class="bar"><strong class="t">Terminal — zsh</strong></div><div class="tout" role="log"></div></div>';
  const out=$('.tout',b);
  const DIRS={Desktop:'desk',Documents:'docs',Downloads:'down',Pictures:'pics'};
  let cwd={root:null,path:[]}, hist=[], hp=0;
  const dirName=()=>{
    if(!cwd.root)return '~';
    const rn=Object.keys(DIRS).find(k=>DIRS[k]===cwd.root);
    let n='~/'+rn, l=FS[cwd.root];
    cwd.path.forEach(i=>{n+='/'+l[i].n;l=l[i].items});
    return n;
  };
  const cur=()=>cwd.root?fsList(cwd.root,cwd.path):null;
  function print(t,cls){const d=document.createElement('div');d.className='tl2'+(cls?' '+cls:'');d.textContent=t;out.appendChild(d);out.scrollTop=out.scrollHeight}
  function prompt(){
    const l=document.createElement('div'); l.className='tl2 pr';
    l.innerHTML='<span class="ps">'+esc(userName().toLowerCase().replace(/\s+/g,''))+'@desktop-mini <b>'+esc(dirName())+'</b> %</span> <input class="tin" spellcheck="false" autocomplete="off" aria-label="Dòng lệnh">';
    out.appendChild(l); const i=$('.tin',l); i.focus(); out.scrollTop=out.scrollHeight;
    i.addEventListener('keydown',e=>{
      if(e.key==='Enter'){
        const v=i.value; i.replaceWith(Object.assign(document.createElement('span'),{textContent:v})); if(v.trim()){hist.push(v);hp=hist.length}
        run(v); if(w.isConnected&&!w._closing)prompt();
      }else if(e.key==='ArrowUp'){e.preventDefault();if(hp>0){hp--;i.value=hist[hp]}}
      else if(e.key==='ArrowDown'){e.preventDefault();if(hp<hist.length-1){hp++;i.value=hist[hp]}else{hp=hist.length;i.value=''}}
    });
  }
  const find=(l,name)=>l.findIndex(x=>norm(x.n)===norm(name));
  function run(line){
    const m=line.trim().match(/^(\S+)\s*(.*)$/); if(!m)return;
    const cmd=m[1], rest=m[2].replace(/^["']|["']$/g,'');
    switch(cmd){
      case 'help':print('Lệnh có sẵn: help, ls, cd, pwd, cat, mkdir, touch, rm, echo, open, date, whoami, hostname, uname, uptime, history, neofetch, clear, exit');break;
      case 'pwd':print('/Users/'+userName().toLowerCase().replace(/\s+/g,'')+(cwd.root?dirName().slice(1):''));break;
      case 'ls':{const l=cur();print(l?(l.length?l.map(x=>x.x==='folder'?x.n+'/':x.n).join('\n'):'(trống)'):Object.keys(DIRS).map(k=>k+'/').join('\n'));break}
      case 'cd':{
        if(!rest||rest==='~'||rest==='~/'){cwd={root:null,path:[]};break}
        if(rest==='..'){if(cwd.path.length)cwd.path.pop();else cwd={root:null,path:[]};break}
        const l=cur();
        if(!l){const k=Object.keys(DIRS).find(x=>norm(x)===norm(rest.replace(/^~\//,'').replace(/\/$/,'')));if(k)cwd={root:DIRS[k],path:[]};else print('cd: không tìm thấy: '+rest,'er');break}
        const i=find(l,rest.replace(/\/$/,''));
        if(i>=0&&l[i].x==='folder')cwd.path.push(i);else print('cd: không tìm thấy thư mục: '+rest,'er');
        break}
      case 'cat':{const l=cur();if(!l){print('cat: hãy vào một thư mục trước','er');break}const i=find(l,rest);if(i<0)print('cat: không tìm thấy: '+rest,'er');else if(l[i].x==='folder')print('cat: '+rest+': là một thư mục','er');else if(l[i].x==='md')print('# '+l[i].n.replace(/\.md$/,'')+'\nNội dung mẫu của tệp văn bản này.');else print('cat: '+rest+': tệp nhị phân','er');break}
      case 'mkdir':{if(!cwd.root){print('mkdir: không thể tạo ở thư mục gốc','er');break}if(!rest){print('mkdir: thiếu tên thư mục','er');break}const l=cur();if(find(l,rest)>=0){print('mkdir: đã tồn tại: '+rest,'er');break}l.push({n:rest,d:tstamp(),s:'--',x:'folder',items:[]});saveFS();break}
      case 'touch':{if(!cwd.root){print('touch: không thể tạo ở thư mục gốc','er');break}if(!rest){print('touch: thiếu tên tệp','er');break}const l=cur();if(find(l,rest)<0){const x=(rest.split('.').pop()||'md').toLowerCase();l.push({n:rest,d:tstamp(),s:'0 KB',x:rest.indexOf('.')>0?x:'md'});saveFS()}break}
      case 'rm':{const l=cur();if(!l){print('rm: hãy vào một thư mục trước','er');break}const i=find(l,rest);if(i<0)print('rm: không tìm thấy: '+rest,'er');else{moveToTrash(cwd.root,cwd.path,i);print('Đã chuyển "'+rest+'" vào Thùng rác')}break}
      case 'echo':print(m[2]);break;
      case 'date':print(new Date().toLocaleString('vi-VN',{dateStyle:'full',timeStyle:'medium'}));break;
      case 'whoami':print(userName().toLowerCase().replace(/\s+/g,''));break;
      case 'hostname':print('desktop-mini.local');break;
      case 'uname':print('DesktopMini 26 (trình duyệt) '+(navigator.platform||''));break;
      case 'uptime':print('đã chạy '+Math.round(performance.now()/60000)+' phút');break;
      case 'history':print(hist.map((h,i)=>(i+1)+'  '+h).join('\n')||'(chưa có lệnh nào)');break;
      case 'clear':out.innerHTML='';break;
      case 'exit':w._closing=true;closeWin(w);break;
      case 'open':{
        if(!rest){print('open: thiếu tên ứng dụng','er');break}
        const id=Object.keys(APPS).find(k=>!APPS[k].hidden&&(norm(APPS[k].name)===norm(rest)||k===norm(rest)));
        if(id){openApp(id)}else print('open: không có ứng dụng "'+rest+'"','er');
        break}
      case 'neofetch':{
        const nv=navigator;
        print('   ✦        '+userName().toLowerCase().replace(/\s+/g,'')+'@desktop-mini\n  ✦ ✦       ----------------\n ✦ ✦ ✦      OS: Desktop Mini 26\n  ✦ ✦       Trình duyệt: '+(nv.platform||'?')+'\n   ✦        Màn hình: '+screen.width+'x'+screen.height+'\n            CPU: '+(nv.hardwareConcurrency||'?')+' lõi\n            Bộ nhớ: '+(nv.deviceMemory?nv.deviceMemory+' GB':'?')+'\n            Giao diện: '+(isDark()?'Tối':'Sáng'));
        break}
      default:print('zsh: không tìm thấy lệnh: '+cmd,'er');
    }
  }
  out.addEventListener('click',()=>{const i=$('.tin',out);if(i&&!getSelection().toString())i.focus()});
  print('Desktop Mini Terminal. Gõ "help" để xem các lệnh.');
  prompt();
  live(w,'fs',()=>{});
  return {};
}
};
