package com.desktopmini.launcher

// Cac doan JavaScript chay trong trang web cua trinh duyet (tach tu BrowserHost.kt).

// Script chay trong moi trang: bat form dang nhap + dien san mat khau da luu.
internal val PW_JS = """
(function(){
 if(window.__wbPw||!window.AndroidPw)return; window.__wbPw=true;
 function findUser(form,pw){var c=form.querySelectorAll('input[type="text"],input[type="email"],input[autocomplete="username"],input:not([type])');for(var i=0;i<c.length;i++){if(c[i]!==pw&&c[i].value)return c[i];}return null;}
 var lastKey='',lastT=0;
 function trySave(pw){try{if(!pw||!pw.value)return;var scope=pw.closest('form')||document;var u=findUser(scope,pw);var uv=u?u.value:'';var key=uv+'|'+pw.value;var now=Date.now();if(key===lastKey&&now-lastT<4000)return;lastKey=key;lastT=now;AndroidPw.postMessage(JSON.stringify({t:'save',u:uv,p:pw.value}));}catch(x){}}
 document.addEventListener('submit',function(e){try{var f=e.target;if(!(f instanceof HTMLFormElement))return;trySave(f.querySelector('input[type="password"]'));}catch(x){}},true);
 document.addEventListener('keydown',function(e){try{if(e.key!=='Enter')return;var el=e.target;if(!el||el.tagName!=='INPUT')return;var pw=el.type==='password'?el:(el.closest('form')||document).querySelector('input[type="password"]');trySave(pw);}catch(x){}},true);
 document.addEventListener('click',function(e){try{var t=e.target&&e.target.closest?e.target.closest('button,input[type="submit"],input[type="button"],[role="button"]'):null;if(!t)return;var pws=document.querySelectorAll('input[type="password"]');for(var i=0;i<pws.length;i++){if(pws[i].value){trySave(pws[i]);break;}}}catch(x){}},true);
 function fill(u,p){var pw=document.querySelector('input[type="password"]');if(!pw)return;pw.value=p;pw.dispatchEvent(new Event('input',{bubbles:true}));pw.dispatchEvent(new Event('change',{bubbles:true}));
  if(u){var scope=pw.closest('form')||document;var ui=scope.querySelector('input[type="text"],input[type="email"],input[autocomplete="username"],input:not([type])');if(ui){ui.value=u;ui.dispatchEvent(new Event('input',{bubbles:true}));ui.dispatchEvent(new Event('change',{bubbles:true}));}}}
 AndroidPw.onmessage=function(ev){try{var m=JSON.parse(ev.data);if(m.t==='fill')fill(m.u,m.p);}catch(x){}};
 var asked=false;
 function check(){try{var has=!!document.querySelector('input[type="password"]');if(has&&!asked){asked=true;AndroidPw.postMessage(JSON.stringify({t:'ask'}));}else if(!has){asked=false;}}catch(x){}}
 try{var ob=new MutationObserver(check);function start(){if(document.documentElement){ob.observe(document.documentElement,{childList:true,subtree:true});check();}}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();}catch(x){}
})();
"""

// Xoa `del` ky tu truoc con tro roi chen `ins` (nhap chu tu dien thoai qua Truyen tep).
internal val TEXT_EDIT_FN = """
function(del, ins) {
  var el = document.activeElement;
  try {
    while (el && el.tagName === 'IFRAME' && el.contentDocument && el.contentDocument.activeElement) {
      el = el.contentDocument.activeElement;
    }
  } catch (e) {}
  if (!el) return false;
  var doc = el.ownerDocument;
  function stepBack(str, idx, n) {
    while (n > 0 && idx > 0) {
      idx--;
      var c = str.charCodeAt(idx);
      if (c >= 0xDC00 && c <= 0xDFFF && idx > 0) {
        var h = str.charCodeAt(idx - 1);
        if (h >= 0xD800 && h <= 0xDBFF) idx--;
      }
      n--;
    }
    return idx;
  }
  function fire(target) {
    try {
      target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: ins ? 'insertText' : 'deleteContentBackward', data: ins || null }));
    } catch (e) {
      target.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }
  if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
    var v = el.value;
    var s = null, e2 = null;
    try { s = el.selectionStart; e2 = el.selectionEnd; } catch (err) {}
    if (s === null || e2 === null) { s = e2 = v.length; }
    var start = (e2 === s) ? stepBack(v, s, del) : s;
    try {
      el.setRangeText(ins, start, e2, 'end');
    } catch (err) {
      var proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, v.slice(0, start) + ins + v.slice(e2));
    }
    fire(el);
    return true;
  }
  if (el.isContentEditable) {
    for (var k = 0; k < del; k++) doc.execCommand('delete');
    if (ins) doc.execCommand('insertText', false, ins);
    return true;
  }
  return false;
}
"""

