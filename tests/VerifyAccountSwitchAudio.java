import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the actual APK's account/entitlement listeners and account-scoped
 * preference adapter. Inputs are synthetic accounts and an in-memory Android
 * preference backend; no application launch, real accounts, or network.
 */
public final class VerifyAccountSwitchAudio {
 static ClassLoader loader;
 static Object unsafe;
 static Method allocate;
 static Class<?> type(String name)throws Exception{return Class.forName(name,true,loader);}
 static Object blank(String name)throws Exception{return allocate.invoke(unsafe,type(name));}
 static void field(Object owner,String name,Object value)throws Exception {
  Field field=owner.getClass().getField(name);field.setAccessible(true);field.set(owner,value);
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args) {
  try { run(args); } catch(Throwable failure) { failure.printStackTrace(System.out); System.exit(1); }
 }
 static void run(String[] args)throws Exception {
  loader=(ClassLoader)Class.forName("dalvik.system.DexClassLoader")
   .getConstructor(String.class,String.class,String.class,ClassLoader.class)
   .newInstance(args[0],args[1],null,VerifyAccountSwitchAudio.class.getClassLoader());
  Class<?> u=Class.forName("sun.misc.Unsafe");Field singleton=u.getDeclaredField("theUnsafe");singleton.setAccessible(true);
  unsafe=singleton.get(null);allocate=u.getMethod("allocateInstance",Class.class);
  Class<?> accountType=type("zsb"), providerType=type("zsc");
  final AtomicReference<Object> current=new AtomicReference<>();
  Map<String,Object> accounts=new HashMap<>();
  for(String name:new String[]{"fixture-A","fixture-B"}) {
   final String id=name;
   accounts.put(id,Proxy.newProxyInstance(loader,new Class<?>[]{accountType},(p,m,a)-> {
    if(m.getName().equals("k"))return id;
    throw new UnsupportedOperationException(m.getName());
   }));
  }
  current.set(accounts.get("fixture-A"));
  Object provider=Proxy.newProxyInstance(loader,new Class<?>[]{providerType},(p,m,a)-> {
   if(m.getName().equals("e"))return current.get();
   throw new UnsupportedOperationException(m.getName());
  });
  Class<?> mapType=type("j$.util.concurrent.ConcurrentHashMap");
  Object cache=mapType.getConstructor().newInstance();Method put=mapType.getMethod("put",Object.class,Object.class);
  put.invoke(cache,"fixture-A",Boolean.TRUE);put.invoke(cache,"fixture-B",Boolean.FALSE);
  Object eligibility=blank("jlm");field(eligibility,"a",provider);field(eligibility,"c",cache);
  final Map<String,Boolean> saved=new HashMap<>(),pending=new HashMap<>();
  final String key="_pref_key_dont_play_video";
  saved.put("fixture-A"+key,true);saved.put("fixture-B"+key,true);
  Class<?> prefsType=Class.forName("android.content.SharedPreferences"),editorType=Class.forName("android.content.SharedPreferences$Editor");
  Object editor=Proxy.newProxyInstance(loader,new Class<?>[]{editorType},(p,m,a)-> {
   if(m.getName().equals("putBoolean")){pending.put((String)a[0],(Boolean)a[1]);return p;}
   if(m.getName().equals("apply")){saved.putAll(pending);pending.clear();return null;}
   throw new UnsupportedOperationException(m.getName());
  });
  Object prefs=Proxy.newProxyInstance(loader,new Class<?>[]{prefsType},(p,m,a)-> {
   if(m.getName().equals("edit"))return editor;
   throw new UnsupportedOperationException(m.getName());
  });
  Object scopedPrefs=type("jit").getConstructor(providerType,prefsType).newInstance(provider,prefs);
  Object handler=blank("hzu");field(handler,"j",eligibility);field(handler,"b",scopedPrefs);
  Constructor<?> listenerConstructor=type("hzr").getDeclaredConstructor(type("hzu"));listenerConstructor.setAccessible(true);
  Object listener=listenerConstructor.newInstance(handler);
  Method accountChanged=type("hzr").getDeclaredMethod("v",accountType);accountChanged.setAccessible(true);
  Method entitlementChanged=type("hzr").getDeclaredMethod("w",accountType,type("jlm"));entitlementChanged.setAccessible(true);
  Method allowed=type("jlm").getMethod("C");
  String[] order={"fixture-A","fixture-B","fixture-A"};
  for(int step=0;step<order.length;step++) {
   String id=order[step];
   current.set(accounts.get(id));accountChanged.invoke(listener,current.get());
   if(step==2) {put.invoke(cache,id,Boolean.FALSE);entitlementChanged.invoke(listener,current.get(),eligibility);}
   check(Boolean.TRUE.equals(saved.get(id+key)),"Account/entitlement change erased audio-only preference for "+id);
   check(Boolean.TRUE.equals(allowed.invoke(eligibility)),"Audio-only availability remained locked after account change");
  }
  saved.put("fixture-A"+key,false);accountChanged.invoke(listener,current.get());
  check(Boolean.FALSE.equals(saved.get("fixture-A"+key)),"Intentional video mode was overwritten");
  Class<?> diagnostic=null;
  try { diagnostic=type("app.local.youtube.adblock.RuntimeDiagnostics"); } catch(ClassNotFoundException noLocalTrace) {}
  if(diagnostic!=null) {
  Method snapshot=diagnostic.getDeclaredMethod("report");snapshot.setAccessible(true);
  String report=(String)snapshot.invoke(null);
  check(report.contains("account_changes=4")&&report.contains("entitlement_changes=1"),"Native account callback trace did not capture the actual events");
  check(!report.contains("fixture-A")&&!report.contains("fixture-B"),"Runtime report exposed an account identifier");
  System.out.println("PASS: real account callback diagnostics capture event counts and omit account identifiers");
  }
  System.out.println("PASS: actual account and entitlement listeners preserve audio-only preferences across A-B-A switches and refreshed false eligibility");
  System.out.println("PASS: exclusive-audio eligibility stays enabled while intentional video preference remains unchanged");
 }
}
