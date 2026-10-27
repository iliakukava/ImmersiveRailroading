package cam72cam.immersiverailroading.model;

import cam72cam.immersiverailroading.ConfigGraphics;
import cam72cam.mod.model.common.mesh.Model;
import cam72cam.mod.render.obj.OBJTextureSheet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Releases packed texture sheets that rendering will not sample.
 *
 * <p>The loader still has to build the full atlas once, because that is how UMC bakes UVs.
 * After the model exists, every sheet larger than the configured budget is removed from the
 * live maps and its GPU allocation is dropped. The smaller LOD sheets share the same UVs and
 * are what {@code ModelConfig.lod} selects, so steady-state RAM and VRAM track the budget
 * instead of one atlas per variant at the device maximum.
 */
public final class TextureMemory {
    private TextureMemory() {
    }

    public static List<Integer> lodSizes(int deviceMax) {
        return TextureBudget.lodSizes(deviceMax, ConfigGraphics.compactTextureMemory,
                ConfigGraphics.stockTextureBudget, ConfigGraphics.stockFarTextureSize);
    }

    public static void retain(Model model) {
        if (model == null || !ConfigGraphics.compactTextureMemory) {
            return;
        }
        int budget = ConfigGraphics.stockTextureBudget;
        prune(model.getTextures(), budget);
        if (model.hasSpecular) {
            prune(model.getSpeculars(), budget);
        }
        if (model.hasNormal) {
            prune(model.getNormals(), budget);
        }
    }

    static void prune(Map<String, NavigableMap<Integer, OBJTextureSheet>> sheets, int budget) {
        if (sheets == null) {
            return;
        }
        for (NavigableMap<Integer, OBJTextureSheet> lods : sheets.values()) {
            if (lods == null || lods.isEmpty()) {
                continue;
            }
            List<Integer> drop = TextureBudget.sheetsToDrop(new ArrayList<>(lods.keySet()), budget);
            for (Integer edge : drop) {
                OBJTextureSheet sheet = lods.remove(edge);
                if (sheet != null) {
                    sheet.dealloc();
                }
            }
        }
    }
}
