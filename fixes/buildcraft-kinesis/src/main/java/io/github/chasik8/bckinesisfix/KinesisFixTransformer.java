package io.github.chasik8.bckinesisfix;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * BuildCraft 7.1.23, {@code TileBuildCraft.receiveEnergy(ForgeDirection from, int maxReceive, boolean simulate)}:
 *
 * <pre>
 *   received = battery.receiveEnergy(Math.min(maxReceive, battery.getMaxEnergyReceive() - receivedTick), simulate);
 * </pre>
 *
 * {@code receivedTick} (energy already received this tick) is subtracted even for a simulated call. A kinesis pipe
 * first delivers power to the machine and then, in the same tick, asks it (simulate = true) how much it wants next
 * tick; the machine answers 0 because its per-tick limit is used up, so the pipe requests power only every other tick.
 *
 * <p>Patch: when {@code simulate} is true, use 0 instead of {@code receivedTick}. Real receives keep the per-tick
 * limit. Bytecode after {@code GETFIELD receivedTick}: {@code ILOAD 3; IFEQ L; POP; ICONST_0; L:}.
 */
public class KinesisFixTransformer implements IClassTransformer, Opcodes {

    static final String TARGET = "buildcraft.core.lib.block.TileBuildCraft";
    static final String OWNER = "buildcraft/core/lib/block/TileBuildCraft";
    static final String METHOD = "receiveEnergy";
    static final String DESC = "(Lnet/minecraftforge/common/util/ForgeDirection;IZ)I";
    static final int SIMULATE_SLOT = 3;

    private static final Logger LOG = LogManager.getLogger("BCKinesisFix");

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !TARGET.equals(transformedName)) {
            return bytes;
        }
        try {
            byte[] out = patch(bytes);
            if (out == null) {
                LOG.warn("TileBuildCraft.receiveEnergy does not look like BuildCraft 7.1.23; the kinesis fix is NOT "
                        + "applied (the class is left unchanged).");
                return bytes;
            }
            LOG.info("Patched TileBuildCraft.receiveEnergy: simulated receives no longer count energy already "
                    + "received this tick.");
            return out;
        } catch (Throwable t) {
            LOG.warn("Kinesis fix failed, the class is left unchanged", t);
            return bytes;
        }
    }

    /** Returns the patched class, or null if the expected instructions are not found exactly once. */
    static byte[] patch(byte[] bytes) {
        ClassNode cn = new ClassNode();
        // version 50 class: drop stack map frames, the JVM verifies it by type inference
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
        MethodNode target = null;
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (m.name.equals(METHOD) && m.desc.equals(DESC) && (m.access & ACC_STATIC) == 0) {
                target = m;
            }
        }
        if (target == null) {
            return null;
        }
        AbstractInsnNode hit = null;
        int hits = 0;
        for (AbstractInsnNode n = target.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == GETFIELD) {
                FieldInsnNode f = (FieldInsnNode) n;
                if (f.owner.equals(OWNER) && f.name.equals("receivedTick") && f.desc.equals("I")) {
                    AbstractInsnNode sub = next(n);
                    AbstractInsnNode min = sub == null ? null : next(sub);
                    if (sub != null && sub.getOpcode() == ISUB && min instanceof MethodInsnNode
                            && min.getOpcode() == INVOKESTATIC
                            && ((MethodInsnNode) min).owner.equals("java/lang/Math")
                            && ((MethodInsnNode) min).name.equals("min")
                            && ((MethodInsnNode) min).desc.equals("(II)I")) {
                        hit = n;
                        hits++;
                    }
                }
            }
        }
        if (hits != 1) {
            return null;
        }
        LabelNode keep = new LabelNode();
        InsnList fix = new InsnList();
        fix.add(new VarInsnNode(ILOAD, SIMULATE_SLOT));
        fix.add(new JumpInsnNode(IFEQ, keep));
        fix.add(new InsnNode(POP));
        fix.add(new InsnNode(ICONST_0));
        fix.add(keep);
        target.instructions.insert(hit, fix);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Next real instruction (skips labels, line numbers and frames). */
    private static AbstractInsnNode next(AbstractInsnNode n) {
        AbstractInsnNode x = n.getNext();
        while (x != null && x.getOpcode() < 0) {
            x = x.getNext();
        }
        return x;
    }
}
