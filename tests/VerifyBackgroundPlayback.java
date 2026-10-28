import java.lang.reflect.*;
/** Real APK eligibility regression. Synthetic native metadata; no login/network. */
public final class VerifyBackgroundPlayback {
 public static void main(String[] args) {
  try { run(args); } catch(Throwable failure) { failure.printStackTrace(System.out); System.exit(1); }
 }
 static void run(String[] args)throws Exception {
  Class<?> loaderType=Class.forName("dalvik.system.DexClassLoader");
  ClassLoader loader=(ClassLoader)loaderType.getConstructor(String.class,String.class,String.class,ClassLoader.class)
   .newInstance(args[0],args[1],null,VerifyBackgroundPlayback.class.getClassLoader());
  Class<?> responseType=Class.forName("alqw",true,loader);
  Constructor<?> responseConstructor=responseType.getDeclaredConstructor();responseConstructor.setAccessible(true);
  Object first=responseConstructor.newInstance(),second=responseConstructor.newInstance();
  Class<?> gate=Class.forName("aafk",true,loader);
  Method allowed=gate.getMethod("q",responseType);
  for(Object response:new Object[]{first,second,first}) {
   if(!((Boolean)allowed.invoke(null,response)))
    throw new AssertionError("Background playback still depends on per-response account eligibility");
  }
  System.out.println("PASS: actual APK background eligibility stays enabled across fresh playback metadata and repeated checks");
  Class<?> ads=Class.forName("acec",false,loader);ads.getDeclaredMethods();
  Class.forName("com.google.android.apps.youtube.music.activities.MusicActivity",false,loader).getDeclaredMethods();
  // ART independently verifies callback instructions against the real Cronet API.
  Class.forName("bza",false,loader).getDeclaredMethods();
  // Native w reports a block decision: enabled modality AND ineligible playable.
  // The trace wrapper must preserve that result with both parameter references intact.
  Class<?> u=Class.forName("sun.misc.Unsafe");Field singleton=u.getDeclaredField("theUnsafe");singleton.setAccessible(true);
  Object unsafe=singleton.get(null);
  Class<?> modalityType=Class.forName("abjc",true,loader),playableType=Class.forName("wei",true,loader);
  Object modality=u.getMethod("allocateInstance",Class.class).invoke(unsafe,modalityType);
  Field enabled=modalityType.getField("h");enabled.setBoolean(modality,true);
  Object playable=Proxy.newProxyInstance(loader,new Class<?>[]{playableType},(p,m,a)-> {
   if(m.getName().equals("t"))return first;
   if(m.getName().equals("V"))return false;
   throw new UnsupportedOperationException(m.getName());
  });
  Method wrapper=Class.forName("aars",true,loader).getMethod("w",modalityType,playableType);
  if(((Boolean)wrapper.invoke(null,modality,playable)))throw new AssertionError("Trace blocked eligible native playback");
  enabled.setBoolean(modality,false);
  if(((Boolean)wrapper.invoke(null,modality,playable)))throw new AssertionError("Trace changed native disabled-modality handling");
  enabled.setBoolean(modality,true);
  if(!((Boolean)wrapper.invoke(null,modality,null)))throw new AssertionError("Trace changed native missing-response handling");
  Class<?> diagnostic=null;
  try { diagnostic=Class.forName("app.local.youtube.adblock.RuntimeDiagnostics",true,loader); }
  catch(ClassNotFoundException noLocalTrace) {}
  if(diagnostic!=null) {
  Method snapshot=diagnostic.getDeclaredMethod("report");snapshot.setAccessible(true);
  String report=(String)snapshot.invoke(null);
  if(!report.contains("modality_enabled=true response_present=false"))throw new AssertionError("Trace lost native response/modality arguments");
  for(int i=0;i<80;i++)diagnostic.getMethod("audioFocusChanged",int.class).invoke(null,i%4-3);
  report=(String)snapshot.invoke(null);
  String eventText=report.substring(report.indexOf("events:\n")+8);
  if(eventText.split("\n").length>60)throw new AssertionError("Unbounded runtime event log");
  System.out.println("PASS: runtime wrapper preserves native true/false decisions, records correct arguments, and bounds its event log");
  }
  System.out.println("PASS: ART verified original ad and activity owners");
 }
}
