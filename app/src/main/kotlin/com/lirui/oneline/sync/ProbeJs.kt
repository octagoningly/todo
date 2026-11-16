package com.lirui.oneline.sync

/**
 * 抓取探针的 JS 生成（纯 Kotlin、无 Android 依赖，便于单测）。
 *
 * ★ 目的不是一次写对抓取逻辑，而是**探路**：用用户已登录的页面会话
 *   去试候选接口，把原始响应存进 `window.__oneline_probe`。
 *
 * ★ §74 v3（依据第二次真实响应）：
 *   1. 课堂派 `document.cookie` 里有 `token`（键名 token），且不带自定义头的
 *      `text/plain` 请求能通过 CORS（返回「账号登陆已过期」而非 Failed to fetch）。
 *      → 从 Cookie / userSession 取 token，改用 body / query / form 传参，
 *        全程不加自定义头以避开预检。
 *   2. 雨课堂第一门课 17 个节点全是 leaf_type=8（课件）、end_time 全 0；
 *      用户共 2 门课，探针只扫了第一门。→ 遍历全部课程并汇总节点。
 *   3. 只记凭证**键名**和token是否找到，绝不把值写进结果文件。
 */
object ProbeJs {

    fun build(platform: SyncPlatform, host: String): String =
        HEADER.replace("__PLATFORM__", platform.id)
            .replace("__HOST__", host) +
            when (platform) {
                SyncPlatform.KETANGPAI -> KETANGPAI
                SyncPlatform.YUKETANG -> YUKETANG
            } + FOOTER

    private const val HEADER = """
(function(){
  var P = {state:'running', platform:'__PLATFORM__', host:location.host,
           tokenFound:false, cookieTokenFound:false, firstCourse:null,
           courseCount:0, results:[], keywords:{}, nodes:null,
           leafTypeCounts:null, withDeadline:0, summary:'',
           credentials:{localKeys:[],sessionKeys:[],cookieKeys:[]}};
  window.__oneline_probe = P;
  var WORDS = ['homework','test','exam','quiz','deadline','endtime','end_time',
               'due_at','due','作业','考试','测验','截止','course_name',
               'coursename','classroom_id','courseid','title','mstatus','submit_state'];
  try{ for(var a=0;a<localStorage.length;a++) P.credentials.localKeys.push(localStorage.key(a)); }catch(e){}
  try{ for(var b=0;b<sessionStorage.length;b++) P.credentials.sessionKeys.push(sessionStorage.key(b)); }catch(e){}
  try{ document.cookie.split(';').forEach(function(c){
         var k=c.split('=')[0].trim(); if(k) P.credentials.cookieKeys.push(k); }); }catch(e){}
  function scan(t){
    var s = String(t||'').toLowerCase();
    for (var i=0;i<WORDS.length;i++){
      var w=WORDS[i], k=w.toLowerCase(), n=0, p=0;
      while((p=s.indexOf(k,p))>=0){ n++; p+=k.length; }
      if(n) P.keywords[w]=(P.keywords[w]||0)+n;
    }
  }
  function maskUrl(u){
    // ★ §75：结果文件落在 /sdcard（其他应用可读的位置），token 绝不能明文进去。
    return String(u||'').replace(/token=[^&]*/gi,'token=***')
                          .replace(/token%3D[^&]*/gi,'token%3D***');
  }
  function push(name,url,status,ok,text){
    text=String(text||'');
    P.results.push({name:name,url:maskUrl(url).slice(0,220),status:status,ok:ok,
                    len:text.length,sample:text.slice(0,12000)});
    scan(text);
  }
  function req(name,url,opts){
    opts=opts||{};
    var init={method:opts.method||'GET',credentials:'include',headers:opts.headers||{}};
    if(opts.form) init.body=opts.form;
    else if(opts.body) init.body=JSON.stringify(opts.body);
    return fetch(url,init)
      .then(function(r){ return r.text().then(function(t){ push(name,url,r.status,r.ok,t); return {ok:r.ok,t:t}; }); })
      .catch(function(e){ push(name,url,-1,false,'ERR:'+e+' @'+maskUrl(url)); return {ok:false,t:''}; });
  }
  function jsonHeaders(extra){
    var h={'Content-Type':'application/json'};
    if(extra) for(var k in extra) h[k]=extra[k];
    return h;
  }
  function finish(){
    var parts=[];
    for(var i=0;i<P.results.length;i++){
      var r=P.results[i]; parts.push(r.name+' '+r.status+(r.ok?' OK':' FAIL'));
    }
    var kw=[];
    for(var k in P.keywords) kw.push(k+'x'+P.keywords[k]);
    P.summary='接口：'+(parts.join(' / ')||'无')+'\n关键词：'+(kw.join(' / ')||'无命中');
    if(P.nodes){
      P.summary+='\n课程数：'+P.courseCount+' / 节点：'+P.nodes.length+
                 ' / leaf_type '+JSON.stringify(P.leafTypeCounts||{})+
                 ' / 有绝对截止 '+P.withDeadline+' 个';
      var ex=P.nodes.filter(function(n){return (n.end_time||0)>0;}).slice(0,5);
      for(var z=0;z<ex.length;z++) P.summary+='\n例：'+ex[z].course+' / '+ex[z].name+' end_time='+ex[z].end_time;
    }
    P.summary+='\nCookie token：'+(P.cookieTokenFound?'找到':'没找到')+
               ' / 凭证键名：local['+P.credentials.localKeys.slice(0,8).join(',')+
               '] cookie['+P.credentials.cookieKeys.slice(0,8).join(',')+']';
    P.state='done';
  }
"""

