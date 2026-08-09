package com.dnl.appenv.pro.xposed;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import com.dnl.appenv.pro.core.Identity;
import com.dnl.appenv.pro.core.IdentityStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import io.github.libxposed.api.XposedModule;

public final class ModuleMain extends XposedModule {
  static final String TAG="AppEnvPro", PREF="appenv", SEARCH="HotUpdateSearchPaths";
  static final Set<String> SK=Set.of("key_is_login","user_login_accesskey","user_temp_accesskey");
  volatile String pkg,override=""; volatile Context ctx; volatile Identity ident; volatile long gen; volatile boolean reset;

  @Override public void onModuleLoaded(ModuleLoadedParam p){
    log(Log.INFO,TAG,"DNLAPPENV_MODULE_LOADED process="+p.getProcessName()+" framework="+getFrameworkName()+" version="+getFrameworkVersion()+" api="+getApiVersion());
  }
  @Override public void onPackageLoaded(PackageLoadedParam p){
    if(!p.isFirstPackage())return; String n=p.getPackageName(); if(!bool(n,"enabled",false))return; pkg=n;
    mark("ENTRY","package="+n+" process="+p.getProcessName()); searchHook(p.getDefaultClassLoader()); attachHook(p.getDefaultClassLoader());
  }
  boolean bool(String p,String k,boolean d){try{return getRemotePreferences(PREF).getBoolean(p+"."+k,d);}catch(Throwable t){return d;}}
  long generation(String p){try{return getRemotePreferences(PREF).getLong(p+".generation",0L);}catch(Throwable t){return 0L;}}

  void attachHook(ClassLoader cl){try{
    Method m=Application.class.getDeclaredMethod("attach",Context.class); m.setAccessible(true);
    hook(m).setPriority(PRIORITY_HIGHEST).intercept(c->{
      ctx=(Context)c.getArg(0); if(pkg==null)pkg=ctx.getPackageName(); gen=generation(pkg); ident=IdentityStore.getOrCreate(ctx,gen); reset=IdentityStore.isSessionResetPending(ctx,gen);
      TraceRecorder.init(ctx,pkg,gen,ident); mark("IDENTITY_READY","gen="+gen+" reset="+reset+" identity="+ident);
      CocosTraceBootstrap.Result b=CocosTraceBootstrap.prepare(ctx,pkg,cl,bool(pkg,"traceEnabled",true),bool(pkg,"debugEnabled",true)); if(b.ready)override=b.overrideRoot;
      mark("JSC_BOOT","ready="+b.ready+" source="+b.source+" debugPatch="+b.debugPatchCount+" trace="+b.traceInjected+" file="+b.traceFile+" message="+b.message);
      runtime(cl); return c.proceed();
    });
  }catch(Throwable t){fail("Application.attach",t);}}

  void runtime(ClassLoader cl){
    settings(Settings.Secure.class); settings(Settings.System.class); mstore(cl); appCache(cl);
    getter(cl,"com.zygote.base.library.tool.MDevice","getOaid",()->id().oaid); getter(cl,"com.zygote.base.library.tool.MDevice","getAndroidId",()->id().androidId); getter(cl,"com.zygote.base.library.tool.MDevice","getDeviceId",()->id().deviceId);
    getter(cl,"com.zygote.app.AppInfoImpl","oaid",()->id().oaid); getter(cl,"com.zygote.app.AppInfoImpl","androidId",()->id().androidId); getter(cl,"com.zygote.app.AppInfoImpl","deviceId",()->id().deviceId);
    step(cl); mnet(cl); mapBoundary(); jsBridge(cl); mark("RUNTIME_READY","package="+pkg);
  }
  Identity id(){if(ident==null)throw new IllegalStateException("identity not ready"); return ident;}

  void settings(Class<?> t){try{Method m=t.getDeclaredMethod("getString",ContentResolver.class,String.class); hook(m).intercept(c->{
    if(Settings.Secure.ANDROID_ID.equals(String.valueOf(c.getArg(1)))){mark("ANDROID_ID","value="+id().androidId);return id().androidId;}return c.proceed();});
  }catch(Throwable e){fail("Settings.getString",e);}}

