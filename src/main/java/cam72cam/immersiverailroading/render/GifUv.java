package cam72cam.immersiverailroading.render;

import cam72cam.mod.model.common.mesh.Model;
import cam72cam.mod.model.common.mesh.ModelGroup;
import cam72cam.mod.model.common.mesh.VAOLayout;
import cam72cam.mod.render.common.ModelRenderer;

import java.util.HashSet;
import java.util.Set;

/**
 * Stretches one group's atlas UVs back to 0..1 so a standalone GIF fills those faces.
 */
public final class GifUv {
    private static final Set<String> DONE = new HashSet<>();

    private GifUv() {
    }

    public static void remapOnce(Model model, String group) {
        if (model == null || group == null) {
            return;
        }
        String key = System.identityHashCode(model) + "|" + group;
        if (!DONE.add(key)) {
            return;
        }
        ModelGroup mesh = model.getGroups().get(group);
        if (mesh == null) {
            return;
        }
        float[] vbo = model.getVboData();
        VAOLayout layout = model.getLayout();
        int uv = layout.getOffset(VAOLayout.Usage.UV);
        if (uv < 0 || vbo == null) {
            return;
        }
        int stride = layout.getStride();
        float minU = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int face = mesh.faceStart; face <= mesh.faceEnd; face++) {
            for (int corner = 0; corner < 3; corner++) {
                int index = (face * 3 + corner) * stride + uv;
                minU = Math.min(minU, vbo[index]);
                maxU = Math.max(maxU, vbo[index]);
                minV = Math.min(minV, vbo[index + 1]);
                maxV = Math.max(maxV, vbo[index + 1]);
            }
        }
        float spanU = Math.max(maxU - minU, 1e-5f);
        float spanV = Math.max(maxV - minV, 1e-5f);
        for (int face = mesh.faceStart; face <= mesh.faceEnd; face++) {
            for (int corner = 0; corner < 3; corner++) {
                int index = (face * 3 + corner) * stride + uv;
                vbo[index] = (vbo[index] - minU) / spanU;
                vbo[index + 1] = (vbo[index + 1] - minV) / spanV;
            }
        }
        ModelRenderer.release(model);
    }
}
