package lib.minecraft.renderer.sweep;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs several vanilla parity sweeps in one JVM over one renderer context.
 *
 * <p>Every sweep measured over vanilla's own client options loads the same context from the same
 * options, so a build asking for several runs them here, one after another, and pays the JVM start,
 * the client acquisition and the context load once rather than once a sweep. Each sweep is its
 * writer's own {@code run} over that context, handed what its {@code main} hands it, so a table
 * written here is the table its writer writes on its own.
 *
 * <p><b>A sweep that fails fails alone.</b> What it threw - an exception, or an error such as a
 * writer's class failing to initialise or an assertion - is recorded against it and the next one runs:
 * the build reports each sweep as a task of its own, so one red sweep does not take the rows of the
 * others with it. Only an error the JVM raises about itself, running out of memory among them, ends
 * the run: it is recorded against the sweep it ended first, and the sweeps after it are left unrun,
 * because they would share the heap or the stack it was raised over.
 *
 * <p>Arguments: the plan file, then the status file. The plan is a JSON array of the sweeps to run,
 * in the order they run, each an object naming its {@code key} - what the status reports it under -,
 * its {@code writer} class, the {@code args} it is handed, and the {@code log} file its standard
 * output is copied into beside the console. The status holds a line per sweep that ran - its key,
 * {@code ok} or {@code failed}, the milliseconds it took and, for a failure, what it threw, tab
 * separated - appended as each one ends, so a JVM that dies part way leaves the lines of the sweeps it
 * finished and none for the rest.
 */
@UtilityClass
public final class SweepBundle {

    /** The status of a sweep that ran to its end. */
    private static final @NotNull String OK = "ok";

    /** The status of a sweep that threw. */
    private static final @NotNull String FAILED = "failed";

    /** Every sweep this JVM runs, by its writer's class name. */
    private static final @NotNull Map<String, Sweep> SWEEPS = sweeps();

    /**
     * One sweep's run over a loaded context.
     */
    @FunctionalInterface
    private interface Sweep {

        /**
         * Runs the sweep.
         *
         * @param context the context every render resolves through
         * @param args what the sweep's {@code main} would be handed
         * @throws IOException if the sweep cannot write its output
         */
        void run(@NotNull RendererContext context, String @NotNull [] args) throws IOException;

    }

    /**
     * One sweep the plan names.
     *
     * @param key what the status reports it under
     * @param writer the sweep's class name
     * @param args what it is handed
     * @param log the file its standard output is copied into, or {@code null} for the console alone
     */
    private record Run(@NotNull String key, @NotNull String writer, String @NotNull [] args, @Nullable Path log) {}

    /**
     * Builds the table of sweeps, each under its writer's class name.
     *
     * @return the table
     */
    private static @NotNull Map<String, Sweep> sweeps() {
        Map<String, Sweep> sweeps = new LinkedHashMap<>();
        sweeps.put(EntityParitySweep.class.getName(), EntityParitySweep::run);
        sweeps.put(EntityAnimationParitySweep.class.getName(), EntityAnimationParitySweep::run);
        sweeps.put(BlockParitySweep.class.getName(), BlockParitySweep::run);
        sweeps.put(ItemParitySweep.class.getName(), ItemParitySweep::run);
        sweeps.put(PlayerParitySweep.class.getName(), PlayerParitySweep::run);
        sweeps.put(ArmorParitySweep.class.getName(), ArmorParitySweep::run);
        sweeps.put(GlintParitySweep.class.getName(), GlintParitySweep::run);
        sweeps.put(MenuParitySweep.class.getName(), MenuParitySweep::run);
        sweeps.put(TooltipParitySweep.class.getName(), TooltipParitySweep::run);
        return Collections.unmodifiableMap(sweeps);
    }