  void mstore(ClassLoader cl){try{Class<?> t=Class.forName("com.zygote.base.library.tool.MStore",false,cl);
    for(Method m:t.getDeclaredMethods()){if(m.getParameterCount()<1||m.getParameterTypes()[0]!=String.class)continue; String n=m.getName(); m.setAccessible(true);
      if((n.startsWith("get")||n.startsWith("decode"))&&(m.getReturnType()==String.class||CharSequence.class.isAssignableFrom(m.getReturnType()))){hook(m).setPriority(PRIORITY_HIGHEST).intercept(c->{
        String k=String.valueOf(c.getArg(0)); if(k.endsWith("store_key_oaid")){mark("MSTORE_ID","oaid="+id().oaid);return id().oaid;} if(k.endsWith("store_key_android_id")){mark("MSTORE_ID","androidId="+id().androidId);return id().androidId;}
        if(reset&&session(k)){mark("SESSION_READ_BLOCK","key="+k);return "";} Object o=c.proceed(); if(session(k))mark("SESSION_READ",k+"="+TraceRecorder.safe(o)); return o;});
      } else if(n.startsWith("put")||n.startsWith("set")||n.startsWith("encode")||n.equals("remove")){hook(m).intercept(c->{String k=String.valueOf(c.getArg(0));if(session(k)||k.contains("store_key_"))mark("STORE_WRITE","method="+n+" key="+k);return c.proceed();});}
    }
  }catch(ClassNotFoundException e){mark("HOOK_SKIP","MStore");}catch(Throwable e){fail("MStore",e);}}

  void appCache(ClassLoader cl){try{Class<?> t=Class.forName("com.zygote.base.business.cache.AppCache$User",false,cl);for(Method m:t.getDeclaredMethods())if(m.getName().equals("getAccesskey")&&m.getParameterCount()==0){m.setAccessible(true);hook(m).setPriority(PRIORITY_HIGHEST).intercept(c->{if(reset){mark("APPCACHE_BLOCK","accessKey");return "";}Object o=c.proceed();mark("APPCACHE_ACCESS",TraceRecorder.safe(o));return o;});}}
  catch(ClassNotFoundException e){mark("HOOK_SKIP","AppCache.User");}catch(Throwable e){fail("AppCache.User",e);}}

  void step(ClassLoader cl){try{Class<?> t=Class.forName("com.zygote.base.business.enter.after.StepRegisterGuest",false,cl);for(Method m:t.getDeclaredMethods())if(m.getName().equals("doStep")){m.setAccessible(true);hook(m).setPriority(PRIORITY_HIGHEST).intercept(c->{if(reset)clear(cl);TraceRecorder.block("STEP_REGISTER BEFORE",snapshot(cl));Object o=c.proceed();TraceRecorder.block("STEP_REGISTER AFTER",snapshot(cl));return o;});}}
  catch(ClassNotFoundException e){mark("HOOK_SKIP","StepRegisterGuest");}catch(Throwable e){fail("StepRegisterGuest",e);}}

  void mnet(ClassLoader cl){try{Class<?> t=Class.forName("com.zygote.base.business.net.request.MNet",false,cl);for(Method m:t.getDeclaredMethods()){String n=m.getName();if(!n.equals("register")&&!n.equals("bindWechat"))continue;m.setAccessible(true);hook(m).setPriority(PRIORITY_HIGHEST).intercept(c->{
    if(n.equals("register")&&reset)clear(cl); TraceRecorder.block("MNET "+n+" BEFORE","args="+args(c,m.getParameterCount())+"\n"+snapshot(cl)+"\nidentity="+id());
    Object o;try{o=c.proceed();}catch(Throwable x){TraceRecorder.block("MNET "+n+" THROW",String.valueOf(x));throw x;}
    TraceRecorder.block("MNET "+n+" RETURN","return="+TraceRecorder.safe(o)+"\n"+snapshot(cl)); if(n.equals("register")&&reset&&ctx!=null){IdentityStore.markSessionResetConsumed(ctx,gen);reset=false;mark("SESSION_RESET_CONSUMED","gen="+gen);}return o;
  });}mark("HOOK_OK","MNet");}catch(ClassNotFoundException e){mark("HOOK_SKIP","MNet");}catch(Throwable e){fail("MNet",e);}}

