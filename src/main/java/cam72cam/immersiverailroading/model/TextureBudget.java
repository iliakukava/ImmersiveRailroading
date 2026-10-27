package cam72cam.immersiverailroading.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decides which packed texture sheets are worth keeping.
 *
 * <p>UMC stitches every material of a model into one atlas and then keeps a full copy of that
 * atlas for the device maximum plus every requested LOD. A single 4096 sheet is sixteen times
 * the texels of a 1024 sheet, and specular/normal channels multiply it again. The budget keeps
 * the sheets that rendering actually samples and names the oversized ones so they can be released.
 */
public final class TextureBudget {
    private TextureBudget() {
    }

    /**
     * LOD edge lengths to ask the loader for, in addition to the full atlas it always builds.
     * Sizes at or above {@code deviceMax} are omitted because they would duplicate that atlas.
     */
    public static List<Integer> lodSizes(int deviceMax, boolean compact, int nearEdge, int farEdge) {
        List<Integer> sizes = new ArrayList<>();
        if (!compact) {
            addUnique(sizes, 1024, deviceMax);
            addUnique(sizes, 512, deviceMax);
            return sizes;
        }
        if (farEdge > 0) {
            addUnique(sizes, farEdge, deviceMax);
        }
        if (nearEdge > 0) {
            addUnique(sizes, nearEdge, deviceMax);
        }
        return sizes;
    }

    /**
     * @param sheetEdges keys of the sheets that were built for one variant and channel
     * @param budget     largest edge that should stay resident
     * @return edges whose sheets should be dropped. The smallest sheet is always kept so a model
     * whose atlas is already under the budget, or whose only sheet is the full atlas, still renders.
     */
    public static List<Integer> sheetsToDrop(Collection<Integer> sheetEdges, int budget) {
        List<Integer> drop = new ArrayList<>();
        if (sheetEdges == null || sheetEdges.isEmpty() || budget <= 0) {
            return drop;
        }
        int smallest = Integer.MAX_VALUE;
        boolean withinBudget = false;
        for (int edge : sheetEdges) {
            smallest = Math.min(smallest, edge);
            if (edge <= budget) {
                withinBudget = true;
            }
        }
        for (int edge : sheetEdges) {
            if (edge > budget && (withinBudget || edge != smallest)) {
                drop.add(edge);
            }
        }
        return drop;
    }

    private static void addUnique(List<Integer> sizes, int edge, int deviceMax) {
        if (edge > 0 && edge < deviceMax && !sizes.contains(edge)) {
            sizes.add(edge);
        }
    }
}