    /**
     * 课堂派：带 `token` 自定义头会触发 CORS 预检并失败（实测）。
     * 改为从 Cookie / localStorage.userSession 取 token，用
     * text/plain body、URL query、x-www-form-urlencoded 三种**不触发预检**的方式传。
     */
    private const val KETANGPAI = """
  var token='';
  try{
    var mm=document.cookie.match(/(?:^|;\s*)token=([^;]+)/);
    if(mm) token=decodeURIComponent(mm[1]);
  }catch(e){}
  P.cookieTokenFound=!!token;
  if(!token){
    try{ var us=localStorage.getItem('userSession');
         if(us){ var uj=JSON.parse(us);
                  token=(uj&&uj.token)||(uj&&uj.data&&uj.data.token)||''; } }catch(e){}
  }
  P.tokenFound=!!token;
  var h=jsonHeaders({'token':token});
  var simple={'Content-Type':'text/plain'};
  var form={'Content-Type':'application/x-www-form-urlencoded'};
  var apiHosts=['https://openapiv5.ketangpai.com',
                'https://openapiv52.ketangpai.com',
                'https://openapiv53.ketangpai.com'];
  (async function(){
    // 对照：同源主站（SPA 会吞掉，返回 HTML）。
    await req('main-semesterList','/CourseApi/semesterList',
      {method:'POST',headers:h,body:{isstudy:'0',search:''}});
    await req('main-courseList','/CourseApi/semesterCourseList',
      {method:'POST',headers:h,body:{isstudy:1,search:'',semester:'',term:''}});
    // 对照：无 token 的简单请求（上次返回「账号登陆已过期」）。
    await req('api-simple','https://openapiv5.ketangpai.com/CourseApi/semesterList',
      {method:'POST',headers:simple,body:{isstudy:'0',search:''}});
    // 对照：旧 v4 域。
    await req('v4-semesterList','https://v4.ketangpai.com/CourseApi/semesterList',
      {method:'POST',headers:simple,body:{isstudy:'0',search:''}});
    var b=apiHosts[0];
    // A：token 放在 JSON body 里（text/plain，不触发预检）。
    var a=await req('api-bodyToken',b+'/CourseApi/semesterList',
      {method:'POST',headers:simple,body:{token:token,isstudy:'0',search:''}});
    // B：token 放 URL query。
    await req('api-queryToken',b+'/CourseApi/semesterList?token='+encodeURIComponent(token),
      {method:'POST',headers:simple,body:{isstudy:'0',search:''}});
    // C：表单编码（简单请求），token 随表单走。
    await req('api-formToken',b+'/CourseApi/semesterList',
      {method:'POST',headers:form,
       form:'token='+encodeURIComponent(token)+'&isstudy=0&search='});
    // 只要有一种返回可用 JSON，就继续取课程列表与作业/考试。
    var r2=await req('courseList-bodyToken',b+'/CourseApi/semesterCourseList',
      {method:'POST',headers:simple,body:{token:token,isstudy:1,search:'',semester:'',term:''}});
    var cid=null;
    try{ var jj=JSON.parse(r2.t); var d=jj.data||[];
         if(d.length) cid=d[0].id||d[0].courseid; }catch(e){}
    P.firstCourse={id:cid,platform:'ketangpai'};
    if(cid){
      var common={token:token,courseid:cid,courserole:0,desc:3,dirid:0,
                  lessonlink:[],limit:50,page:1,sort:[],vtr_type:''};
      await req('homework',b+'/FutureV2/CourseMeans/getCourseContent',
        {method:'POST',headers:simple,body:Object.assign({contenttype:4},common)});
      await req('exam',b+'/FutureV2/CourseMeans/getCourseContent',
        {method:'POST',headers:simple,body:Object.assign({contenttype:6},common)});
    }
    finish();
  })().catch(function(e){ P.error=String(e); finish(); });
"""

