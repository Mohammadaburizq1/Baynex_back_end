-- POS-27: kitchen display. Additive only. The kitchen works from the restaurant order lines of POS-26
-- (order_items of an open restaurant order); nothing here is a second order or a financial record.
--
-- A kitchen ticket is one "send" of one order for one station: the lines an ADD_ITEMS operation added,
-- grouped by the station they route to. Adding a dessert later makes a new ticket; the burger sent
-- earlier is never fired again. Ticket items point at the order line and keep what the kitchen was
-- asked to make (quantity sent, name, add-ons, note, course), plus what was voided since and why.

CREATE TABLE kitchen_stations (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_kitchen_stations_active_name ON kitchen_stations(store_id, lower(name)) WHERE active;

-- Routing: a category sends its products to one or more stations; a product's own routes override its
-- category's. An item routed nowhere goes to the general ticket (station NULL, shown on every screen).
CREATE TABLE kitchen_station_categories (
    station_id UUID NOT NULL REFERENCES kitchen_stations(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (station_id, category_id)
);
CREATE TABLE kitchen_station_products (
    station_id UUID NOT NULL REFERENCES kitchen_stations(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    PRIMARY KEY (station_id, product_id)
);
CREATE INDEX ix_kitchen_station_products_product ON kitchen_station_products(product_id);
CREATE INDEX ix_kitchen_station_categories_category ON kitchen_station_categories(category_id);

CREATE TABLE kitchen_tickets (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    -- The order the ticket's lines are on now (a merge moves them), and the order that sent them.
    order_id UUID NOT NULL REFERENCES customer_orders(id) ON DELETE CASCADE,
    origin_order_id UUID NOT NULL REFERENCES customer_orders(id) ON DELETE CASCADE,
    sent_version INTEGER NOT NULL,
    station_id UUID REFERENCES kitchen_stations(id) ON DELETE SET NULL,
    station_name VARCHAR(60),
    status VARCHAR(10) NOT NULL CHECK (status IN ('NEW', 'ACCEPTED', 'PREPARING', 'READY', 'SERVED')),
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE,
    ready_at TIMESTAMP WITH TIME ZONE,
    served_at TIMESTAMP WITH TIME ZONE,
    -- +1 per change (status, void, note); what a kitchen screen compares to know it is current.
    version INTEGER NOT NULL DEFAULT 1,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_kitchen_tickets_send ON kitchen_tickets(origin_order_id, sent_version, COALESCE(station_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX ix_kitchen_tickets_board ON kitchen_tickets(store_id, status, updated_at);
CREATE INDEX ix_kitchen_tickets_order ON kitchen_tickets(order_id);

CREATE TABLE kitchen_ticket_items (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES kitchen_tickets(id) ON DELETE CASCADE,
    order_item_id UUID NOT NULL REFERENCES order_items(id) ON DELETE CASCADE,
    line_uid UUID,
    product_name VARCHAR(300) NOT NULL,
    variant_label VARCHAR(200),
    modifiers TEXT,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    voided_quantity INTEGER NOT NULL DEFAULT 0 CHECK (voided_quantity >= 0),
    void_reason VARCHAR(20),
    note VARCHAR(300),
    course VARCHAR(10),
    line_no INTEGER
);
CREATE INDEX ix_kitchen_ticket_items_ticket ON kitchen_ticket_items(ticket_id);
CREATE INDEX ix_kitchen_ticket_items_line ON kitchen_ticket_items(order_item_id);

-- Audit: every status change, recall and void a ticket went through (who, which screen, when).
CREATE TABLE kitchen_ticket_events (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    ticket_id UUID NOT NULL REFERENCES kitchen_tickets(id) ON DELETE CASCADE,
    operation_id UUID REFERENCES pos_sync_operations(operation_id) ON DELETE SET NULL,
    device_id UUID REFERENCES pos_devices(id) ON DELETE SET NULL,
    event_type VARCHAR(10) NOT NULL CHECK (event_type IN ('CREATED', 'STATUS', 'RECALL', 'VOID', 'NOTE', 'STALE')),
    from_status VARCHAR(10),
    to_status VARCHAR(10),
    staff_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    staff_name VARCHAR(160),
    detail TEXT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_kitchen_ticket_events_ticket ON kitchen_ticket_events(ticket_id, created_at);
