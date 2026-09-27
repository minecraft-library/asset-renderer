package lib.minecraft.renderer.fixture;

import org.jetbrains.annotations.NotNull;

/**
 * Item model definition inputs for the tests that parse a dispatch tree - a {@code range_dispatch}
 * written the way vanilla's time and compass tables are, over any property and any number of faces.
 */
public final class ItemModelFixtures {

    private ItemModelFixtures() {}

    /**
     * Writes a {@code range_dispatch} over {@code faces} models, plus the wrap entry vanilla's tables
     * carry.
     *
     * <p>The first entry opens at threshold {@code 0} and entry {@code n} after it at {@code n - 0.5},
     * and entry {@code n} names face {@code n % faces} - so the last entry lands back on the first
     * face, and the table holds one entry more than it has steps.
     *
     * @param property the property the dispatch reads, namespaced or not
     * @param faces how many distinct faces the table steps through
     * @return the dispatch node as JSON, ready to sit under a {@code model} member or in a case
     */
    public static @NotNull String timeDispatch(@NotNull String property, int faces) {
        StringBuilder entries = new StringBuilder();
        for (int entry = 0; entry <= faces; entry++) {
            if (entry > 0) entries.append(',');
            entries.append("{\"threshold\":%s,\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/face_%02d\"}}"
                .formatted(entry == 0 ? "0.0" : (entry - 0.5f), entry % faces));
        }
        return "{\"type\":\"minecraft:range_dispatch\",\"property\":\"%s\",\"scale\":%s.0,\"entries\":[%s]}"
            .formatted(property, faces, entries);
    }

}
