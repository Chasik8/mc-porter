// porter:adapter (generic, client) - token matrix of the GL-backed PoseStack (transforms live in GL state).
package dev.porter.shim.mc.client;

public final class Matrix4f {
    public static final Matrix4f IDENTITY = new Matrix4f();

    public Matrix4f copy() { return this; }

    public void setIdentity() {}
}
