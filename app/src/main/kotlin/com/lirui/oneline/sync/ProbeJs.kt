package com.lirui.oneline.sync

/**
 * 抓取探针的 JS 生成（纯 Kotlin、无 Android 依赖，便于单测）。
 *
 * ★ 目的不是「一次写对抓取逻辑」，而是**探路**：用用户已登录的页面会话
 *   去试候选接口，把原始响应存进 `window.__oneline_probe`，再统计关键词。
 *   真实响应回来之后才能确定课程列表怎么取、作业/考试在哪个节点、
 *   有没有绝对截止时间（Q1 / Q2）。
 *
 * ★ §73 第二轮升级：
 *   1. sample 从 800 字扩到 12000 字，完整响应不再被截断；
 *   2. 雨课堂直接解析章节树，输出 leaf_type 分布与有绝对截止时间的节点；
 *   3. 课堂派跨域带自定义头会触发 CORS 预检失败（实测 Failed to fetch），
 *      补同源主站 / 无自定义头 / 旧 v4 域等候选，并记录页面里能看到的凭证键名。
 *   4. 只记**键名**不记值，不上传、不改任何平台数据。
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
           tokenFound:false, firstCourse:null, results:[], keywords:{},
           nodes:null, leafTypeCounts:null, withDeadline:0, summary:'',
           credentials:{localKeys:[],sessionKeys:[],cookieKeys:[]}};
  window.__oneline_probe = P;
  var WORDS = ['homework','test','exam','quiz','deadline','endtime','end_time',
               'due_at','due','作业','考试','测验','截止','course_name',
               'coursename','classroom_id','courseid','title','mstatus','submit_state'];
  // 只收集键名，绝不记录值或 Cookie 内容。
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
  function push(name,url,status,ok,text){
    text=String(text||'');
    P.results.push({name:name,url:String(url).slice(0,220),status:status,ok:ok,
                    len:text.length,sample:text.slice(0,12000)});
    scan(text);
  }
  function req(name,url,opts){
    opts=opts||{};
    var init={method:opts.method||'GET',credentials:'include',headers:opts.headers||{}};
    if(opts.body) init.body=JSON.stringify(opts.body);
    return fetch(url,init)
      .then(function(r){ return r.text().then(function(t){ push(name,url,r.status,r.ok,t); return {ok:r.ok,t:t}; }); })
      .catch(function(e){ push(name,url,-1,false,'ERR:'+e+' @'+url); return {ok:false,t:''}; });
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
      P.summary+='\n章节节点：'+P.nodes.length+' / leaf_type '+JSON.stringify(P.leafTypeCounts||{})+
                 ' / 有绝对截止 '+P.withDeadline+' 个';
      var ex=P.nodes.filter(function(n){return (n.end_time||0)>0;}).slice(0,3);
      for(var z=0;z<ex.length;z++) P.summary+='\n例：'+ex[z].name+' end_time='+ex[z].end_time;
    }
    P.summary+='\n凭证键名：local['+P.credentials.localKeys.slice(0,8).join(',')+
               '] cookie['+P.credentials.cookieKeys.slice(0,8).join(',')+']';
    P.state='done';
  }
"""

    /**
     * 课堂派：API 在 openapiv5(x) 子域，跨域且带 token 自定义头 → 实测 CORS 预检
     * 全部 `Failed to fetch`。这里同时试：同源主站代理、无自定义头的简单请求、
     * 旧 v4 域，以及原来的三个 API 域，把每条的失败原因都记下来。
     */
    private const val KETANGPAI = """
  var token='';
  try{
    for(var i=0;i<localStorage.length;i++){
      var k=localStorage.key(i), v=localStorage.getItem(k)||'';
      if(/token/i.test(k) && v){ token=v; break; }
    }
    if(!token) for(var j=0;j<sessionStorage.length;j++){
      var sk=sessionStorage.key(j), sv=sessionStorage.getItem(sk)||'';
      if(/token/i.test(sk) && sv){ token=sv; break; }
    }
  }catch(e){}
  P.tokenFound=!!token;
  var h=jsonHeaders({'token':token});
  var simple={'Content-Type':'text/plain'};
  var apiHosts=['https://openapiv5.ketangpai.com',
                'https://openapiv52.ketangpai.com',
                'https://openapiv53.ketangpai.com'];
  (async function(){
    // 1) 同源主站：若前端走主站代理，这些请求不会触发 CORS。
    await req('main-semesterList','/CourseApi/semesterList',
      {method:'POST',headers:h,body:{isstudy:'0',search:''}});
    await req('main-courseList','/CourseApi/semesterCourseList',
      {method:'POST',headers:h,body:{isstudy:1,search:'',semester:'',term:''}});
    // 2) 不带自定义头 + text/plain，避开 CORS 预检。
    await req('api-simple','https://openapiv5.ketangpai.com/CourseApi/semesterList',
      {method:'POST',headers:simple,body:{isstudy:'0',search:''}});
    // 3) 旧 v4 域：早期版本是纯 Cookie 鉴权，不需要 token。
    await req('v4-semesterList','https://v4.ketangpai.com/CourseApi/semesterList',
      {method:'POST',headers:simple,body:{isstudy:'0',search:''}});
    // 4) 原三域（保留原始口径，方便对比）。
    for(var i=0;i<apiHosts.length;i++){
      var b=apiHosts[i];
      var r=await req('semesterList-'+i,b+'/CourseApi/semesterList',
        {method:'POST',headers:h,body:{isstudy:'0',search:''}});
      if(!r.ok) continue;
      var r2=await req('courseList-'+i,b+'/CourseApi/semesterCourseList',
        {method:'POST',headers:h,body:{isstudy:1,search:'',semester:'',term:''}});
      var cid=null;
      try{ var jj=JSON.parse(r2.t); var d=jj.data||[];
           if(d.length) cid=d[0].id||d[0].courseid; }catch(e){}
      P.firstCourse={id:cid,platform:'ketangpai'};
      if(cid){
        var common={courseid:cid,courserole:0,desc:3,dirid:0,lessonlink:[],
                    limit:50,page:1,sort:[],vtr_type:''};
        await req('homework-'+i,b+'/FutureV2/CourseMeans/getCourseContent',
          {method:'POST',headers:h,body:Object.assign({contenttype:4},common)});
        await req('exam-'+i,b+'/FutureV2/CourseMeans/getCourseContent',
          {method:'POST',headers:h,body:Object.assign({contenttype:6},common)});
      }
      break;
    }
    finish();
  })().catch(function(e){ P.error=String(e); finish(); });
"""

    /**
     * 雨课堂：mooc-api 同源，`xtbz:cloud` 是必需头（§72）。
     * 课程列表 → 章节树（含作业/课件/考试节点）→ 解析所有节点的
     * leaf_type / end_time / score_deadline，直接回答 Q2。
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
    var cid=null, sign=null, cname=null;
    try{ var j=JSON.parse(r.t); var l=(j.data&&j.data.product_list)||[];
         if(l.length){ cid=l[0].classroom_id; sign=l[0].course_sign; cname=l[0].course_name; }
    }catch(e){}
    P.firstCourse={id:cid,sign:sign,name:cname};
    if(cid){
      var u=base+'/mooc-api/v1/lms/learn/course/chapter?cid='+cid+'&term=latest';
      if(sign) u+='&sign='+encodeURIComponent(sign);
      var ch=await req('chapter',u,{headers:H});
      try{
        var cj=JSON.parse(ch.t), nodes=[];
        function walk(arr){
          if(!arr) return;
          for(var i=0;i<arr.length;i++){
            var n=arr[i];
            if(n && (n.leaf_type!==undefined || n.name)){
              nodes.push({name:n.name,leaf_type:n.leaf_type,id:n.id,
                          end_time:n.end_time,score_deadline:n.score_deadline,
                          start_time:n.start_time});
            }
            if(n && n.leaf_list) walk(n.leaf_list);
            if(n && n.section_leaf_list) walk(n.section_leaf_list);
          }
        }
        var ccs=(cj.data&&cj.data.course_chapter)||[];
        for(var c=0;c<ccs.length;c++) walk(ccs[c].section_leaf_list);
        P.nodes=nodes.slice(0,80);
        var counts={}, withEnd=0;
        for(var q=0;q<nodes.length;q++){
          var lt=nodes[q].leaf_type; counts[lt]=(counts[lt]||0)+1;
          if((nodes[q].end_time||0)>0 || (nodes[q].score_deadline||0)>0) withEnd++;
        }
        P.leafTypeCounts=counts; P.withDeadline=withEnd;
      }catch(e){ P.parseError=String(e); }
      await req('leafInfo',base+'/mooc-api/v1/lms/learn/leaf_info/'+cid+'/?term=latest',{headers:H});
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