  void mapBoundary(){try{Method m=java.util.HashMap.class.getDeclaredMethod("put",Object.class,Object.class);hook(m).intercept(c->{if(inMnet()){Object k=c.getArg(0),v=c.getArg(1);TraceRecorder.log("MNET-PAYLOAD","put "+TraceRecorder.safe(k)+"="+TraceRecorder.safe(v));if("oaid".equals(String.valueOf(k))&&!id().oaid.equals(String.valueOf(v)))mark("OAID_MISMATCH","expected="+id().oaid+" actual="+v);}return c.proceed();});}catch(Throwable e){fail("HashMap.put",e);}}
  boolean inMnet(){for(StackTraceElement e:Thread.currentThread().getStackTrace())if(e.getClassName().equals("com.zygote.base.business.net.request.MNet")&&(e.getMethodName().equals("register")||e.getMethodName().equals("bindWechat")))return true;return false;}

  void jsBridge(ClassLoader cl){try{Class<?> t=Class.forName("org.cocos2dx.javascript.bridge.JSFunction",false,cl);for(Method m:t.getDeclaredMethods()){String n=m.getName(),l=n.toLowerCase(Locale.ROOT);if(!n.equals("getCommentInfo")&&!n.equals("loginWeChat")&&!n.equals("reLoginDialog")&&!l.contains("blackbox"))continue;m.setAccessible(true);hook(m).intercept(c->{TraceRecorder.block("JS-BRIDGE "+n+" ENTER",args(c,m.getParameterCount()));Object o=c.proceed();TraceRecorder.block("JS-BRIDGE "+n+" RETURN",TraceRecorder.safe(o));return o;});}mark("HOOK_OK","JSFunction");}catch(ClassNotFoundException e){mark("HOOK_SKIP","JSFunction");}catch(Throwable e){fail("JSFunction",e);}}

  void searchHook(ClassLoader cl){try{Class<?> t=Class.forName("org.cocos2dx.lib.Cocos2dxLocalStorage",false,cl);for(Method m:t.getDeclaredMethods()){if(m.getParameterCount()<1||m.getParameterTypes()[0]!=String.class)continue;String n=m.getName();m.setAccessible(true);if(n.equals("getItem")&&(m.getReturnType()==String.class||CharSequence.class.isAssignableFrom(m.getReturnType()))){hook(m).setPriority(PRIORITY_HIGHEST).intercept(c->{Object o=c.proceed();if(SEARCH.equals(String.valueOf(c.getArg(0)))&&!override.isEmpty()){String v=CocosTraceBootstrap.mergeSearchPaths(override,o==null?"":String.valueOf(o));mark("SEARCH_PATH_GET",v);return v;}return o;});}else if(n.equals("setItem")||n.equals("removeItem")){hook(m).intercept(c->{if(SEARCH.equals(String.valueOf(c.getArg(0))))mark("SEARCH_PATH_"+n,args(c,m.getParameterCount()));return c.proceed();});}}}
  catch(ClassNotFoundException ignored){}catch(Throwable e){fail("Cocos2dxLocalStorage",e);}}

