package com.lirui.oneline.sync

/**
 * 抓取探针的 JS 生成（纯 Kotlin、无 Android 依赖，便于单测）。
 *
 * ★ 目的不是「一次写对抓取逻辑」，而是**探路**：用用户已登录的页面会话
 *   （同源 Cookie 自动带上）去试几个候选接口，把原始响应存到
 *   `window.__oneline_probe`，再统计关键词命中。真实响应回来之后才能确定
 *   课程列表怎么取、作业/考试在哪个节点、有没有绝对截止时间（Q1 / Q2）。
 *
 * ★ 只做 GET/POST 读取，不提交、不修改任何平台数据。
 * ★ 结果由 [ProbeRunner] 轮询取回并写到本机文件；JS 本身不落盘、不上传。
 */
object ProbeJs {

    fun build(platform: SyncPlatform, host: String): String =
        HEADER.replace("__PLATFORM__", platform.id)
            .replace("__HOST__", host) +
            when (platform) {
                SyncPlatform.KETANGPAI -> KETANGPAI
                SyncPlatform.YUKETANG -> YUKETANG
            } + FOOTER

    /** 公共脚手架：结果容器、关键词扫描、请求封装、摘要生成。 */
    private const val HEADER = """
(function(){
  var P = {state:'running', platform:'__PLATFORM__', host:location.host,
           tokenFound:false, firstCourse:null, results:[], keywords:{}, summary:''};
  window.__oneline_probe = P;
  var WORDS = ['homework','test','exam','quiz','deadline','endtime','end_time',
               'due_at','due','作业','考试','测验','截止','course_name',
               'coursename','classroom_id','courseid','title','mstatus','submit_state'];
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
    P.results.push({name:name,url:String(url).slice(0,200),status:status,ok:ok,
                    len:text.length,sample:text.slice(0,800)});
    scan(text);
  }
  function req(name,url,opts){
    opts=opts||{};
    var init={method:opts.method||'GET',credentials:'include',headers:opts.headers||{}};
    if(opts.body) init.body=JSON.stringify(opts.body);
    return fetch(url,init)
      .then(function(r){ return r.text().then(function(t){ push(name,url,r.status,r.ok,t); return {ok:r.ok,t:t}; }); })
      .catch(function(e){ push(name,url,-1,false,'ERR:'+e); return {ok:false,t:''}; });
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
    P.state='done';
  }
"""

    /**
     * 课堂派：API 在 openapiv5(x) 子域，跨域但 CORS 放行官网；需要 `token` 头。
     * token 优先从 localStorage 找（现状未知，找不到就如实报告 tokenFound=false）。
     * 依次试三个 API host，第一个通的就继续取课程 → 作业(contenttype=4) / 考试(6)。
     */
    private const val KETANGPAI = """
  var token='';
  try{
    for(var i=0;i<localStorage.length;i++){
      var k=localStorage.key(i), v=localStorage.getItem(k)||'';
      if(/token/i.test(k) && v){ token=v; break; }
    }
  }catch(e){}
  P.tokenFound=!!token;
  var apiHosts=['https://openapiv5.ketangpai.com',
                'https://openapiv52.ketangpai.com',
                'https://openapiv53.ketangpai.com'];
  (async function(){
    for(var i=0;i<apiHosts.length;i++){
      var b=apiHosts[i], h=jsonHeaders({'token':token});
      var r=await req('semesterList-'+i,b+'/CourseApi/semesterList',
        {method:'POST',headers:h,body:{isstudy:'0',search:''}});
      if(!r.ok) continue;
      var r2=await req('courseList-'+i,b+'/CourseApi/semesterCourseList',
        {method:'POST',headers:h,body:{isstudy:1,search:'',semester:'',term:''}});
      var cid=null;
      try{ var j=JSON.parse(r2.t); var d=j.data||[];
           if(d.length) cid=d[0].id||d[0].courseid; }catch(e){}
      P.firstCourse={id:cid,platform:'ketangpai'};
      if(cid){
        var common={courseid:cid,courserole:0,desc:3,dirid:0,lessonlink:[],
                    limit:50,page:1,sort:[],vtr_type:''};
        var hw=Object.assign({contenttype:4},common);
        var ex=Object.assign({contenttype:6},common);
        await req('homework-'+i,b+'/FutureV2/CourseMeans/getCourseContent',
          {method:'POST',headers:h,body:hw});
        await req('exam-'+i,b+'/FutureV2/CourseMeans/getCourseContent',
          {method:'POST',headers:h,body:ex});
      }
      break;
    }
    finish();
  })().catch(function(e){ P.error=String(e); finish(); });
"""

    /**
     * 雨课堂：mooc-api 与登录页同源，Cookie 自动带。
     * 先取本学期课程，再用第一个课程的 classroom_id / course_sign 取章节树
     * （leaf_type=6 是作业），另外试两个可能的任务聚合入口。
     */
    private const val YUKETANG = """
  var base=location.origin;
  (async function(){
    var r=await req('courses',base+'/mooc-api/v1/lms/user/user-courses/?status=1&page=1&no_page=1&term=latest');
    var cid=null, sign=null, cname=null;
    try{ var j=JSON.parse(r.t); var l=(j.data&&j.data.product_list)||[];
         if(l.length){ cid=l[0].classroom_id; sign=l[0].course_sign; cname=l[0].course_name; }
    }catch(e){}
    P.firstCourse={id:cid,sign:sign,name:cname};
    if(cid){
      var u=base+'/mooc-api/v1/lms/learn/course/chapter?cid='+cid+'&term=latest';
      if(sign) u+='&sign='+encodeURIComponent(sign);
      await req('chapter',u);
      await req('leafInfo',base+'/mooc-api/v1/lms/learn/leaf_info/'+cid+'/?term=latest');
    }
    await req('userTasks',base+'/mooc-api/v1/lms/user/user-tasks/?term=latest');
    await req('exerciseList',base+'/mooc-api/v1/lms/exercise/list/?term=latest');
    finish();
  })().catch(function(e){ P.error=String(e); finish(); });
"""

    private const val FOOTER = """
})();
"""
}
