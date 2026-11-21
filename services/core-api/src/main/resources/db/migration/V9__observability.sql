-- M7: observability (ADR-022).
--
-- 1. The outbox keeps the W3C trace context of the request that wrote each event, so the delivery,
--    the worker's job and the indexing appear in the same trace as the upload. It is a trace and
--    span identifier and a flag byte, nothing else; the length and format are checked.
-- 2. operational_metrics() returns platform-wide counts for the metrics endpoint: documents by
--    status, the outbox backlog and its age, and open tickets. It returns aggregates only, never a
--    tenant identifier or any content, so it needs no tenant bound and is safe to run on a schedule.

ALTER TABLE outbox_events ADD COLUMN trace_parent text;
ALTER TABLE outbox_events ADD CONSTRAINT ck_outbox_events_trace_parent
    CHECK (trace_parent IS NULL OR trace_parent ~ '^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');

CREATE FUNCTION operational_metrics()
    RETURNS TABLE (metric text, label text, value double precision)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT 'documents', d.status, count(*)::double precision
    FROM documents d
    GROUP BY d.status
    UNION ALL
    -- Age of the oldest document still processing: a stuck worker shows up here.
    SELECT 'documents_processing_oldest_age_seconds', '',
           COALESCE(EXTRACT(EPOCH FROM now() - min(d.updated_at)), 0)::double precision
    FROM documents d
    WHERE d.status = 'PROCESSING'
    UNION ALL
    SELECT 'outbox_events', o.status, count(*)::double precision
    FROM outbox_events o
    WHERE o.status IN ('PENDING', 'FAILED')
    GROUP BY o.status
    UNION ALL
    SELECT 'outbox_oldest_pending_age_seconds', '',
           COALESCE(EXTRACT(EPOCH FROM now() - min(o.created_at)), 0)::double precision
    FROM outbox_events o
    WHERE o.status = 'PENDING'
    UNION ALL
    SELECT 'tickets', t.status, count(*)::double precision
    FROM tickets t
    WHERE t.status <> 'CLOSED'
    GROUP BY t.status
$$;

REVOKE ALL ON FUNCTION operational_metrics() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION operational_metrics() TO ${app_role};