    /**
     * Runs every sweep the plan names, in its order, over one context.
     *
     * @param args the plan file, then the status file
     * @throws IOException if the plan cannot be read or the status cannot be written
     * @throws IllegalArgumentException if the arguments are not two, or the plan names a writer this
     *     JVM does not run
     * @throws VirtualMachineError if a sweep ended on one, once its status line is written
     */
    public static void main(String @NotNull [] args) throws IOException {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: SweepBundle <plan.json> <status.tsv>");
        List<Run> runs = readPlan(Path.of(args[0]));
        Path status = Path.of(args[1]);
        Files.deleteIfExists(status);
        for (Run run : runs) {
            if (!SWEEPS.containsKey(run.writer()))
                throw new IllegalArgumentException(String.format("Writer '%s' is not a sweep this JVM runs", run.writer()));
        }
        if (runs.isEmpty()) {
            System.out.println("No vanilla sweep scheduled - name the sweeps to run.");
            return;
        }

        RendererContext context;
        try {
            context = RendererContext.load(SweepPair.acquire(ClientOptions.defaults()));
        } catch (RuntimeException | Error ex) {
            for (Run run : runs)
                record(status, run.key(), FAILED, 0, "the shared context could not be loaded: " + ex);
            throw ex;
        }

        for (Run run : runs) {
            PrintStream console = System.out;
            long started = System.nanoTime();
            String outcome = OK;
            String message = "";
            VirtualMachineError fatal = null;
            try (PrintStream out = run.log() == null ? null : copying(console, run.log())) {
                if (out != null) System.setOut(out);
                SWEEPS.get(run.writer()).run(context, run.args());
            } catch (Throwable ex) {
                outcome = FAILED;
                message = ex.toString();
                ex.printStackTrace();
                if (ex instanceof VirtualMachineError error) fatal = error;
            } finally {
                System.setOut(console);
            }
            record(status, run.key(), outcome, (System.nanoTime() - started) / 1_000_000L, message);
            if (fatal != null)
                throw fatal;
        }
    }

    /**
     * Reads the plan.
     *
     * @param plan the plan file
     * @return the sweeps it names, in its order
     * @throws IOException if the file cannot be read
     */
    private static @NotNull List<Run> readPlan(@NotNull Path plan) throws IOException {
        JsonArray entries = JsonParser.parseString(Files.readString(plan, StandardCharsets.UTF_8)).getAsJsonArray();
        List<Run> runs = new ArrayList<>(entries.size());
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            JsonArray given = entry.getAsJsonArray("args");
            String[] sweepArgs = new String[given.size()];
            for (int i = 0; i < sweepArgs.length; i++) sweepArgs[i] = given.get(i).getAsString();
            JsonElement log = entry.get("log");
            runs.add(new Run(entry.get("key").getAsString(), entry.get("writer").getAsString(), sweepArgs,
                log == null || log.isJsonNull() ? null : Path.of(log.getAsString())));
        }
        return runs;
    }

    /**
     * Opens a stream writing to the console and to a log file at once.
     *
     * @param console the console stream
     * @param log the log file, replaced
     * @return the stream
     * @throws IOException if the log cannot be opened
     */
    private static @NotNull PrintStream copying(@NotNull PrintStream console, @NotNull Path log) throws IOException {
        Files.createDirectories(log.toAbsolutePath().getParent());
        OutputStream file = Files.newOutputStream(log);
        OutputStream both = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                console.write(b);
                file.write(b);
            }

            @Override
            public void write(byte @NotNull [] b, int off, int len) throws IOException {
                console.write(b, off, len);
                file.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                console.flush();
                file.flush();
            }

            @Override
            public void close() throws IOException {
                console.flush();
                file.close();
            }
        };
        return new PrintStream(both, true, console.charset());
    }

    /**
     * Appends one sweep's line to the status file.
     *
     * @param status the status file
     * @param key the sweep's key
     * @param outcome {@code ok} or {@code failed}
     * @param millis how long it took
     * @param message what it threw, empty where it threw nothing
     * @throws IOException if the file cannot be written
     */
    private static void record(@NotNull Path status, @NotNull String key, @NotNull String outcome, long millis,
                               @NotNull String message) throws IOException {
        Files.createDirectories(status.toAbsolutePath().getParent());
        String line = String.join("\t", key, outcome, Long.toString(millis), message.replaceAll("[\\t\\r\\n]+", " "));
        Files.writeString(status, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

}
