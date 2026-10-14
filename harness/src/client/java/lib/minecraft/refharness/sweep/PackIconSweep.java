package lib.minecraft.refharness.sweep;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import lib.minecraft.refharness.HarnessConfig;
import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.ItemFrameRenderer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Diagnostic pack-icon sweep. Draws every stack asset-renderer's pack-icon roster names through the
 * client's own GUI slot path, with the roster's resource packs selected over vanilla - the ground truth
 * for a block item a pack gives a definition of its own, which no reference sweep has, because none
 * loads a pack.
 *
 * <p>Nothing here is a reference of the vanilla tree. The roster and the drawings both live in
 * {@code pack-icons/} beside it, so the manifest that gates the tree never acquires them.
 *
 * <p>Each subject's components are SNBT, parsed by the client's {@code TagParser} and decoded by the
 * client's {@code DataComponentPatch} codec against the running registries, then applied with
 * validation - so a stack the client would refuse is a failed subject rather than a drawing of
 * something else. The drawing is {@link ItemFrameRenderer}'s, the path a slot icon takes for any item,
 * a block item included.
 *
 * <p>The roster names the packs it was solved over, and a run whose client selected a different set is
 * refused before anything is drawn: a drawing over other packs answers a question the roster did not
 * ask.
 */
@Parity(claim = "harness-probe-sweep", mode = Mode.DEMOTE)
public final class PackIconSweep implements Sweep<PackIconSweep.Subject> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /** The roster's file name inside this sweep's directory. */
    private static final String ROSTER = "roster.json";

    private final ItemFrameRenderer frameRenderer = new ItemFrameRenderer();

    /**
     * One roster subject.
     *
     * @param key the subject's name, the stem of its drawing
     * @param item the qualified item id
     * @param components the stack's component patch as SNBT
     */
    public record Subject(String key, String item, String components) {}

    /**
     * Returns a directory outside the reference tree - the roster and its drawings are a diagnostic,
     * and the manifest that gates the tree must not acquire them.
     */
    @Override
    public String outputDir() {
        return "pack-icons";
    }

    /**
     * Reads the roster, refusing it where the packs it names are not the ones the client selected.
     *
     * @param ctx the sweep context
     * @return the subjects whose item the target filter accepts, in roster order
     * @throws UncheckedIOException if the roster cannot be read
     * @throws IllegalStateException if the roster's packs are not the selected ones
     */
    @Override
    public List<Subject> enumerate(SweepContext ctx) {
        Path file = ctx.outputRoot().resolve(outputDir()).resolve(ROSTER);
        JsonObject roster;
        try {
            roster = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException ex) {
            throw new UncheckedIOException("No pack-icon roster at " + file, ex);
        }

        List<String> named = new ArrayList<>();
        for (JsonElement pack : roster.getAsJsonArray("packs")) named.add("file/" + pack.getAsString());
        Collection<String> selected = ctx.client().getResourcePackRepository().getSelectedIds();
        if (!named.equals(HarnessConfig.packIds()) || !selected.containsAll(named))
            throw new IllegalStateException(String.format(
                "The roster was solved over '%s', and this run selected '%s' of '%s'", named, selected, HarnessConfig.packIds()));

        List<Subject> subjects = new ArrayList<>();
        for (JsonElement element : roster.getAsJsonArray("subjects")) {
            JsonObject subject = element.getAsJsonObject();
            String item = subject.get("item").getAsString();
            if (!ctx.targets().accepts(item)) continue;
            subjects.add(new Subject(subject.get("key").getAsString(), item, subject.get("components").getAsString()));
        }
        LOG.info("PackIconSweep built: {} subjects over {}", subjects.size(), named);
        return subjects;
    }

    @Override
    public RefKey key(Subject subject) {
        return RefKey.named(subject.key());
    }

    @Override
    public Canvas canvas(SweepContext ctx, Subject subject) {
        return Canvas.square(HarnessConfig.IMAGE_SIZE);
    }

    /**
     * Builds the subject's stack and draws it through the slot path.
     *
     * @param ctx the sweep context
     * @param subject the subject
     * @param canvas the canvas
     * @param out the drawing's path
     * @return whether a drawing was written
     * @throws IOException if the drawing cannot be written
     * @throws IllegalStateException if the item is unknown or the components do not decode
     */
    @Override
    public boolean render(SweepContext ctx, Subject subject, Canvas canvas, Path out) throws IOException {
        return frameRenderer.render(ctx.client(), stack(ctx, subject), canvas, out);
    }

    /** Builds a subject's stack: one of its item, its components decoded and applied as the client applies a patch. */
    private static ItemStack stack(SweepContext ctx, Subject subject) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(subject.item()));
        if (item == Items.AIR)
            throw new IllegalStateException(String.format("Roster item '%s' is no item", subject.item()));

        DataComponentPatch patch;
        try {
            patch = DataComponentPatch.CODEC
                .parse(ctx.level().registryAccess().createSerializationContext(NbtOps.INSTANCE), TagParser.parseCompoundFully(subject.components()))
                .getOrThrow(message -> new IllegalStateException(String.format("Components of '%s' do not decode: %s", subject.key(), message)));
        } catch (CommandSyntaxException ex) {
            throw new IllegalStateException(String.format("Components of '%s' are not SNBT: %s", subject.key(), ex.getMessage()), ex);
        }
        ItemStack stack = new ItemStack(item);
        stack.applyComponentsAndValidate(patch);
        return stack;
    }
}
