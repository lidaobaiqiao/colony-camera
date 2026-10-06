package dev.colony.camera.resources;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reservations describe claims, never ownership of a copied item. Rebuilt in project order. */
public final class ReservationLedger {
    public record Claim(@Nonnull UUID worker, @Nonnull UUID project, @Nonnull UUID source, int quantity) {
        public Claim {
            Objects.requireNonNull(worker); Objects.requireNonNull(project); Objects.requireNonNull(source);
            if (quantity < 1) throw new IllegalArgumentException("Invalid claim quantity");
        }
    }
    private final Map<UUID, Claim> claims = new HashMap<>();
    private final Map<UUID, Map<UUID, Claim>> batches = new HashMap<>();
    public record Demand(int stock, int quantity) {}

    public int reserved(UUID source) {
        return claims.values().stream().filter(c -> c.source().equals(source)).mapToInt((Claim checkedElement) -> Objects.requireNonNull(checkedElement).quantity()).sum()
                + batches.values().stream().flatMap(batch -> batch.values().stream()).filter(c -> c.source().equals(source)).mapToInt((Claim checkedElement) -> Objects.requireNonNull(checkedElement).quantity()).sum();
    }
    public boolean reserve(UUID worker, UUID project, UUID source, int stock, int quantity) {
        if (stock < 0 || quantity < 1 || claims.containsKey(worker) || batches.containsKey(worker) || quantity > stock - reserved(source)) return false;
        claims.put(worker, new Claim(worker, project, source, quantity));
        return true;
    }
    /** Atomic ingredient reservation; a missing ingredient cannot strand claims for the others. */
    public boolean reserveBatch(UUID worker, UUID project, Map<UUID, Demand> demands) {
        if (claims.containsKey(worker) || batches.containsKey(worker)) return false;
        for (var entry : demands.entrySet()) {
            Demand need = entry.getValue();
            if (need.stock() < 0 || need.quantity() < 1 || need.quantity() > need.stock() - reserved(entry.getKey())) return false;
        }
        Map<UUID, Claim> batch = new HashMap<>();
        demands.forEach((source, need) -> batch.put(source, new Claim(Objects.requireNonNull(worker), Objects.requireNonNull(project), Objects.requireNonNull(source), need.quantity())));
        batches.put(worker, batch); return true;
    }
    @Nullable public Claim claim(UUID worker) {
        Claim single = claims.get(worker); if (single != null) return single;
        var batch = batches.get(worker); return batch == null ? null : batch.values().stream().findFirst().orElse(null);
    }
    public void release(UUID worker) { claims.remove(worker); batches.remove(worker); }
    public void clear() { claims.clear(); batches.clear(); }
}
