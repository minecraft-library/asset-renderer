package dev.sbs.renderer.tooling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Unit tests for the {@link ToolingBlockEntities.Diagnostics} severity pipeline introduced by
 * Tasks 16 + 18 + 20. Exercises the error/warn/info split, the strict-failing counter, and the
 * dedupe behaviour - the three invariants that gate strict-mode behaviour in
 * {@link ToolingBlockEntities#main(String[])}.
 */
@DisplayName("Diagnostics severity pipeline")
class DiagnosticsTest {

    @Test
    @DisplayName("error + warn fail strict; info does not")
    void severitySplit() {
        ToolingBlockEntities.Diagnostics diag = new ToolingBlockEntities.Diagnostics();
        diag.error("missing class %s", "Foo");
        diag.warn("underflow at %s", "addBox");
        diag.info("%d leftover literals", 2);

        assertThat(diag.entries(), contains(
            "ERROR: missing class Foo",
            "WARN: underflow at addBox",
            "INFO: 2 leftover literals"
        ));
        assertThat(diag.strictFailingCount(), equalTo(2));
    }

    @Test
    @DisplayName("identical messages dedupe to a single entry")
    void dedupeSuppressesRepeats() {
        ToolingBlockEntities.Diagnostics diag = new ToolingBlockEntities.Diagnostics();
        diag.warn("repeat");
        diag.warn("repeat");
        diag.warn("repeat");
        diag.warn("distinct");

        assertThat(diag.entries(), contains("WARN: repeat", "WARN: distinct"));
        assertThat(diag.strictFailingCount(), equalTo(2));
    }

    @Test
    @DisplayName("fresh instance is empty with zero strict-failing count")
    void initiallyEmpty() {
        ToolingBlockEntities.Diagnostics diag = new ToolingBlockEntities.Diagnostics();
        assertThat(diag.isEmpty(), is(true));
        assertThat(diag.strictFailingCount(), equalTo(0));
        assertThat(diag.entries().isEmpty(), is(true));
    }

    @Test
    @DisplayName("info-only diagnostics leave strict-failing at zero")
    void infoOnlyIsStrictClean() {
        ToolingBlockEntities.Diagnostics diag = new ToolingBlockEntities.Diagnostics();
        diag.info("leftover A");
        diag.info("leftover B");
        assertThat(diag.strictFailingCount(), equalTo(0));
        assertThat(diag.isEmpty(), is(false));
    }

}