    /**
     * 雨课堂：`xtbz:cloud` 必需。遍历**全部**课程（上一版只扫第一门），
     * 汇总所有章节节点的 leaf_type / end_time / score_deadline。
     */
    private const val YUKETANG = """
  var base=location.origin;
  var H={'xtbz':'cloud'};
  try{
    var m=document.cookie.match(/(?:^|;\s*)csrftoken=([^;]+)/);
    if(m) H['x-csrftoken']=m[1];
    var m2=document.cookie.match(/(?:^|;\s*)university_id=([^;]+)/);
    if(m2) H['university-id']=m2[1];
  }catch(e){}
  P.probeHeaders=Object.keys(H);
  (async function(){
    var r=await req('courses',base+'/mooc-api/v1/lms/user/user-courses/?status=1&page=1&no_page=1&term=latest',{headers:H});
    var courses=[];
    try{ var j=JSON.parse(r.t); courses=(j.data&&j.data.product_list)||[]; }catch(e){}
    P.courseCount=courses.length;
    var nodes=[];
    function walk(arr,courseName){
      if(!arr) return;
      for(var i=0;i<arr.length;i++){
        var n=arr[i];
        if(n && (n.leaf_type!==undefined || n.name)){
          nodes.push({course:courseName,name:n.name,leaf_type:n.leaf_type,id:n.id,
                      end_time:n.end_time,score_deadline:n.score_deadline,
                      start_time:n.start_time});
        }
        if(n && n.leaf_list) walk(n.leaf_list,courseName);
        if(n && n.section_leaf_list) walk(n.section_leaf_list,courseName);
      }
    }
    for(var ci=0;ci<courses.length && ci<10;ci++){
      var c=courses[ci];
      var u=base+'/mooc-api/v1/lms/learn/course/chapter?cid='+c.classroom_id+'&term=latest';
      if(c.course_sign) u+='&sign='+encodeURIComponent(c.course_sign);
      var ch=await req('chapter-'+ci,u,{headers:H});
      try{
        var cj=JSON.parse(ch.t);
        var ccs=(cj.data&&cj.data.course_chapter)||[];
        for(var cc=0;cc<ccs.length;cc++) walk(ccs[cc].section_leaf_list,c.course_name);
      }catch(e){ P.parseError=String(e); }
    }
    P.nodes=nodes.slice(0,150);
    var counts={}, withEnd=0;
    for(var q=0;q<nodes.length;q++){
      var lt=nodes[q].leaf_type; counts[lt]=(counts[lt]||0)+1;
      if((nodes[q].end_time||0)>0 || (nodes[q].score_deadline||0)>0) withEnd++;
    }
    P.leafTypeCounts=counts; P.withDeadline=withEnd;
    // 作业详情候选：leaf_info 需要 <cid>/<leaf_id> 两段 id（上一版只给了一段，404）。
    var leaf=nodes.filter(function(n){return n.id;})[0];
    if(leaf && courses.length){
      await req('leafInfo',base+'/mooc-api/v1/lms/learn/leaf_info/'+
        courses[0].classroom_id+'/'+leaf.id+'/?term=latest',{headers:H});
    }
    await req('userTasks',base+'/mooc-api/v1/lms/user/user-tasks/?term=latest',{headers:H});
    await req('exerciseList',base+'/mooc-api/v1/lms/exercise/list/?term=latest',{headers:H});
    finish();
  })().catch(function(e){ P.error=String(e); finish(); });
"""

    private const val FOOTER = """
})();
"""
}
