ALTER TABLE demand_line_history DROP CONSTRAINT demand_line_history_action_check;
ALTER TABLE demand_line_history ADD CONSTRAINT demand_line_history_action_check CHECK (
    action IN ('CREATED', 'QUANTITY_CHANGED', 'COVERAGE_CHANGED', 'STATUS_CHANGED', 'CANCELLED', 'STOCK_RESERVED')
);
