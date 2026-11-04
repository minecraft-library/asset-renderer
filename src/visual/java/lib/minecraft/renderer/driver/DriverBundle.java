package lib.minecraft.renderer.driver;

import api.simplified.mojang.exception.MojangApiException;
import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.ClientException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the visual drivers whose {@code cache/visual} sub-trees {@code manifest.visual} hashes, in one
 * JVM over one renderer context.
 *
 * <p>Every one of them renders over vanilla's own client options, so the build runs them here, one
 * after another, and pays the JVM start, the client acquisition and the context load once rather than
 * once a driver. Each is its driver's own {@code run} over that context, handed the arguments the
 * driver's own task hands its {@code main}, so what a driver writes here is what it writes on its own.
 *
 * <p><b>A driver that fails fails alone.</b> What it threw - an exception, or an error such as a
 * driver's class failing to initialise or an assertion - is printed and the next one runs; once every
 * driver has run, an exception naming each one that failed ends the JVM, so the build reports the run
 * as failed and still captures what the others wrote. Only an error the JVM raises about itself,
 * running out of memory among them, ends the run where it was raised, because the drivers after it
 * would share the heap or the stack it was raised over.
 *
 * <p>Arguments: for each driver, in the order it runs, its {@code main} class name, how many arguments
 * it is handed, then those arguments.
 */
@UtilityClass
public final class DriverBundle {

    /** Every driver this JVM runs, by its class name. */
    private static final @NotNull Map<String, Driver> DRIVERS = drivers();

    /**
     * One driver's run over a loaded context.
     */
    @FunctionalInterface
    private interface Driver {

        /**
         * Runs the driver.
         *
         * @param context the context every render resolves through
         * @param args what the driver's {@code main} would be handed
         * @throws IOException if the driver cannot write its output
         */
        void run(@NotNull RendererContext context, String @NotNull [] args) throws IOException;

    }

    /**
     * One driver the arguments name.
     *
     * @param main the driver's class name
     * @param args what it is handed
     */
    private record Run(@NotNull String main, String @NotNull [] args) {}

    /**
     * Builds the table of drivers, each under its class name.
     *
     * @return the table
     */
    private static @NotNull Map<String, Driver> drivers() {
        Map<String, Driver> drivers = new LinkedHashMap<>();
        drivers.put(BlockRenderDriver.class.getName(), BlockRenderDriver::run);
        drivers.put(EntityProjectionsDriver.class.getName(), EntityProjectionsDriver::run);
        drivers.put(EntityRenderDriver.class.getName(), EntityRenderDriver::run);
        drivers.put(ItemDayCycleDriver.class.getName(), ItemDayCycleDriver::run);
        drivers.put(ItemRenderDriver.class.getName(), ItemRenderDriver::run);
        drivers.put(LoreTooltipDriver.class.getName(), LoreTooltipDriver::run);
        drivers.put(MenuRenderDriver.class.getName(), MenuRenderDriver::run);
        drivers.put(BlockProjectionsDriver.class.getName(), BlockProjectionsDriver::run);
        return Collections.unmodifiableMap(drivers);
    }

    /**
     * Runs every driver the arguments name, in their order, over one context.
     *
     * @param args for each driver, its class name, its argument count and its arguments
     * @throws IOException if the context cannot be loaded
     * @throws IllegalArgumentException if the arguments are malformed or name a driver this JVM does not
     *     run
     * @throws IllegalStateException if a driver failed
     * @throws VirtualMachineError if a driver ended on one
     */
    public static void main(String @NotNull [] args) throws IOException {
        List<Run> runs = readRuns(args);
        for (Run run : runs) {
            if (!DRIVERS.containsKey(run.main()))
                throw new IllegalArgumentException(String.format("Driver '%s' is not one this JVM runs", run.main()));
        }

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (ClientException | MojangApiException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }
        RendererContext context = RendererContext.load(result);

        List<String> failed = new ArrayList<>();
        for (Run run : runs) {
            System.out.println("Running " + run.main().substring(run.main().lastIndexOf('.') + 1));
            try {
                DRIVERS.get(run.main()).run(context, run.args());
            } catch (VirtualMachineError error) {
                throw error;
            } catch (Throwable ex) {
                failed.add(run.main() + " (" + ex + ")");
                ex.printStackTrace();
            }
        }

        if (!failed.isEmpty())
            throw new IllegalStateException(String.format("%d of %d drivers failed: %s", failed.size(), runs.size(), String.join(", ", failed)));
    }

    /**
     * Reads the drivers the arguments name.
     *
     * @param args for each driver, its class name, its argument count and its arguments
     * @return the drivers, in the order they run
     * @throws IllegalArgumentException if a count is missing, is not a number, or runs past the end
     */
    private static @NotNull List<Run> readRuns(String @NotNull [] args) {
        List<Run> runs = new ArrayList<>();
        int at = 0;
        while (at < args.length) {
            if (at + 1 >= args.length)
                throw new IllegalArgumentException(String.format("Driver '%s' has no argument count", args[at]));

            String main = args[at];
            int count = Integer.parseInt(args[at + 1]);
            if (count < 0 || at + 2 + count > args.length)
                throw new IllegalArgumentException(String.format("Driver '%s' names %d arguments and %d follow", main, count, args.length - at - 2));

            runs.add(new Run(main, Arrays.copyOfRange(args, at + 2, at + 2 + count)));
            at += 2 + count;
        }
        return runs;
    }

}
