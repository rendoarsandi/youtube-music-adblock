import java.security.MessageDigest;
import java.util.*;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.dexbacked.instruction.DexBackedInstruction;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;

/** Instrumentation preserves native account callbacks, audio focus and gate results. */
final class RuntimeHooks {
    private static final String OWNER = "Lapp/local/youtube/adblock/RuntimeDiagnostics;";
    private record Guard(String name, int flags, int registers, String digest) {}
    private static final Map<String, Guard> TARGETS = Map.of(
        "Lhzr;->v(Lzsb;)V", new Guard("trace-account", 17, 2, "03cd9bf3d0f1be1c29d3c16f250007a1fcff2f5859c0c8e7ec48e8baa5e7719c"),
        "Lhzr;->w(Lzsb;Ljlm;)V", new Guard("trace-entitlement", 17, 3, "03cd9bf3d0f1be1c29d3c16f250007a1fcff2f5859c0c8e7ec48e8baa5e7719c"),
        "Laars;->w(Labjc;Lwei;)Z", new Guard("trace-background", 9, 2, "4c52d9cf964fd76af0702d37ded2b720e20ca60fa381eb11e8a0ffade895b87b"),
        "Laarb;->onAudioFocusChange(I)V", new Guard("trace-audio-focus", 17, 16, "ed86828751ae5769514427802750ce4304fa181a00f54195a09bd5f2b0c2e542"),
        "Lbza;->onResponseStarted(Lorg/chromium/net/UrlRequest;Lorg/chromium/net/UrlResponseInfo;)V", new Guard("trace-stream-http", 131089, 5, "67d10e4ed5c52a0fd92c594e3ef554cccd7b807c752fc74ae59fee5082a3861b"),
        "Lbza;->onFailed(Lorg/chromium/net/UrlRequest;Lorg/chromium/net/UrlResponseInfo;Lorg/chromium/net/CronetException;)V", new Guard("trace-stream-failed", 131089, 4, "74d2b509fe39fed7d4db5c16199f6b7b8dad690a76ba12ae30717b2b211ea5c4")
    );
    static String hook(String id) { Guard g = TARGETS.get(id); return g == null ? null : g.name(); }
    static Set<String> hooks() {
        Set<String> names = new HashSet<>(); for (Guard g : TARGETS.values()) names.add(g.name()); return names;
    }
    static MethodImplementation apply(Method method) {
        Guard g = TARGETS.get(method.toString()); MethodImplementation original = method.getImplementation();
        if (g == null || original == null || method.getAccessFlags() != g.flags() || original.getRegisterCount() != g.registers())
            throw new IllegalStateException("Runtime trace target frame changed: " + method);
        List<Instruction> code = new ArrayList<>(); original.getInstructions().forEach(code::add);
        DexBackedInstruction first = (DexBackedInstruction) code.get(0);
        int units = code.stream().mapToInt(Instruction::getCodeUnits).sum();
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                first.dexFile.getDataBuffer().readByteRange(first.instructionStart, units * 2)));
            if (!digest.equals(g.digest())) throw new IllegalStateException("Runtime trace instructions changed: " + method);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        MutableMethodImplementation body;
        if (g.name().equals("trace-background")) {
            // Original v0/v1 parameters are overwritten by its boolean computation.
            // Keep true input references in v3/v4; copy into old slots for native code.
            body = new MutableMethodImplementation(new com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation(
                5, code, original.getTryBlocks(), original.getDebugItems()));
            int returns = 0;
            for (int i = body.getInstructions().size() - 1; i >= 0; i--) {
                if (body.getInstructions().get(i).getOpcode() != Opcode.RETURN) continue;
                int result = ((OneRegisterInstruction) body.getInstructions().get(i)).getRegisterA();
                var displaced = body.getInstructions().get(i);
                body.replaceInstruction(i, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 3, 3, 4, result, 0, 0,
                    new ImmutableMethodReference(OWNER, "backgroundCheck", List.of("Ljava/lang/Object;", "Ljava/lang/Object;", "Z"), "V")));
                body.addInstruction(i + 1, displaced); returns++;
            }
            if (returns != 2 || !original.getTryBlocks().isEmpty()) throw new IllegalStateException("Background return layout changed");
            body.addInstruction(0, new BuilderInstruction12x(Opcode.MOVE_OBJECT, 1, 4));
            body.addInstruction(0, new BuilderInstruction12x(Opcode.MOVE_OBJECT, 0, 3));
        } else {
            body = new MutableMethodImplementation(original);
            String entry;
            int input;
            List<String> types;
            if (g.name().equals("trace-stream-http")) {
                entry = "streamResponse"; input = 4; types = List.of("Ljava/lang/Object;");
            } else if (g.name().equals("trace-stream-failed")) {
                entry = "streamFailed"; input = 2; types = List.of("Ljava/lang/Object;", "Ljava/lang/Object;");
            } else if (g.name().equals("trace-audio-focus")) {
                entry = "audioFocusChanged"; input = 15; types = List.of("I");
            } else {
                entry = g.name().equals("trace-account") ? "accountChanged" : "entitlementChanged";
                input = 0; types = List.of("Ljava/lang/Object;");
            }
            var displaced = body.getInstructions().get(0);
            body.replaceInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, input, types.size(),
                new ImmutableMethodReference(OWNER, entry, types, "V")));
            body.addInstruction(1, displaced);
        }
        return body;
    }
}
