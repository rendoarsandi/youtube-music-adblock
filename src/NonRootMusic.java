import java.io.StringWriter;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.*;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.iface.debug.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableLineNumber;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.reandroid.apk.ApkModule;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.arsc.chunk.xml.*;

/** Exact-version local adaptation of upstream GmsCore routing and the ad hook.
 * Upstream sources retained in upstream-gms/. Runtime behavior is unverified.
 * No account handling, networking, target execution or installation here.
 */
public final class NonRootMusic {
    static final String FROM = "com.google.android.apps.youtube.music";
    static final String TO = "app.revanced.android.apps.youtube.music";
    static final String GMS = "app.revanced.android.gms";
    static final Set<String> GMS_PERMISSIONS = Set.of(
        "com.google.android.providers.gsf.permission.READ_GSERVICES", "com.google.android.c2dm.permission.RECEIVE",
        "com.google.android.c2dm.permission.SEND", "com.google.android.gtalkservice.permission.GTALK_SERVICE",
        "com.google.android.googleapps.permission.GOOGLE_AUTH", "com.google.android.googleapps.permission.GOOGLE_AUTH.cp",
        "com.google.android.googleapps.permission.GOOGLE_AUTH.local", "com.google.android.googleapps.permission.GOOGLE_AUTH.mail",
        "com.google.android.googleapps.permission.GOOGLE_AUTH.writely", "com.google.android.gms.permission.ACTIVITY_RECOGNITION",
        "com.google.android.gms.permission.AD_ID", "com.google.android.gms.permission.AD_ID_NOTIFICATION",
        "com.google.android.gms.auth.api.phone.permission.SEND", "com.google.android.gms.permission.CAR_INFORMATION",
        "com.google.android.gms.permission.CAR_SPEED", "com.google.android.gms.permission.CAR_FUEL",
        "com.google.android.gms.permission.CAR_MILEAGE", "com.google.android.gms.permission.CAR_VENDOR_EXTENSION",
        "com.google.android.gms.locationsharingreporter.periodic.STATUS_UPDATE", "com.google.android.gms.auth.permission.GOOGLE_ACCOUNT_CHANGE");
    static final Set<String> GMS_AUTHORITIES = Set.of(
        "com.google.android.gms.fileprovider", "com.google.android.gms.auth.accounts", "com.google.android.gms.chimera",
        "com.google.android.gms.fonts", "com.google.android.gms.phenotype", "com.google.android.gsf.gservices",
        "com.google.settings", "subscribedfeeds");
    static final Set<String> APP_PERMISSIONS = new HashSet<>(Set.of("org.microg.gms.STATUS_BROADCAST", "org.microg.gms.EXTENDED_ACCESS", "org.microg.gms.PROVISION"));
    static final Set<String> APP_AUTHORITIES = new HashSet<>();
    static final Map<String, Integer> CHANGES = new TreeMap<>();
    static final Set<String> SEEN_HOOKS = new HashSet<>();
    static final Map<String, String> HOOKS = Map.of(
        "Lkku;->b(Landroid/content/Context;I)I", "availability",
        "Lkku;->d(Landroid/content/Context;I)V", "service-check",
        "Lqci;->a()Z", "prime",
        "Lxhv;->a(Llnh;)V", "cast-context",
        "Laty;->d(Landroid/content/Context;Ljava/lang/String;)Leff;", "file-provider",
        "Lacec;->d(Z)V", "ads");

