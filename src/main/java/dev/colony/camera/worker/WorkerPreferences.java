package dev.colony.camera.worker;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

/** Work permissions do not change the colony's global priority or FIFO order. */
@ParametersAreNonnullByDefault
public final class WorkerPreferences {
    public enum Task { BUILD, MINE, SWEEP, CRAFT, RESEARCH }
    @Nonnull private final Set<Task> enabled = Objects.requireNonNull(EnumSet.allOf(Task.class));
    private boolean automaticTools = true;
    public boolean allows(Task task) { return enabled.contains(task); }
    public void allows(Task task, boolean value) { if (value) enabled.add(task); else enabled.remove(task); }
    public boolean automaticTools() { return automaticTools; }
    public void automaticTools(boolean value) { automaticTools = value; }
}
