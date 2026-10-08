package dev.colony.camera.worker;

import org.junit.jupiter.api.Test;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class WorkerPreferencesTest {
    @Test void defaultsKeepExistingWorkersWorkingAndEachTaskIsIndependent() {
        var preferences = new WorkerPreferences(); for (var task : WorkerPreferences.Task.values()) assertTrue(preferences.allows(Objects.requireNonNull(task)));
        preferences.allows(WorkerPreferences.Task.MINE, false); assertFalse(preferences.allows(WorkerPreferences.Task.MINE));
        for (var task : WorkerPreferences.Task.values()) if (task != WorkerPreferences.Task.MINE) assertTrue(preferences.allows(Objects.requireNonNull(task)));
        assertTrue(preferences.automaticTools()); preferences.automaticTools(false); assertFalse(preferences.automaticTools());
        assertTrue(preferences.allows(WorkerPreferences.Task.BUILD));
    }
}