    static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    static void count(String value) { CHANGES.merge(value, 1, Integer::sum); }
    static String replacePrefix(String value, String from, String to) {
        return value.startsWith(from) ? value.replace(from, to) : to + "." + value;
    }
    static String transform(String s) {
        if (s.equals("com.google") || s.equals("com.google.android.gms") || GMS_PERMISSIONS.contains(s) || GMS_AUTHORITIES.contains(s))
            return replacePrefix(s, "com.google", "app.revanced");
        if (APP_PERMISSIONS.contains(s) || APP_AUTHORITIES.contains(s)) return replacePrefix(s, FROM, TO);
        if (s.startsWith("content://")) {
            try {
                String authority = URI.create(s).getAuthority();
                if (GMS_AUTHORITIES.contains(authority)) return s.replace(authority, replacePrefix(authority, "com.google", "app.revanced"));
                if (APP_AUTHORITIES.contains(authority)) return s.replace(authority, replacePrefix(authority, FROM, TO));
            } catch (IllegalArgumentException ignored) { }
        }
        return s;
    }
    static String string(Instruction i) {
        if (i.getOpcode() != Opcode.CONST_STRING && i.getOpcode() != Opcode.CONST_STRING_JUMBO) return null;
        return ((StringReference)((ReferenceInstruction)i).getReference()).getString();
    }
    static String id(Method m) { return m.toString(); }
    static void named(ResXmlElement e, String name, String value) {
        int resource = name.equals("name") ? 0x01010003 : name.equals("value") ? 0x01010024 : 0x01010001;
        e.getOrCreateAndroidAttribute(name, resource).setValueAsString(value);
    }
    static void metadata(ResXmlElement app, String name, String value) {
        ResXmlElement e = app.newElement("meta-data"); named(e, "name", name); named(e, "value", value);
    }
    static void patchManifest(ApkModule module) {
        AndroidManifestBlock manifest = module.getAndroidManifest();
        require(FROM.equals(manifest.getPackageName()) && manifest.getVersionCode() == 94051240, "Wrong merged target");
        manifest.ensureFullClassNames();
        ResXmlElement root = manifest.getManifestElement(), app = manifest.getApplicationElement();
        Iterator<ResXmlElement> permissions = root.getElements("permission");
        while (permissions.hasNext()) {
            ResXmlAttribute a = permissions.next().searchAttributeByResourceId(0x01010003);
            if (a != null) APP_PERMISSIONS.add(a.getValueAsString());
        }
        for (ResXmlElement provider : manifest.listApplicationElementsByTag("provider")) {
            ResXmlAttribute a = provider.searchAttributeByResourceId(0x01010018);
            if (a == null) continue;
            String[] authorities = a.getValueAsString().split(";");
            for (int i = 0; i < authorities.length; i++) {
                APP_AUTHORITIES.add(authorities[i]); authorities[i] = replacePrefix(authorities[i], FROM, TO);
            }
            a.setValueAsString(String.join(";", authorities));
        }
        Iterator<ResXmlElement> elements = root.recursiveElements();
        while (elements.hasNext()) {
            ResXmlElement e = elements.next();
            for (int attrId : new int[]{0x01010003, 0x01010006, 0x01010007, 0x01010008}) {
                ResXmlAttribute a = e.searchAttributeByResourceId(attrId);
                if (a == null) continue;
                String s = a.getValueAsString();
                if (s != null && (APP_PERMISSIONS.contains(s) || GMS_PERMISSIONS.contains(s))) a.setValueAsString(transform(s));
            }
        }
        module.setPackageName(TO);
        for (com.reandroid.arsc.chunk.PackageBlock tablePackage : module.getTableBlock()) {
            if (FROM.equals(tablePackage.getName())) tablePackage.setName(TO);
        }
        manifest.setApplicationLabel("YT Music (Ad test)");
        named(manifest.getMainActivity(), "label", "YT Music (Ad test)");
        ResXmlElement query = root.getOrCreateElement("queries").newElement("package"); named(query, "name", GMS);
        metadata(app, GMS + ".SPOOFED_PACKAGE_NAME", FROM);
        metadata(app, GMS + ".SPOOFED_PACKAGE_SIGNATURE", "afb0fed5eeaebdd86f56a97742f4b6b33ef59875");
        metadata(app, "app.revanced.MICROG_PACKAGE_NAME", GMS);
        module.refreshManifest();
        module.refreshTable();
    }

