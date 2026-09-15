package lib.minecraft.renderer.pose.compile;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.asset.model.EntityModelData;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The members of one indexed family a mesh declares, in the order the mesh numbers them.
 *
 * <p>A family is a spelling and nothing else - one word and a running number - which is the whole
 * of what it buys over naming each bone: the count is the mesh's answer rather than the author's.
 * It asserts no arrangement, because the meshes sharing a spelling do not share one. Two meshes
 * both spelling {@code tailN} hold one as a parent chain, where a stance stated once compounds
 * down the links, and the other as unparented siblings, where it does not; a family reports which
 * it met and stamps what the author wrote either way.
 */
@UtilityClass
@Parity(subject = Subject.ENTITY)
public final class LimbFamily {

    /**
     * A member's spelling - the stem alone, or the stem and a number across an optional
     * underscore. The run of digits is bounded so a name no reader would call an index cannot
     * overflow the ordering.
     */
    private static final @NotNull String INDEX = "(?:_?(\\d{1,9}))?";

    /** Where the bare stem sorts against the numbered members. */
    private static final int UNNUMBERED = -1;

    /**
     * Every bone one stem addresses on a mesh, the bare stem first and the rest in numeric order.
     *
     * @param mesh the mesh being asked
     * @param stem the name every member begins with
     * @return the bones addressed, empty where the mesh declares none
     */
    public static @NotNull ConcurrentList<String> members(@NotNull EntityModelData mesh,
                                                          @NotNull String stem) {
        Pattern spelling = Pattern.compile(Pattern.quote(stem) + INDEX);
        List<Member> found = new ArrayList<>();
        for (String bone : mesh.getBones().keySet()) {
            Matcher spelled = spelling.matcher(bone);
            if (!spelled.matches()) continue;
            String digits = spelled.group(1);
            found.add(new Member(digits == null ? UNNUMBERED : Integer.parseInt(digits), bone));
        }
        found.sort(Comparator.comparingInt(Member::index).thenComparing(Member::bone));
        return Concurrent.newUnmodifiableList(found.stream().map(Member::bone).toList());
    }

    /**
     * Whether one stem's members hang off one another rather than standing as siblings.
     *
     * <p>A stance stated once reaches every member either way, and what differs is what the
     * subject then looks like: on a chain each member carries the members below it, so one uniform
     * turn compounds down the links, where siblings each turn by what they were given.
     *
     * <p>It is asked of a stem rather than of a list of bones, so the members it reads are the ones
     * {@link #members} answers for that stem and there is no set a caller can ask this of that the
     * stem does not spell.
     *
     * @param mesh the mesh being asked
     * @param stem the name every member begins with
     * @return {@code true} where any member names another as its parent
     */
    public static boolean chained(@NotNull EntityModelData mesh, @NotNull String stem) {
        Map<String, EntityModelData.Bone> bones = mesh.getBones();
        Set<String> members = Set.copyOf(members(mesh, stem));
        for (String member : members) {
            EntityModelData.Bone bone = bones.get(member);
            if (bone != null && bone.getParent() != null && members.contains(bone.getParent()))
                return true;
        }
        return false;
    }

    /**
     * One bone a stem addressed, and where it sorts among its siblings.
     *
     * @param index the number the mesh spells it with, or {@link #UNNUMBERED} for the bare stem
     * @param bone the bone name, as the mesh names it
     */
    private record Member(int index, @NotNull String bone) {}

}
