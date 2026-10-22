package ic2cextras.asm;

import net.minecraft.launchwrapper.IClassTransformer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Gives the IC2 Classic 1.7.10 steam vent the 1.19.2 steam multiplier. In ItemReactorVentSteam every 40.0 in
 * processTick (water use per heat, heat removed per water) and in the tooltip is replaced by Hooks.getVentDivisor()
 * (= 40 / multiplier): heat removed per tick stays self * heatLevel, steam per heat becomes 4 * multiplier mB.
 */
public class VentTransformer implements IClassTransformer {

    private static final Logger LOG = LogManager.getLogger("ic2cextras-asm");
    private static final String TARGET = "ic2.core.item.reactor.ItemReactorVentSteam";
    private static final String HOOKS = "ic2cextras/asm/Hooks";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !TARGET.equals(transformedName)) return basicClass;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(basicClass).accept(node, 0);
            int patched = 0;
            for (MethodNode m : node.methods) {
                if (!m.name.equals("processTick") && !m.name.equals("addInformation") && !m.name.equals("func_77624_a"))
                    continue;
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode && Double.valueOf(40.0)
                        .equals(((LdcInsnNode) insn).cst)) {
                        MethodInsnNode call = new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            HOOKS,
                            "getVentDivisor",
                            "()D",
                            false);
                        m.instructions.set(insn, call);
                        insn = call;
                        patched++;
                    }
                }
            }
            if (patched == 0) {
                LOG.warn("No 40.0 constants found in {}, steam multiplier not applied", TARGET);
                return basicClass;
            }
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(cw);
            Hooks.ventPatched = true;
            LOG.info("Patched {} ({} constants)", TARGET, patched);
            return cw.toByteArray();
        } catch (Throwable t) {
            LOG.error("Failed to patch " + TARGET + ", leaving it unchanged", t);
            return basicClass;
        }
    }
}