// Tu choi/chan link khi nguoi dung TAT "mo link 1 cham" (giong ban may tinh): chan cham vao <a href>
// va window.open() de tranh lo tay mo trang quang cao. Link '#...' va 'javascript:' van chay binh thuong.
internal val ONE_TAP_JS = """
(function(){
 if(window.__wbOneTapInstalled)return; window.__wbOneTapInstalled=true;
 if(typeof window.__wbOneTap==='undefined')window.__wbOneTap=true;
 function blockable(a){var h=a.getAttribute('href')||'';return h!==''&&h.charAt(0)!=='#'&&h.slice(0,11).toLowerCase()!=='javascript:';}
 function block(e){
  if(window.__wbOneTap!==false)return;
  var t=e.target;var a=(t&&t.closest)?t.closest('a[href]'):null;
  if(!a||!blockable(a))return;
  e.preventDefault();e.stopImmediatePropagation();
 }
 ['click','auxclick','mousedown','pointerdown','touchend'].forEach(function(n){document.addEventListener(n,block,true);});
 try{var no=window.open;window.open=function(){if(window.__wbOneTap===false)return null;return no.apply(window,arguments);};}catch(x){}
})();
"""

// YouTube: tu bam "Bo qua" quang cao, tua nhanh quang cao khong bo qua duoc (tat tieng roi nhay den cuoi),
// va an banner/quang cao hien thi. Bat/tat qua window.__wbAdSkip.
internal val YT_AD_JS = """
(function(){
 if(window.__wbYtInstalled)return; window.__wbYtInstalled=true;
 if(typeof window.__wbAdSkip==='undefined')window.__wbAdSkip=true;
 var HIDE='.ytp-ad-overlay-container,.ytp-ad-text-overlay,.ytp-ad-image-overlay,.video-ads,#player-ads,ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-in-feed-ad-layout-renderer,ytd-ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ytm-promoted-video-renderer{display:none!important}';
 var SKIP=['.ytp-ad-skip-button','.ytp-ad-skip-button-modern','.ytp-skip-ad-button','.videoAdUiSkipButton','.ytp-ad-skip-button-container button','.ytp-skip-ad button'];
 var mutedByUs=false,fastByUs=false;
 function tick(){
  try{
   var on=window.__wbAdSkip!==false;
   var st=document.getElementById('__wb_adcss');
   if(on&&!st&&document.documentElement){st=document.createElement('style');st.id='__wb_adcss';st.textContent=HIDE;document.documentElement.appendChild(st);}
   else if(!on&&st){st.parentNode.removeChild(st);}
   var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
   var p=document.querySelector('.html5-video-player');
   var ad=!!(p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')));
   if(on&&ad){
    for(var i=0;i<SKIP.length;i++){var b=document.querySelector(SKIP[i]);if(b)b.click();}
    if(v){
     if(!v.muted){v.muted=true;mutedByUs=true;}
     if(v.duration&&isFinite(v.duration)){v.currentTime=v.duration;}else{v.playbackRate=16;fastByUs=true;}
    }
   }else if(v){
    if(mutedByUs){v.muted=false;mutedByUs=false;}
    if(fastByUs){v.playbackRate=1;fastByUs=false;}
   }
  }catch(e){}
 }
 setInterval(tick,250);
})();
"""