  void clear(ClassLoader cl){mark("SESSION_RESET_BEGIN",snapshot(cl));int n=0;
    try{Class<?> t=Class.forName("com.zygote.base.business.cache.AppCache$User",true,cl);Object u=instance(t);Method m=t.getDeclaredMethod("clearLoginCache");m.setAccessible(true);m.invoke(u);mark("SESSION_RESET","AppCache.clearLoginCache ok");}catch(Throwable e){TraceRecorder.log("SESSION-RESET","AppCache: "+e);}
    try{Class<?> t=Class.forName("com.zygote.base.library.tool.MStore",true,cl);Object s=instance(t);Method m=t.getDeclaredMethod("remove",String.class);m.setAccessible(true);for(String k:SK){m.invoke(s,k);n++;}}catch(Throwable e){TraceRecorder.log("SESSION-RESET","MStore: "+e);}
    try{Class<?> t=Class.forName("com.tencent.mmkv.MMKV",true,cl);Method d=t.getDeclaredMethod("defaultMMKV");d.setAccessible(true);Object x=d.invoke(null);if(x!=null){Method m=t.getDeclaredMethod("removeValueForKey",String.class);m.setAccessible(true);for(String k:SK){m.invoke(x,k);n++;}}}catch(Throwable e){TraceRecorder.log("SESSION-RESET","MMKV: "+e);}
    mark("SESSION_RESET_DONE","removed="+n+" "+snapshot(cl));
  }

  String snapshot(ClassLoader cl){String a="",u="",anon="";try{Class<?> t=Class.forName("com.zygote.base.business.cache.AppCache$User",true,cl);Object x=instance(t);a=str(t,x,"getAccesskey");u=first(str(t,x,"getUserId"),str(t,x,"getUserid"),uid(a));Object q=call(t,x,"isAnonymous");if(q==null)q=call(t,x,"getAnonymous");if(q!=null)anon=String.valueOf(q);}catch(Throwable ignored){}return "userId="+empty(u)+" accessKey="+empty(a)+" isAnonymous="+empty(anon)+" resetPending="+reset;}
  Object instance(Class<?> t)throws Exception{Field f=t.getDeclaredField("INSTANCE");f.setAccessible(true);return f.get(null);}
  Object call(Class<?> t,Object x,String n){try{Method m=t.getDeclaredMethod(n);m.setAccessible(true);return m.invoke(x);}catch(Throwable e){return null;}}
  String str(Class<?> t,Object x,String n){Object v=call(t,x,n);return v instanceof String?(String)v:"";}
  String uid(String a){if(a==null)return "";int i=a.lastIndexOf('_');return i>=0&&i+1<a.length()?a.substring(i+1):"";}
  String first(String...v){if(v!=null)for(String x:v)if(x!=null&&!x.isEmpty())return x;return "";} String empty(String v){return v==null||v.isEmpty()?"<empty>":v;}

  void getter(ClassLoader cl,String cn,String mn,Supplier<String> s){try{Class<?> t=Class.forName(cn,false,cl);for(Method m:t.getDeclaredMethods())if(m.getName().equals(mn)&&m.getParameterCount()==0&&(m.getReturnType()==String.class||CharSequence.class.isAssignableFrom(m.getReturnType()))){m.setAccessible(true);hook(m).intercept(c->{String v=s.get();mark("IDENTITY_GETTER",cn+"."+mn+"="+v);return v;});}}catch(ClassNotFoundException ignored){}catch(Throwable e){fail(cn+"."+mn,e);}}
  boolean session(String k){if(k==null)return false;String n=k.toLowerCase(Locale.ROOT);for(String s:SK)if(n.equals(s)||n.endsWith(s))return true;return false;}
  String args(Object c,int count){Object[] v=new Object[count];try{Method g=c.getClass().getMethod("getArg",int.class);for(int i=0;i<count;i++)v[i]=g.invoke(c,i);}catch(Throwable ignored){}return TraceRecorder.args(v);}
  void mark(String c,String m){try{log(Log.INFO,TAG,"DNLAPPENV_"+c+" "+m);}catch(Throwable ignored){}try{TraceRecorder.log(c,m);}catch(Throwable ignored){}}
  void fail(String w,Throwable t){try{log(Log.WARN,TAG,"DNLAPPENV_HOOK_FAIL target="+w,t);}catch(Throwable ignored){}try{TraceRecorder.log("HOOK-FAIL",w+" "+t);}catch(Throwable ignored){}}
}
