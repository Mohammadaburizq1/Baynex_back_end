package com.byonix.shoplink.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** Runs inside the existing restaurant/kitchen operation transaction. No stock or financial writes. */
@Service
@RequiredArgsConstructor
public class KitchenAllocationService {
    private final JdbcTemplate jdbc;

    private record Leaf(UUID id, UUID work, UUID line, int quantity, String status,
                        boolean voided, String reason, String note, String course) {}

    public void sent(UUID work, UUID line, int quantity, String note, String course, KitchenService.Actor actor) {
        insert(null, work, line, quantity, "NEW", false, null, note, course, "SEND", actor, null);
    }

    public void move(UUID store, UUID from, UUID to, int quantity, KitchenService.Actor actor) {
        change(store, from, to, quantity, false, null, null, actor);
    }

    public void voided(UUID store, UUID line, int quantity, String reason, UUID manager, KitchenService.Actor actor) {
        change(store, line, line, quantity, true, reason, manager, actor);
    }

    private void change(UUID store, UUID from, UUID to, int quantity, boolean voided, String reason, UUID manager, KitchenService.Actor actor) {
        for (UUID work : lockWorks(store, from)) {
            List<Leaf> leaves = leaves(work).stream().filter(a -> a.line().equals(from) && !a.voided()).toList();
            final List<KitchenAllocationPolicy.Portion> portions;
            try {
                portions = KitchenAllocationPolicy.take(leaves.stream().map(Leaf::quantity).toList(), quantity);
            } catch (IllegalArgumentException mismatch) {
                throw new PosSyncRejectedException("KITCHEN_LINEAGE_CONFLICT", "Kitchen quantities differ from this restaurant line; no change was applied");
            }
            for (int i = 0; i < leaves.size(); i++) {
                Leaf a = leaves.get(i);
                var p = portions.get(i);
                if (p.taken() == 0) continue;
                retire(a);
                String action = voided ? "VOID" : "MOVE";
                insert(a.id(), work, to, p.taken(), a.status(), voided, reason, a.note(), a.course(), action, actor, manager);
                if (p.remaining() > 0) insert(a.id(), work, from, p.remaining(), a.status(), false, null,
                        a.note(), a.course(), "REMAINDER", actor, manager);
            }
        }
    }

    public void instructions(UUID store, UUID line, String note, String course, KitchenService.Actor actor) {
        for (UUID work : lockWorks(store, line)) {
            for (Leaf a : leaves(work)) {
                if (!a.line().equals(line) || a.voided()) continue;
                retire(a);
                insert(a.id(), work, line, a.quantity(), a.status(), false, null, note, course, "INSTRUCTION", actor, null);
            }
        }
    }

    /** Ticket controls revise its allocation leaves. A future quantity control can split the same leaves. */
    public void status(UUID ticket, String status, boolean recall, KitchenService.Actor actor) {
        List<UUID> works = jdbc.queryForList("SELECT id FROM kitchen_ticket_items WHERE ticket_id=? ORDER BY id FOR UPDATE", UUID.class, ticket);
        for (UUID work : works) {
            for (Leaf a : leaves(work)) {
                if (a.voided() || (!recall && KitchenService.FLOW.indexOf(status) <= KitchenService.FLOW.indexOf(a.status()))) continue;
                if (recall && !"SERVED".equals(a.status())) continue;
                retire(a);
                insert(a.id(), work, a.line(), a.quantity(), status, false, null, a.note(), a.course(), recall ? "RECALL" : "STATUS", actor, null);
            }
        }
    }

    private List<UUID> lockWorks(UUID store, UUID line) {
        // Same lock order as a KDS transition: ticket then work. The order lock serializes moves/voids.
        List<UUID> tickets = jdbc.queryForList("""
                SELECT DISTINCT w.ticket_id FROM kitchen_item_allocations a JOIN kitchen_ticket_items w ON w.id=a.work_id
                JOIN kitchen_tickets t ON t.id=w.ticket_id
                WHERE a.current_order_item_id=? AND NOT a.superseded AND NOT a.voided AND t.store_id=? ORDER BY w.ticket_id
                """, UUID.class, line, store);
        for (UUID ticket : tickets) jdbc.queryForList("SELECT id FROM kitchen_tickets WHERE id=? FOR UPDATE", UUID.class, ticket);
        List<UUID> works = jdbc.queryForList("""
                SELECT w.id FROM kitchen_ticket_items w JOIN kitchen_tickets t ON t.id=w.ticket_id
                WHERE t.store_id=? AND EXISTS (SELECT 1 FROM kitchen_item_allocations a
                  WHERE a.work_id=w.id AND a.current_order_item_id=? AND NOT a.superseded AND NOT a.voided)
                ORDER BY w.id FOR UPDATE OF w
                """, UUID.class, store, line);
        for (UUID ticket : tickets) jdbc.update("UPDATE kitchen_tickets SET version=version+1,updated_at=now() WHERE id=?", ticket);
        return works;
    }

    private List<Leaf> leaves(UUID work) {
        return jdbc.query("SELECT * FROM kitchen_item_allocations WHERE work_id=? AND NOT superseded ORDER BY created_at,id",
                (rs,n) -> new Leaf(rs.getObject("id",UUID.class),work,rs.getObject("current_order_item_id",UUID.class),
                        rs.getInt("quantity"),rs.getString("status"),rs.getBoolean("voided"),rs.getString("void_reason"),
                        rs.getString("note"),rs.getString("course")),work);
    }

    private void retire(Leaf a) {
        if (jdbc.update("UPDATE kitchen_item_allocations SET superseded=true WHERE id=? AND NOT superseded",a.id()) != 1)
            throw new PosSyncRejectedException("KITCHEN_LINEAGE_CONFLICT","This kitchen allocation changed concurrently");
    }

    private void insert(UUID parent, UUID work, UUID line, int quantity, String status, boolean voided, String reason,
                        String note, String course, String action, KitchenService.Actor actor, UUID manager) {
        jdbc.update("""
                INSERT INTO kitchen_item_allocations(parent_id,work_id,current_order_item_id,quantity,status,voided,void_reason,note,course,
                    action,operation_id,device_id,staff_id,staff_name,manager_id,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,parent,work,line,quantity,status,voided,reason,note,course,action,actor.operationId(),actor.deviceId(),
                actor.staffId(),actor.staffName(),manager,Timestamp.from(actor.at()));
    }
}
