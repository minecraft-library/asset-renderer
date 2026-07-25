package lib.minecraft.renderer.visual;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.engine.camera.Projection;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.Age;
import lib.minecraft.renderer.option.CopperWeathering;
import lib.minecraft.renderer.option.EntityAppearance;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.HorseMarking;
import lib.minecraft.renderer.option.IronGolemCrackiness;
import lib.minecraft.renderer.option.Size;
import lib.minecraft.renderer.option.TintAxis;
import lib.minecraft.renderer.option.TropicalFishPattern;
import lib.minecraft.renderer.option.VillagerLevel;
import lib.minecraft.renderer.option.VillagerProfession;
import lib.minecraft.renderer.option.VillagerType;
import lib.minecraft.renderer.option.spec.ArmorMaterial;
import lib.minecraft.renderer.option.spec.ArmorOptions;
import lib.minecraft.renderer.option.spec.ArmorPiece;
import lib.minecraft.renderer.option.spec.DyeColor;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Renders every entity from the Java-derived pipeline ({@code entity_models.json} /
 * {@code entity_geometry.json}, produced by the {@code entityModels} tooling task) through
 * {@link EntityRenderer} and writes one PNG per entity to {@code cache/visual/entity-render-3d/}
 * for visual inspection. Entities load via {@link EntityModelLoader#load()}; a single id or the
 * whole (alphabetically sorted) set can be rendered.
 * <p>
 * Each render uses the selected {@link Projection} (default {@link Projection#VANILLA_ISO}) with
 * the entity's facing applied as a model-to-world placement, so any projection in the catalog
 * presents the subject's front upright.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:entityRender3D [-PrenderSize=512] [-PentityId=minecraft:zombie] [-Pprojection=ISOMETRIC]}.
 */
@UtilityClass
public final class TestEntityRender3D {

    private static final Path OUTPUT_DIR = Path.of("cache/visual/entity-render-3d");

    /** Square edge length (pixels) for each render. */
    private static final int DEFAULT_SIZE = 512;

    /**
     * Runs the Java-pipeline entity sweep.
     *
     * @param args {@code args[0]} is an optional render size; {@code args[1]} an optional single entity id
     *     (blank to render all); {@code args[2]} an optional {@link Projection} name (default
     *     {@code VANILLA_ISO})
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_SIZE;
        Optional<String> singleEntityId = args.length > 1 && !args[1].isBlank() ? Optional.of(args[1]) : Optional.empty();
        Projection projection = args.length > 2 && !args[2].isBlank()
            ? Projection.valueOf(args[2].toUpperCase()) : Projection.VANILLA_ISO;

        Files.createDirectories(OUTPUT_DIR);

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        ConcurrentMap<String, Entity> javaEntities = EntityModelLoader.load();
        if (javaEntities.isEmpty()) {
            System.err.println("entity_models.json / entity_geometry.json not present on the classpath - run ./gradlew :asset-renderer:entityModelsJava first");
            return;
        }
        EntityRenderer renderer = new EntityRenderer(context, javaEntities);

        List<String> entityIds = singleEntityId
            .map(List::of)
            .orElseGet(() -> List.copyOf(new TreeSet<>(javaEntities.keySet())));

        System.out.printf("Rendering %d entit%s via Java pipeline at %dx%d to %s%n",
            entityIds.size(),
            entityIds.size() == 1 ? "y" : "ies",
            size, size,
            OUTPUT_DIR.toAbsolutePath());

        int rendered = 0;
        int failed = 0;
        long t0 = System.nanoTime();

        Optional<String> state = Optional.ofNullable(System.getProperty("asset.entity.state")).filter(s -> !s.isBlank());
        Optional<String> carried = Optional.ofNullable(System.getProperty("asset.entity.carried")).filter(s -> !s.isBlank());
        // Dye tint axes (-Dasset.entity.collar / .wool / .base_color / .pattern_color / .equipment_color
        // name a vanilla dye); each populates its TintAxis slot, empty = the target's baked default.
        Optional<String> collarName = Optional.ofNullable(System.getProperty("asset.entity.collar")).filter(s -> !s.isBlank());
        Optional<String> woolName = Optional.ofNullable(System.getProperty("asset.entity.wool")).filter(s -> !s.isBlank());
        Optional<String> baseColorName = Optional.ofNullable(System.getProperty("asset.entity.base_color")).filter(s -> !s.isBlank());
        Optional<String> patternColorName = Optional.ofNullable(System.getProperty("asset.entity.pattern_color")).filter(s -> !s.isBlank());
        Optional<String> equipmentColorName = Optional.ofNullable(System.getProperty("asset.entity.equipment_color")).filter(s -> !s.isBlank());
        java.util.EnumMap<TintAxis, DyeColor> tints = new java.util.EnumMap<>(TintAxis.class);
        collarName.flatMap(TestEntityRender3D::dye).ifPresent(d -> tints.put(TintAxis.COLLAR, d));
        woolName.flatMap(TestEntityRender3D::dye).ifPresent(d -> tints.put(TintAxis.WOOL, d));
        baseColorName.flatMap(TestEntityRender3D::dye).ifPresent(d -> tints.put(TintAxis.BASE, d));
        patternColorName.flatMap(TestEntityRender3D::dye).ifPresent(d -> tints.put(TintAxis.PATTERN, d));
        equipmentColorName.flatMap(TestEntityRender3D::dye).ifPresent(d -> tints.put(TintAxis.EQUIPMENT, d));
        // -Dasset.entity.pattern=sunstreak names a tropical-fish pattern (TropicalFishPattern).
        Optional<String> patternName = Optional.ofNullable(System.getProperty("asset.entity.pattern")).filter(s -> !s.isBlank());
        Optional<TropicalFishPattern> pattern = patternName.map(TropicalFishPattern::ofName);
        Optional<String> age = Optional.ofNullable(System.getProperty("asset.entity.age")).filter(s -> !s.isBlank());
        // -Dasset.entity.size=small|medium|large names a Size (pufferfish puff mesh).
        Optional<Size> sizeOpt = Optional.ofNullable(System.getProperty("asset.entity.size")).filter(s -> !s.isBlank())
            .map(s -> Size.valueOf(s.toUpperCase(java.util.Locale.ROOT)));
        // -Dasset.entity.markings=white_dots names a horse marking (HorseMarking); default NONE.
        Optional<String> markingsName = Optional.ofNullable(System.getProperty("asset.entity.markings")).filter(s -> !s.isBlank());
        HorseMarking markings = markingsName.map(HorseMarking::ofName).orElse(HorseMarking.NONE);
        // -Dasset.entity.crackiness=low|medium|high names an iron-golem damage level; default NONE.
        Optional<String> crackinessName = Optional.ofNullable(System.getProperty("asset.entity.crackiness")).filter(s -> !s.isBlank());
        IronGolemCrackiness crackiness = crackinessName.map(IronGolemCrackiness::ofName).orElse(IronGolemCrackiness.NONE);
        // -Dasset.entity.weathering=exposed|weathered|oxidized names a copper-golem oxidation state; default UNAFFECTED.
        Optional<String> weatheringName = Optional.ofNullable(System.getProperty("asset.entity.weathering")).filter(s -> !s.isBlank());
        CopperWeathering weathering = weatheringName.map(CopperWeathering::ofName).orElse(CopperWeathering.UNAFFECTED);
        // -Dasset.entity.type=desert names a villager/zombie_villager biome type (VillagerType); default PLAINS.
        Optional<String> villagerTypeName = Optional.ofNullable(System.getProperty("asset.entity.type")).filter(s -> !s.isBlank());
        VillagerType villagerType = villagerTypeName.map(VillagerType::ofName).orElse(VillagerType.PLAINS);
        // -Dasset.entity.profession=farmer names a villager profession (VillagerProfession); default NONE.
        Optional<String> professionName = Optional.ofNullable(System.getProperty("asset.entity.profession")).filter(s -> !s.isBlank());
        VillagerProfession villagerProfession = professionName.map(VillagerProfession::ofName).orElse(VillagerProfession.NONE);
        // -Dasset.entity.level=gold names a villager trade badge tier (VillagerLevel); unnamed leaves a
        // job villager on vanilla's first tier, which is what an unspecified level clamps up to.
        Optional<String> villagerLevelName = Optional.ofNullable(System.getProperty("asset.entity.level")).filter(s -> !s.isBlank());
        Optional<VillagerLevel> villagerLevel = villagerLevelName.map(VillagerLevel::ofName);
        boolean sheared = Boolean.getBoolean("asset.entity.sheared");
        boolean charged = Boolean.getBoolean("asset.entity.charged");
        // -Dasset.entity.elytra=true wears an elytra (the WINGS model overlay); default false.
        boolean elytra = Boolean.getBoolean("asset.entity.elytra");
        java.util.Set<String> toggles = Optional.ofNullable(System.getProperty("asset.entity.toggles")).filter(s -> !s.isBlank())
            .map(s -> java.util.Arrays.stream(s.split(",")).map(String::trim).filter(t -> !t.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)))
            .map(s -> (java.util.Set<String>) s)
            .orElse(java.util.Set.of());
        // -Dasset.entity.equipment=saddle,body:diamond -> {saddle:"", body:"diamond"}; a bare slot uses
        // the layer default material (leather armor / the saddle), "slot:material" picks the material.
        java.util.Map<String, String> equipment = Optional.ofNullable(System.getProperty("asset.entity.equipment")).filter(s -> !s.isBlank())
            .map(s -> {
                java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
                for (String part : s.split(",")) {
                    String entry = part.trim();
                    if (entry.isEmpty()) continue;
                    int colon = entry.indexOf(':');
                    if (colon < 0) map.put(entry, "");
                    else map.put(entry.substring(0, colon), entry.substring(colon + 1));
                }
                return map;
            })
            .orElse(java.util.Map.of());
        // -Dasset.entity.armor=iron|leather|... wears a full set of that material's humanoid armor
        // (helmet/chestplate/leggings/boots); default unarmored. -Dasset.entity.armor_dye=RRGGBB
        // dyes leather armor (ignored for other materials).
        Optional<ArmorMaterial> armorMaterial = Optional.ofNullable(System.getProperty("asset.entity.armor"))
            .filter(s -> !s.isBlank())
            .map(s -> ArmorMaterial.valueOf(s.toUpperCase(java.util.Locale.ROOT)));
        Optional<Integer> armorDye = Optional.ofNullable(System.getProperty("asset.entity.armor_dye"))
            .filter(s -> !s.isBlank())
            .map(s -> 0xFF000000 | Integer.parseInt(s, 16));
        ArmorOptions armor = armorMaterial
            .map(mat -> mat == ArmorMaterial.LEATHER && armorDye.isPresent()
                ? ArmorPiece.dyedLeather(armorDye.get())
                : ArmorPiece.of(mat))
            .map(piece -> ArmorOptions.builder()
                .helmet(Optional.of(piece))
                .chestplate(Optional.of(piece))
                .leggings(Optional.of(piece))
                .boots(Optional.of(piece))
                .build())
            .orElseGet(ArmorOptions::defaults);

        for (String entityId : entityIds) {
            String safeName = entityId.replace(':', '_')
                + state.map(s -> "_" + s).orElse("")
                + carried.map(c -> "_carried-" + c.replace(':', '_')).orElse("")
                + collarName.map(c -> "_collar-" + c).orElse("")
                + woolName.map(c -> "_wool-" + c).orElse("")
                + baseColorName.map(c -> "_base-" + c).orElse("")
                + patternColorName.map(c -> "_patterncolor-" + c).orElse("")
                + patternName.map(c -> "_pattern-" + c).orElse("")
                + (sheared ? "_sheared" : "")
                + (charged ? "_charged" : "")
                + (elytra ? "_elytra" : "")
                + armorMaterial.map(m -> "_armor-" + m.name().toLowerCase(java.util.Locale.ROOT)
                    + armorDye.map(d -> String.format("-dye%06x", d & 0xFFFFFF)).orElse("")).orElse("")
                + sizeOpt.map(s -> "_size-" + s.name().toLowerCase(java.util.Locale.ROOT)).orElse("")
                + (markings == HorseMarking.NONE ? "" : "_markings-" + markings.name().toLowerCase(java.util.Locale.ROOT))
                + (crackiness == IronGolemCrackiness.NONE ? "" : "_crackiness-" + crackiness.name().toLowerCase(java.util.Locale.ROOT))
                + (weathering == CopperWeathering.UNAFFECTED ? "" : "_weathering-" + weathering.name().toLowerCase(java.util.Locale.ROOT))
                + (villagerType == VillagerType.PLAINS ? "" : "_type-" + villagerType.name().toLowerCase(java.util.Locale.ROOT))
                + (villagerProfession == VillagerProfession.NONE ? "" : "_prof-" + villagerProfession.name().toLowerCase(java.util.Locale.ROOT))
                + villagerLevel.map(l -> "_level-" + l.name().toLowerCase(java.util.Locale.ROOT)).orElse("")
                + (toggles.isEmpty() ? "" : "_toggle-" + String.join("-", toggles))
                + (equipment.isEmpty() ? "" : "_equip-" + equipment.entrySet().stream()
                    .map(en -> en.getValue().isEmpty() ? en.getKey() : en.getKey() + "-" + en.getValue())
                    .collect(java.util.stream.Collectors.joining("-")))
                + equipmentColorName.map(n -> "_equipdye-" + n.toLowerCase(java.util.Locale.ROOT)).orElse("")
                + age.map(a -> "_" + a).orElse("");
            EntityAppearance appearance = EntityAppearance.builder()
                .age(age.map(a -> a.equalsIgnoreCase("baby") ? Age.BABY : Age.ADULT).orElse(Age.ADULT))
                .state(state)
                .carried(carried)
                .tints(tints)
                .pattern(pattern)
                .markings(markings)
                .crackiness(crackiness)
                .weathering(weathering)
                .villagerType(villagerType)
                .villagerProfession(villagerProfession)
                .villagerLevel(villagerLevel)
                .sheared(sheared)
                .charged(charged)
                .elytra(elytra)
                .size(sizeOpt)
                .toggles(toggles)
                .equipment(equipment)
                .build();
            EntityOptions options = EntityOptions.builder()
                .entityId(Optional.of(entityId))
                .appearance(appearance)
                .armor(armor)
                .output(OutputOptions.builder()
                    .canvasSize(size)
                    .supersample(2)
                    .antiAlias(true)
                    .projection(projection)
                    .build())
                .build();

            long perT0 = System.nanoTime();
            try {
                ImageData image = renderer.render(options);
                File out = OUTPUT_DIR.resolve(safeName + ".png").toFile();
                ImageIO.write(image.toBufferedImage(), "PNG", out);

                long elapsedMs = (System.nanoTime() - perT0) / 1_000_000L;
                System.out.printf("  %-40s -> %s.png (%d ms)%n", entityId, safeName, elapsedMs);
                rendered++;
            } catch (Exception ex) {
                System.err.printf("  %-40s FAILED: %s%n", entityId, ex.getMessage());
                failed++;
            }
        }

        long totalMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf("Done. %d rendered, %d failed, %d ms total.%n", rendered, failed, totalMs);
    }

    /**
     * Resolves a vanilla dye name (case-insensitive) to its {@link DyeColor}, or empty when the name
     * is not a known vanilla dye.
     *
     * @param name the dye name (e.g. {@code "red"})
     * @return the matching dye, or empty
     */
    private static Optional<DyeColor> dye(@NotNull String name) {
        return Optional.ofNullable(DyeColor.ofName(name.toUpperCase(java.util.Locale.ROOT)));
    }

}
