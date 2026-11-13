ALTER TABLE orders
    ADD COLUMN refund_required_at TIMESTAMPTZ,
    ADD COLUMN refunded_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_orders_refunded_after_required
        CHECK ((refunded_at IS NULL) OR refund_required_at IS NOT NULL);
CREATE INDEX idx_refund_required_but_not_issued ON orders (refund_required_at)
    WHERE refund_required_at IS NOT NULL AND refunded_at IS NULL;