    static Method patch(Method method) {
        MethodImplementation body = method.getImplementation();
        if (body == null) return ImmutableMethod.of(method);
        String hook = HOOKS.get(id(method));
        List<Instruction> original = new ArrayList<>(); body.getInstructions().forEach(original::add);
        Set<String> strings = new HashSet<>(); for (Instruction i : original) { String s = string(i); if (s != null) strings.add(s); }
        if (hook != null) {
            require(SEEN_HOOKS.add(hook), "Duplicate hook " + hook);
            switch (hook) {
                case "availability": require(strings.containsAll(Set.of("This should never happen.", "MetadataValueReader", "com.google.android.gms")), "Availability fingerprint changed"); break;
                case "service-check": require(strings.contains("Google Play Services not available"), "Service fingerprint changed"); break;
                case "cast-context": require(strings.contains("Error fetching CastContext."), "Cast fingerprint changed"); break;
                case "prime": require(strings.containsAll(Set.of("com.android.vending", "com.google.android.GoogleCamera", FROM)), "Prime fingerprint changed"); break;
                case "file-provider": require(strings.containsAll(Set.of("android.support.FILE_PROVIDER_PATHS", "Name must not be empty")), "Provider fingerprint changed"); break;
                case "ads":
                    require(body.getRegisterCount() == 2 && body.getTryBlocks().isEmpty() && original.size() == 3
                        && original.get(0).getOpcode() == Opcode.IPUT_BOOLEAN && original.get(1).getOpcode() == Opcode.INVOKE_VIRTUAL
                        && original.get(2).getOpcode() == Opcode.RETURN_VOID, "Ad setter changed");
                    require(((ReferenceInstruction)original.get(0)).getReference().toString().equals("Lacec;->d:Z"), "Ad field changed");
                    require(((ReferenceInstruction)original.get(1)).getReference().toString().equals("Lacec;->b()V"), "Ad callback changed"); break;
            }
            count("hook:" + hook);
        }
        List<Instruction> code = new ArrayList<>(); boolean modified = false;
        for (Instruction i : original) {
            String s = string(i), replacement = s == null ? null : transform(s);
            if ("prime".equals(hook) && FROM.equals(s)) replacement = TO;
            if (s != null && !s.equals(replacement)) {
                int register = ((OneRegisterInstruction)i).getRegisterA();
                i = i.getOpcode() == Opcode.CONST_STRING ? new ImmutableInstruction21c(Opcode.CONST_STRING, register, new ImmutableStringReference(replacement))
                    : new ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, register, new ImmutableStringReference(replacement));
                modified = true; count("string:" + s + " -> " + replacement);
            }
            code.add(i);
        }
        MethodImplementation updated = new ImmutableMethodImplementation(body.getRegisterCount(), code, body.getTryBlocks(), body.getDebugItems());
        if ("availability".equals(hook)) {
            updated = new ImmutableMethodImplementation(body.getRegisterCount(), List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0), new ImmutableInstruction11x(Opcode.RETURN, 0)), List.of(), List.of());
        } else if ("service-check".equals(hook) || "cast-context".equals(hook)) {
            updated = new ImmutableMethodImplementation(body.getRegisterCount(), List.of(new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of());
        } else if ("ads".equals(hook)) {
            code.add(0, new ImmutableInstruction11n(Opcode.CONST_4, 1, 0));
            List<DebugItem> debug = new ArrayList<>();
            for (DebugItem d : body.getDebugItems()) {
                require(d instanceof LineNumber, "Ad debug item changed");
                debug.add(new ImmutableLineNumber(d.getCodeAddress() + 1, ((LineNumber)d).getLineNumber()));
            }
            updated = new ImmutableMethodImplementation(body.getRegisterCount(), code, body.getTryBlocks(), debug);
        } else if ("file-provider".equals(hook)) {
            require(body.getRegisterCount() == 12 && (method.getAccessFlags() & 8) != 0, "Provider frame changed");
            MutableMethodImplementation provider = new MutableMethodImplementation(updated);
            // Twenty code units preserve the existing payload alignment.
            provider.addInstruction(0, new BuilderInstruction10x(Opcode.NOP)); Label ignore = provider.newLabelForIndex(0);
            provider.addInstruction(0, new BuilderInstruction21c(Opcode.CONST_STRING, 11, new ImmutableStringReference(TO + ".fileprovider"))); Label fix = provider.newLabelForIndex(0);
            provider.addInstruction(0, new BuilderInstruction10t(Opcode.GOTO, ignore));
            ImmutableMethodReference equals = new ImmutableMethodReference("Ljava/util/Objects;", "equals", List.of("Ljava/lang/Object;", "Ljava/lang/Object;"), "Z");
            for (String authority : List.of(FROM + ".fileprovider", "com.google.android.youtube.fileprovider")) {
                provider.addInstruction(0, new BuilderInstruction21t(Opcode.IF_NEZ, 0, fix));
                provider.addInstruction(0, new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
                provider.addInstruction(0, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, 11, 0, 0, 0, 0, equals));
                provider.addInstruction(0, new BuilderInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference(authority)));
            }
            updated = provider;
        }
        if (!modified && hook == null) return ImmutableMethod.of(method);
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(), method.getReturnType(),
            method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), updated);
    }
    static String render(ClassDef c) throws Exception {
        BaksmaliOptions options = new BaksmaliOptions(); options.apiLevel = 36; options.accessorComments = false;
        StringWriter buffer = new StringWriter();
        BaksmaliWriter writer = new BaksmaliWriter(buffer) {
            @Override public void writeAnnotationElements(Collection<? extends AnnotationElement> elements) throws java.io.IOException {
                // Annotation member order is not semantic; the DEX writer sorts it.
                List<AnnotationElement> sorted = new ArrayList<>(elements);
                sorted.sort(Comparator.comparing(AnnotationElement::getName));
                super.writeAnnotationElements(sorted);
            }
        };
        ClassDefinition definition = new ClassDefinition(options, c); definition.writeTo(writer); writer.flush();
        require(!definition.hadValidationErrors(), "Invalid disassembly " + c.getType());
        return canonicalAnnotations(buffer.toString()).replaceAll("(?m)^(\\.field [^\\n]*:Z) = false$", "$1")
            .replaceAll("(?m)^(\\.field [^\\n]*:[BSCIJ]) = 0x0(?:L|t|s)?$", "$1")
            .replaceAll("(?m)^(\\.field [^\\n]*:[FD]) = 0\\.0f?$", "$1")
            .replaceAll("(?m)^(\\.field [^\\n]*:(?:L[^\\n]*;|\\[[^\\n]*)) = null$", "$1");
    }
    static String canonicalAnnotations(String text) {
        // A DEX annotation set is unordered. Sort only adjacent complete annotation
        // blocks separated by whitespace; array values retain their original order.
        Matcher matcher = Pattern.compile("(?ms)^([ \\t]*)\\.annotation [^\\n]+\\n.*?^\\1\\.end annotation(?:\\n|$)").matcher(text);
        List<int[]> ranges = new ArrayList<>(); List<String> indents = new ArrayList<>();
        while (matcher.find()) { ranges.add(new int[]{matcher.start(), matcher.end()}); indents.add(matcher.group(1)); }
        StringBuilder result = new StringBuilder(); int cursor = 0;
        for (int i = 0; i < ranges.size();) {
            int end = i;
            while (end + 1 < ranges.size() && indents.get(end + 1).equals(indents.get(i))
                && text.substring(ranges.get(end)[1], ranges.get(end + 1)[0]).isBlank()) end++;
            result.append(text, cursor, ranges.get(i)[0]);
            List<String> blocks = new ArrayList<>();
            for (int j = i; j <= end; j++) blocks.add(text.substring(ranges.get(j)[0], ranges.get(j)[1]).stripTrailing());
            Collections.sort(blocks); result.append(String.join("\n\n", blocks)).append("\n");
            cursor = ranges.get(end)[1]; i = end + 1;
        }
        return result.append(text.substring(cursor)).toString();
    }
    static void rewriteDex(ApkModule module, String name, byte[] original, Path output) throws Exception {
        DexBackedDexFile before = new DexBackedDexFile(null, original); List<ClassDef> expectedClasses = new ArrayList<>();
        for (ClassDef c : before.getClasses()) {
            List<Method> direct = new ArrayList<>(), virtual = new ArrayList<>();
            for (Method m : c.getDirectMethods()) direct.add(patch(m));
            for (Method m : c.getVirtualMethods()) virtual.add(patch(m));
            expectedClasses.add(new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(), c.getSourceFile(), c.getAnnotations(), c.getStaticFields(), c.getInstanceFields(), direct, virtual));
        }
        ImmutableDexFile expected = new ImmutableDexFile(before.getOpcodes(), expectedClasses);
        Path dexPath = output.resolve(name); require(!Files.exists(dexPath), "DEX output exists"); DexPool.writeTo(dexPath.toString(), expected);
        DexBackedDexFile actual = new DexBackedDexFile(null, Files.readAllBytes(dexPath)); Map<String, ClassDef> actualClasses = new HashMap<>();
        for (ClassDef c : actual.getClasses()) actualClasses.put(c.getType(), c);
        require(expected.getClasses().size() == actualClasses.size(), "Class count changed");
        for (ClassDef c : expected.getClasses()) {
            ClassDef a = actualClasses.get(c.getType());
            require(a != null, "Missing assembled class " + c.getType());
            String wanted = render(c), found = render(a);
            if (!wanted.equals(found)) {
                Files.writeString(output.resolve("mismatch-expected.smali"), wanted);
                Files.writeString(output.resolve("mismatch-actual.smali"), found);
                throw new IllegalStateException("Round-trip differs: " + c.getType());
            }
            if (c.getType().equals("Lacec;") || c.getType().equals("Lkku;") || c.getType().equals("Lqci;") || c.getType().equals("Lxhv;") || c.getType().equals("Laty;")) Files.writeString(output.resolve(c.getType().substring(1,c.getType().length()-1) + ".smali"), render(a));
        }
        module.add(new ByteInputSource(Files.readAllBytes(dexPath), name));
        System.out.println("Validated assembled " + name + ": " + actualClasses.size() + " classes.");
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]).toAbsolutePath(); require(!Files.exists(output), "Output directory exists"); Files.createDirectories(output);
        try (ApkModule module = ApkModule.loadApkFile(Path.of(args[0]).toFile()); ZipFile zip = new ZipFile(args[0])) {
            patchManifest(module);
            List<String> dexFiles = zip.stream().map(ZipEntry::getName).filter(n -> n.matches("classes(?:[2-9][0-9]*)?\\.dex")).sorted().toList();
            require(dexFiles.size() == 6, "Unexpected DEX count");
            for (String name : dexFiles) rewriteDex(module, name, zip.getInputStream(zip.getEntry(name)).readAllBytes(), output);
            require(SEEN_HOOKS.equals(new HashSet<>(HOOKS.values())), "Missing hooks " + SEEN_HOOKS);
            module.setApkSignatureBlock(null); module.removeInputSource("stamp-cert-sha256");
            for (com.reandroid.archive.InputSource input : module.listInputSources()) {
                if (input.getAlias().matches("META-INF/(?:MANIFEST\\.MF|[^/]+\\.(?:SF|RSA|DSA|EC))")) module.removeInputSource(input.getAlias());
            }
            module.removeInputSource("assets/dexopt/baseline.prof"); module.removeInputSource("assets/dexopt/baseline.profm");
            module.refreshManifest(); module.refreshTable();
            module.writeApk(output.resolve("music-unsigned.apk").toFile());
        }
        List<String> lines = new ArrayList<>(); CHANGES.forEach((key, value) -> lines.add(value + "\t" + key)); Files.write(output.resolve("changes.tsv"), lines);
        System.out.println("Built " + TO + "; hooks " + SEEN_HOOKS + "; not runtime verified.");
    }
}
