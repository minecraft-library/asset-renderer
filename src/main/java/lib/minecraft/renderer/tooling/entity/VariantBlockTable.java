package lib.minecraft.renderer.tooling.entity;

import lib.minecraft.renderer.tooling.kernel.AsmKit;
import lib.minecraft.renderer.tooling.kernel.ClassNodeCache;
import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.VanillaSourceClasses;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import lib.minecraft.renderer.tooling.walk.AsmWalker;
import lib.minecraft.renderer.tooling.walk.Cells;
import lib.minecraft.renderer.tooling.walk.Insn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The block each constant of a {@code $Variant} enum wraps - vanilla holds it as a
 * {@code BlockState} field on the constant, so the pairing is read off the enum's {@code <clinit>}
 * rather than from the layer that draws it.
 *
 * <p>A variant that names a block names it for the whole subject: the mooshroom's coat decides
 * whether its three mushrooms are red or brown, exactly as
 * {@code MushroomCowRenderer.extractRenderState} re-bakes one block model per variant and the
 * layer submits that one model at three poses. So the {@code DEFAULT} constant's block is the
 * {@code block_overlays[]} row's, and every other constant's rides its own variant option.
 *
 * @param byConstant enum constant name to the registered id of the block it wraps, in
 *     {@code <clinit>} order
 * @param defaultConstant the constant the enum's {@code DEFAULT} alias names, or {@code null} when
 *     it declares none
 */
record VariantBlockTable(@NotNull Map<String, String> byConstant, @Nullable String defaultConstant) {

    /** The table of an enum that wraps no block, and of one that could not be loaded. */
    private static final @NotNull VariantBlockTable EMPTY = new VariantBlockTable(Map.of(), null);

    /**
     * Walks a variant enum's {@code <clinit>}, pairing each constant with the {@code Blocks.X} it
     * is constructed from and resolving those fields to their registered ids.
     *
     * @param cache the class cache to load the enum from
     * @param blocks the registry index resolving a {@code Blocks} field to its registered id
     * @param variantClass the variant enum's JVM internal name
     * @param diagnostics the diagnostics sink for an unregistered block field
     * @return the constant-to-block table, empty when the enum wraps no block
     */
    static @NotNull VariantBlockTable of(
        @NotNull ClassNodeCache cache,
        @NotNull BlockRegistryIndex blocks,
        @NotNull String variantClass,
        @NotNull Diagnostics diagnostics
    ) {
        MethodNode clinit = AsmKit.findClinit(cache, variantClass);
        if (clinit == null) return EMPTY;

        Map<String, String> byField = new LinkedHashMap<>();
        String[] defaultConstant = new String[1];
        Cells.Latch<String> pendingBlocksField = Cells.latch();
        Cells.Latch<String> pendingAlias = Cells.latch();
        AsmWalker.over(clinit)
            .feed(pendingBlocksField)
            .feed(pendingAlias)
            .on(Insn.getStatic(VanillaSourceClasses.Types.BLOCKS), get -> pendingBlocksField.set(get.name))
            .on(Insn.getStatic(variantClass), get -> pendingAlias.set(get.name))
            .commitAt(Insn.putStatic(variantClass), put -> {
                if (EntityNamingPolicies.ENUM_DEFAULT_FIELD.stringValue().equals(put.name) && pendingAlias.get() != null)
                    defaultConstant[0] = pendingAlias.get();
                else if (pendingBlocksField.get() != null)
                    byField.put(put.name, pendingBlocksField.get());
            })
            .run();
        if (byField.isEmpty()) return EMPTY;

        Map<String, String> byConstant = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : byField.entrySet()) {
            BlockRegistryIndex.Entry registered = blocks.byField(entry.getValue());
            if (registered == null) {
                diagnostics.warn("Blocks.%s has no registration entry", entry.getValue());
                continue;
            }
            byConstant.put(entry.getKey(), registered.id());
        }
        return new VariantBlockTable(byConstant, defaultConstant[0]);
    }

    /**
     * The block the enum's canonical constant wraps - the one a block-overlay row carries when no
     * coat is selected.
     *
     * @return the default constant's registered block id, or {@code null} when the enum declares no
     *     default or its block did not resolve
     */
    @Nullable String defaultBlockId() {
        return this.defaultConstant == null ? null : this.byConstant.get(this.defaultConstant);
    }
}
