// porter:adapter (generic, client) - 1.19.2 PoseStack on the 1.7.10 fixed-function GL matrix stack: every pose
// operation is applied to GL immediately, so vertices submitted afterwards are transformed by GL (the Matrix4f a
// pose hands out is only a token for BufferBuilder.vertex(matrix, ...)).
package dev.porter.shim.mc.client;

import org.lwjgl.opengl.GL11;

public class PoseStack {
    private int depth;

    public void pushPose() {
        GL11.glPushMatrix();
        depth++;
    }

    public void popPose() {
        if (depth > 0) {
            GL11.glPopMatrix();
            depth--;
        }
    }

    public void translate(double x, double y, double z) { GL11.glTranslated(x, y, z); }

    public void translate(float x, float y, float z) { GL11.glTranslatef(x, y, z); }

    public void scale(float x, float y, float z) { GL11.glScalef(x, y, z); }

    public void mulPose(Quaternion q) { GL11.glRotatef(q.angle, q.x, q.y, q.z); }

    public boolean clear() { return depth == 0; }

    public Pose last() { return Pose.INSTANCE; }

    public static final class Pose {
        static final Pose INSTANCE = new Pose();

        public Matrix4f pose() { return Matrix4f.IDENTITY; }

        public Matrix4f normal() { return Matrix4f.IDENTITY; }
    }

    /** 1.19.2 Vector3f.XP.rotationDegrees(a) etc. */
    public static final class Quaternion {
        final float angle, x, y, z;

        public Quaternion(float x, float y, float z, float angle) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.angle = angle;
        }
    }
}
