package cam72cam.immersiverailroading.model.format;

import cam72cam.mod.model.common.material.Material;
import cam72cam.mod.model.common.mesh.IModelBuilder;
import cam72cam.mod.model.common.util.MalformedModelException;
import cam72cam.mod.resource.Identifier;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * OBJ parser that keeps UMC's geometry path and also reads the material keys the stock renderer can show.
 * Dissolve ({@code d} / {@code Tr}) becomes vertex alpha for glass. Normal, roughness, specular and height
 * maps are routed into the normal and specular samplers Iris already samples (labPBR: normal RGB plus height
 * in alpha, smoothness/roughness in the specular map).
 */
public final class IrObjParser {
    private IrObjParser() {
    }

    public static void parse(IModelBuilder builder) throws IOException {
        Map<String, Material> materials = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(builder.open(builder.getModelLoc()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] args = line.split("\\s+");
                switch (args[0]) {
                    case "mtllib":
                        parseMtl(builder, args[1], materials);
                        break;
                    case "usemtl":
                        builder.setCurrentMaterial(materials.computeIfAbsent(args.length > 1 ? line.substring(7) : "undefined",
                                name -> new Material(builder, name)));
                        break;
                    case "o":
                    case "g":
                        if (args.length < 2) {
                            throw new MalformedModelException(String.format("Cannot parse group from \"%s\" of OBJ model %s", line, builder.getModelLoc()));
                        }
                        builder.newModelGroup(line.substring(2));
                        break;
                    case "v":
                        builder.addIndexedVert(Float.parseFloat(args[1]), Float.parseFloat(args[2]), Float.parseFloat(args[3]));
                        break;
                    case "vt":
                        builder.addIndexedUv(Float.parseFloat(args[1]), Float.parseFloat(args[2]));
                        break;
                    case "vn":
                        builder.addIndexedNormal(Float.parseFloat(args[1]), Float.parseFloat(args[2]), Float.parseFloat(args[3]));
                        break;
                    case "f":
                        IModelBuilder.IFaceBuilder face = builder.newFace();
                        for (int i = 1; i < args.length; i++) {
                            String[] part = args[i].split("/");
                            int pos = part.length > 0 && !part[0].trim().isEmpty() ? Integer.parseInt(part[0]) - 1 : -1;
                            int uv = part.length > 1 && !part[1].trim().isEmpty() ? Integer.parseInt(part[1]) - 1 : -1;
                            int nrm = part.length > 2 && !part[2].trim().isEmpty() ? Integer.parseInt(part[2]) - 1 : -1;
                            face.addVert(pos, uv, nrm);
                        }
                        face.end();
                        break;
                    case "s":
                        if (args.length == 2 && args[1].equals("1")) {
                            builder.doSmoothShading();
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private static void parseMtl(IModelBuilder builder, String path, Map<String, Material> materials) throws IOException {
        Identifier mtlLoc = builder.getModelLoc().getRelative(path);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(builder.open(mtlLoc), StandardCharsets.UTF_8))) {
            String name = null;
            float r = 1, g = 1, b = 1, a = 1;
            String albedo = null;
            String specular = null;
            String normal = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                switch (parts[0]) {
                    case "newmtl":
                        if (name != null) {
                            materials.put(name, build(builder, name, r, g, b, a, albedo, specular, normal));
                        }
                        name = line.substring(7);
                        r = g = b = a = 1;
                        albedo = specular = normal = null;
                        break;
                    case "Kd":
                        r = Float.parseFloat(parts[1]);
                        g = Float.parseFloat(parts[2]);
                        b = Float.parseFloat(parts[3]);
                        if (parts.length > 4) {
                            a = Float.parseFloat(parts[4]);
                        }
                        break;
                    case "d":
                        a = Float.parseFloat(parts[1]);
                        break;
                    case "Tr":
                        a = 1f - Float.parseFloat(parts[1]);
                        break;
                    case "map_Kd":
                        albedo = parts[1];
                        break;
                    case "map_Ns":
                    case "map_Ks":
                    case "map_Pr":
                    case "map_roughness":
                    case "map_Prs":
                        specular = parts[1];
                        break;
                    case "map_Bump":
                    case "bump":
                    case "map_bump":
                    case "map_Kn":
                    case "norm":
                    case "map_height":
                    case "map_Height":
                    case "disp":
                    case "map_Disp":
                        normal = parts[1];
                        break;
                    default:
                        break;
                }
            }
            if (name != null) {
                materials.put(name, build(builder, name, r, g, b, a, albedo, specular, normal));
            }
        }
    }

    private static Material build(IModelBuilder builder, String name, float r, float g, float b, float a,
                                  String albedo, String specular, String normal) {
        Material material = new Material(builder, name, r, g, b, a);
        if (albedo != null) {
            material.setAlbedo(albedo).defaultSpecular().defaultNormal();
        }
        if (specular != null) {
            material.setSpecular(specular);
        }
        if (normal != null) {
            material.setNormal(normal);
        }
        return material;
    }
}
