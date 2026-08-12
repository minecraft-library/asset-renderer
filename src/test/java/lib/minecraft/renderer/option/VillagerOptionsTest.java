package lib.minecraft.renderer.option;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * The three villager axes that answer with something other than their own name - the profession's
 * badge gate and optional clothes pass, the biome type's adult-versus-baby robe directory, and the
 * badge tier's floor.
 *
 * <p>Each of the three carries a claim a rename or a reorder would break silently. A profession
 * suppresses the badge on exactly two constants, and they are not the same two that suppress the
 * clothes pass: {@link VillagerProfession#NITWIT} has a clothes texture and no badge, so the two
 * gates are asserted separately over the whole enum rather than assumed to coincide.
 * {@link VillagerType} carries the adult and baby robe sub-paths as two methods differing in nothing
 * but the directory token, which is exactly the pair a baby-form change swaps one half of; the
 * renderer decides a pass is a baby one by looking for a {@code /baby/} segment in the qualified
 * ref, so the token is pinned as it appears after the texture prefix is joined on.
 * {@link VillagerLevel#minimum()} is the first declared constant read by index, so the DECLARATION
 * ORDER is the contract - moving a tier up the enum silently changes what an unnamed level draws.
 */
@DisplayName("Villager profession, type and level")
class VillagerOptionsTest {

    /** The texture prefix the renderer joins onto every prefix-relative villager sub-path */
    private static final String PREFIX = "villager";

    @Nested
    @DisplayName("profession")
    class Profession {

        /** The two professions vanilla's badge pass skips - unemployed, and the one job that is not one */
        private final Set<VillagerProfession> badgeless =
            Set.of(VillagerProfession.NONE, VillagerProfession.NITWIT);

        /**
         * Asserts the badge gate is closed on exactly two constants and open on every other one, so a
         * profession added to the enum draws a badge unless it is named here.
         */
        @Test
        @DisplayName("exactly NONE and NITWIT draw no badge")
        void exactlyNoneAndNitwitDrawNoBadge() {
            for (VillagerProfession profession : VillagerProfession.values()) {
                assertThat(profession.name(), profession.drawsBadge(),
                    is(!this.badgeless.contains(profession)));
            }
        }

        /**
         * Asserts the clothes pass is suppressed on NONE alone, so the two gates do not coincide -
         * NITWIT draws its own clothes and no badge, and asserting either gate through the other
         * would lose that.
         */
        @Test
        @DisplayName("only NONE has no clothes pass, so NITWIT is clothed and badgeless")
        void onlyNoneHasNoClothesPass() {
            assertThat(VillagerProfession.NONE.overlaySubPath().isPresent(), is(false));
            assertThat(VillagerProfession.NITWIT.overlaySubPath(),
                is(equalTo(Optional.of("profession/nitwit"))));
            assertThat(VillagerProfession.NITWIT.drawsBadge(), is(false));

            for (VillagerProfession profession : VillagerProfession.values()) {
                assertThat(profession.name(), profession.overlaySubPath().isPresent(),
                    is(profession != VillagerProfession.NONE));
            }
        }

        /** Asserts every clothed profession's sub-path is its own lower-cased name under {@code profession/}. */
        @Test
        @DisplayName("a clothes sub-path is profession/ plus the lower-cased constant name")
        void aClothesSubPathIsTheLowerCasedConstantName() {
            for (VillagerProfession profession : VillagerProfession.values()) {
                if (profession == VillagerProfession.NONE) continue;
                assertThat(profession.name(), profession.overlaySubPath(),
                    is(equalTo(Optional.of("profession/" + profession.name().toLowerCase(Locale.ROOT)))));
            }
        }

        /** Asserts the name lookup round-trips every constant, folds case, and answers null off the enum. */
        @Test
        @DisplayName("the name lookup round-trips, folds case and answers null for anything else")
        void theNameLookupRoundTripsAndAnswersNullOffTheEnum() {
            for (VillagerProfession profession : VillagerProfession.values()) {
                assertThat(VillagerProfession.ofName(profession.name().toLowerCase(Locale.ROOT)),
                    is(profession));
            }

            assertThat(VillagerProfession.ofName("WeApOnSmItH"), is(VillagerProfession.WEAPONSMITH));
            assertThat(VillagerProfession.ofName("plumber"), is(nullValue()));
            assertThat(VillagerProfession.ofName(null), is(nullValue()));
        }
    }

    @Nested
    @DisplayName("biome type")
    class BiomeType {

        /**
         * Asserts the adult and baby robe sub-paths are the same lower-cased biome name under two
         * directories, over every biome - the pair a baby-form edit can move one half of.
         */
        @Test
        @DisplayName("the adult and baby robe paths are one biome name under two directories")
        void theAdultAndBabyRobePathsShareOneBiomeName() {
            for (VillagerType type : VillagerType.values()) {
                String biome = type.name().toLowerCase(Locale.ROOT);
                assertThat(type.name(), type.overlaySubPath(), is(equalTo("type/" + biome)));
                assertThat(type.name(), type.babyOverlaySubPath(), is(equalTo("baby/" + biome)));
            }
            assertThat(VillagerType.PLAINS.overlaySubPath(), is(equalTo("type/plains")));
        }

        /**
         * Asserts the directory token survives the join the renderer performs: a baby ref carries a
         * {@code /baby/} path segment once the entity texture prefix is prepended, and an adult ref
         * carries {@code /type/} and never that segment. The renderer decides which robe a pass draws
         * by looking for exactly that segment in the joined ref, so a token that lost or gained a
         * boundary here would swap every baby robe for an adult one without failing anything else.
         */
        @Test
        @DisplayName("the joined baby ref carries a /baby/ segment where the adult ref carries /type/")
        void theJoinedRefsCarryTheirOwnDirectorySegment() {
            for (VillagerType type : VillagerType.values()) {
                String baby = PREFIX + "/" + type.babyOverlaySubPath();
                String adult = PREFIX + "/" + type.overlaySubPath();
                assertThat(type.name(), baby, containsString("/baby/"));
                assertThat(type.name(), adult, containsString("/type/"));
                assertThat(type.name(), adult, is(not(containsString("/baby/"))));
                assertThat(type.name(), type.babyOverlaySubPath(), startsWith("baby/"));
            }
        }

        /** Asserts the name lookup round-trips every biome, folds case, and answers null off the enum. */
        @Test
        @DisplayName("the name lookup round-trips, folds case and answers null for anything else")
        void theNameLookupRoundTripsAndAnswersNullOffTheEnum() {
            for (VillagerType type : VillagerType.values())
                assertThat(VillagerType.ofName(type.name().toLowerCase(Locale.ROOT)), is(type));

            assertThat(VillagerType.ofName("SaVaNnA"), is(VillagerType.SAVANNA));
            assertThat(VillagerType.ofName("badlands"), is(nullValue()));
            assertThat(VillagerType.ofName(null), is(nullValue()));
        }
    }

    @Nested
    @DisplayName("badge tier")
    class BadgeTier {

        /**
         * Asserts the floor tier is read off the first declared constant by index, so the DECLARATION
         * ORDER is the contract and not a convenience: reordering the enum moves what an unnamed level
         * draws, with nothing else in the type to contradict it.
         */
        @Test
        @DisplayName("the minimum tier is the first declared constant, read by index")
        void theMinimumTierIsTheFirstDeclaredConstant() {
            assertThat(VillagerLevel.minimum(), is(VillagerLevel.STONE));
            assertThat(VillagerLevel.minimum(), is(VillagerLevel.values()[0]));
        }

        /** Asserts the five tiers are declared in vanilla's ascending trade order. */
        @Test
        @DisplayName("the five tiers are declared in ascending trade order")
        void theFiveTiersAreDeclaredInAscendingTradeOrder() {
            assertThat(List.of(VillagerLevel.values()), contains(
                VillagerLevel.STONE, VillagerLevel.IRON, VillagerLevel.GOLD,
                VillagerLevel.EMERALD, VillagerLevel.DIAMOND));
        }

        /** Asserts every tier's badge sub-path is its own lower-cased name under {@code profession_level/}. */
        @Test
        @DisplayName("a badge sub-path is profession_level/ plus the lower-cased constant name")
        void aBadgeSubPathIsTheLowerCasedConstantName() {
            for (VillagerLevel level : VillagerLevel.values()) {
                assertThat(level.name(), level.overlaySubPath(),
                    is(equalTo("profession_level/" + level.name().toLowerCase(Locale.ROOT))));
            }
        }

        /** Asserts the name lookup round-trips every tier, folds case, and answers null off the enum. */
        @Test
        @DisplayName("the name lookup round-trips, folds case and answers null for anything else")
        void theNameLookupRoundTripsAndAnswersNullOffTheEnum() {
            for (VillagerLevel level : VillagerLevel.values())
                assertThat(VillagerLevel.ofName(level.name().toLowerCase(Locale.ROOT)), is(level));

            assertThat(VillagerLevel.ofName("EmErAlD"), is(VillagerLevel.EMERALD));
            assertThat(VillagerLevel.ofName("netherite"), is(nullValue()));
            assertThat(VillagerLevel.ofName(null), is(nullValue()));
        }
    }

}
