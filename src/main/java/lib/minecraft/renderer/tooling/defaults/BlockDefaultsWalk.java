package lib.minecraft.renderer.tooling.defaults;

import lib.minecraft.renderer.tooling.kernel.Diagnostics;
import lib.minecraft.renderer.tooling.kernel.JsonNode;
import lib.minecraft.renderer.tooling.kernel.ToolingSession;
import lib.minecraft.renderer.tooling.vanilla.BlockRegistryIndex;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.TreeMap;

/**
 * Walks every registered block and decodes its default state, the only stage that touches the
 * output tree. Loops every registered block sorted by id: a block whose class the registration
 * walk could not bind lands in {@code unresolved[]} (a WARN); every other block emits its decoded
 * default object under {@code blocks} ({@code {}} when property-less, distinguishing resolved-empty
 * from walk-failed).
 */
public final class BlockDefaultsWalk {

    private BlockDefaultsWalk() {
    }

    /**
     * Runs the decode over every block in the registry index, appending to {@code blocks} and
     * {@code unresolved}.
     *
     * @param session the live session
     * @param index the block registry index (field / id / class per registered block)
     * @param root the envelope root owning the {@code blocks} + {@code unresolved} nodes
     */
    public static void run(@NotNull ToolingSession session, @NotNull BlockRegistryIndex index, @NotNull JsonNode root) {
        // blocks before unresolved; both created upfront so unresolved is always present.
        JsonNode blocks = root.child("blocks");
        JsonNode unresolved = root.childArray("unresolved");

        PropertyDefinitionResolver properties = new PropertyDefinitionResolver(session.cache());
        BlockDefaultStateResolver decoder = new BlockDefaultStateResolver(session.cache(), properties);

        // Sort by id (the declared ordering); dedupe by id, last-writer-wins.
        Map<String, BlockRegistryIndex.Entry> byId = new TreeMap<>();
        for (BlockRegistryIndex.Entry entry : index.entries().values()) byId.put(entry.id(), entry);

        for (BlockRegistryIndex.Entry entry : byId.values()) {
            Diagnostics scope = session.diagnostics().child(entry.id());
            if (entry.blockClass() == null) {
                unresolved.add(entry.id());
                scope.warn("registration walk bound no block class (plain register(String, Properties)) - unresolved");
                continue;
            }
            JsonNode defaults = JsonNode.object();
            for (Map.Entry<String, String> pair : decoder.resolve(entry.blockClass()).entrySet())
                defaults.put(pair.getKey(), pair.getValue());
            blocks.put(entry.id(), defaults);
        }
    }

}
