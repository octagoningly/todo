package com.lirui.oneline.sync

/**
 * 登录页的**只读布局体检**（§81）。
 *
 * ★ 为什么需要它：二维码「只显示一半」这件事，我们已经盲改了三轮
 *   （§70.10 注入 CSS、§77 改 padding、§80 开 overview 模式），用户反馈都是"没变"。
 *   盲改的根本问题是**不知道实际数字**：
 *   - 到底是**左右**被裁（viewport 比布局窄）？
 *   - 还是**上下**被裁（二维码在视口外，需要滚动）？
 *   - 还是二维码元素本身被父容器裁掉（overflow:hidden + 固定高度）？
 *
 *   这三种的原因和解法完全不同。这里只做**测量与上报**，不改任何页面样式。
 *
 * ★ 输出走 `console.log`，由 WebChromeClient.onConsoleMessage 捕获后打 logcat，
 *   用 `adb logcat -s OnelineProbe:D` 就能看到，不需要用户操作、不需要代码回灌。
 */
object LayoutDiag {

    /**
     * 测量脚本：视口尺寸、二维码元素位置、是否溢出。
     *
     * 找二维码用启发式：`img` / `canvas` 里宽高在 120~400 之间的、或者
     * class/id 含 qrcode / qr / code 的元素。雨课堂用的是 canvas 或 img。
     */
    val JS = """
(function(){
  function num(v){ return Math.round(v||0); }
  try {
    var de = document.documentElement, b = document.body;
    var out = {
      innerW: num(window.innerWidth),
      innerH: num(window.innerHeight),
      clientW: num(de.clientWidth),
      clientH: num(de.clientHeight),
      scrollW: num(de.scrollWidth),
      scrollH: num(de.scrollHeight),
      dpr: window.devicePixelRatio,
      ua: (navigator.userAgent||'').slice(0,120)
    };
    // 找二维码候选元素
    var cands = [];
    var all = document.querySelectorAll('img,canvas,div');
    for (var i=0;i<all.length && cands.length<6;i++){
      var el = all[i];
      var r = el.getBoundingClientRect();
      var w = r.width, h = r.height;
      if (w < 110 || h < 110 || w > 460 || h > 460) continue;
      var cls = String(el.className||''), id = String(el.id||'');
      var tag = el.tagName.toLowerCase();
      var looksQr = /qr|code|erweima/i.test(cls+id+tag) ||
                    (tag==='canvas') ||
                    (tag==='img' && /base64|qr/i.test(String(el.src||'').slice(0,80)));
      if (!looksQr) continue;
      cands.push({
        tag: tag, cls: cls.slice(0,40), id: id.slice(0,20),
        left: num(r.left), top: num(r.top), w: num(w), h: num(h),
        // 相对视口是否完整可见
        visibleTop: r.top >= 0,
        visibleBottom: r.bottom <= window.innerHeight,
        visibleLeft: r.left >= 0,
        visibleRight: r.right <= window.innerWidth
      });
    }
    out.qr = cands;
    // 页面最外层是否禁用了滚动
    out.overflowHtml = de ? getComputedStyle(de).overflowY : '';
    out.overflowBody = b ? getComputedStyle(b).overflowY : '';
    out.bodyH = b ? getComputedStyle(b).height : '';
    console.log('ONELINE_LAYOUT ' + JSON.stringify(out));
  } catch(e) {
    console.log('ONELINE_LAYOUT_ERR ' + String(e));
  }
})();
"""
}
