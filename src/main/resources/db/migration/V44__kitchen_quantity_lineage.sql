-- V43 is retained verbatim. A kitchen_ticket_item is the immutable original work
-- for one station. Allocations are immutable revisions; only superseded changes.
-- Each work conserves its sent quantity independently (station fan-out is not stock).
-- Old transfers cannot safely be guessed from today's financial quantities.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM kitchen_ticket_items w JOIN order_items i ON i.id = w.order_item_id
             WHERE w.quantity <> i.quantity + i.voided_quantity) THEN
    RAISE EXCEPTION 'KDS lineage backfill blocked: V43 work has moved quantities. Reconcile historical restaurant transfer events before upgrading; do not guess or discard kitchen work.';
  END IF;
END $$;

CREATE TABLE kitchen_item_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_id UUID NOT NULL REFERENCES kitchen_ticket_items(id),
    parent_id UUID REFERENCES kitchen_item_allocations(id),
    current_order_item_id UUID NOT NULL REFERENCES order_items(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status VARCHAR(10) NOT NULL CHECK (status IN ('NEW','ACCEPTED','PREPARING','READY','SERVED')),
    voided BOOLEAN NOT NULL DEFAULT FALSE,
    void_reason VARCHAR(20),
    note VARCHAR(300),
    course VARCHAR(10),
    superseded BOOLEAN NOT NULL DEFAULT FALSE,
    action VARCHAR(16) NOT NULL,
    operation_id UUID REFERENCES pos_sync_operations(operation_id),
    device_id UUID REFERENCES pos_devices(id),
    staff_id UUID REFERENCES app_users(id),
    staff_name VARCHAR(160),
    manager_id UUID REFERENCES app_users(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_kitchen_allocation_line ON kitchen_item_allocations(current_order_item_id) WHERE NOT superseded;
CREATE INDEX ix_kitchen_allocation_work ON kitchen_item_allocations(work_id);
CREATE INDEX ix_kitchen_allocation_parent ON kitchen_item_allocations(parent_id);

-- Backfill two leaves where some units had already been voided, retaining V43 history.
INSERT INTO kitchen_item_allocations(work_id,current_order_item_id,quantity,status,voided,void_reason,note,course,action,occurred_at)
SELECT w.id,w.order_item_id,w.quantity-w.voided_quantity,t.status,false,NULL,w.note,w.course,'BACKFILL',now()
FROM kitchen_ticket_items w JOIN kitchen_tickets t ON t.id=w.ticket_id WHERE w.quantity>w.voided_quantity;
INSERT INTO kitchen_item_allocations(work_id,current_order_item_id,quantity,status,voided,void_reason,note,course,action,occurred_at)
SELECT w.id,w.order_item_id,w.voided_quantity,t.status,true,w.void_reason,w.note,w.course,'BACKFILL',now()
FROM kitchen_ticket_items w JOIN kitchen_tickets t ON t.id=w.ticket_id WHERE w.voided_quantity>0;

CREATE FUNCTION protect_kitchen_allocation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Kitchen allocation history cannot be deleted'; END IF;
  IF OLD.superseded OR NOT NEW.superseded OR
     (to_jsonb(OLD) - 'superseded') <> (to_jsonb(NEW) - 'superseded') THEN
    RAISE EXCEPTION 'Kitchen allocation history is immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER kitchen_allocation_immutable BEFORE UPDATE OR DELETE ON kitchen_item_allocations
FOR EACH ROW EXECUTE FUNCTION protect_kitchen_allocation();

-- Deferred because replacing a leaf inserts its descendants in the same transaction.
CREATE FUNCTION check_kitchen_allocation_quantity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE sent INTEGER; leaves BIGINT; p kitchen_item_allocations; w UUID;
BEGIN
  w := NEW.work_id;
  SELECT quantity INTO sent FROM kitchen_ticket_items WHERE id=w FOR UPDATE;
  SELECT COALESCE(sum(quantity),0) INTO leaves FROM kitchen_item_allocations WHERE work_id=w AND NOT superseded;
  IF leaves <> sent THEN RAISE EXCEPTION 'Kitchen quantity conservation failed: work %, sent %, leaves %',w,sent,leaves; END IF;
  IF NEW.parent_id IS NOT NULL THEN
    SELECT * INTO p FROM kitchen_item_allocations WHERE id=NEW.parent_id;
    IF p.work_id <> w OR NOT p.superseded THEN RAISE EXCEPTION 'Invalid kitchen allocation parent'; END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM kitchen_item_allocations a WHERE a.work_id=w AND a.superseded
      AND a.quantity <> (SELECT COALESCE(sum(c.quantity),0) FROM kitchen_item_allocations c WHERE c.parent_id=a.id)) THEN
    RAISE EXCEPTION 'Kitchen split children must equal parent quantity';
  END IF;
  IF EXISTS (SELECT 1 FROM kitchen_item_allocations a
      JOIN kitchen_ticket_items wi ON wi.id=a.work_id JOIN kitchen_tickets t ON t.id=wi.ticket_id
      JOIN order_items oi ON oi.id=a.current_order_item_id JOIN customer_orders o ON o.id=oi.order_id
      WHERE a.work_id=w AND t.store_id<>o.store_id) THEN RAISE EXCEPTION 'Kitchen allocation crossed store boundary'; END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER kitchen_allocation_quantity AFTER INSERT OR UPDATE ON kitchen_item_allocations
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_kitchen_allocation_quantity();

CREATE FUNCTION protect_kitchen_original_work() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Original kitchen work is immutable; append an allocation revision';
END $$;
CREATE TRIGGER kitchen_original_work_immutable BEFORE UPDATE OR DELETE ON kitchen_ticket_items
FOR EACH ROW EXECUTE FUNCTION protect_kitchen_original_work();
