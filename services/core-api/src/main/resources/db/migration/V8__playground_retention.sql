-- M7: retention for playground questions.
--
-- Questions put to the assistant from the playground are stored in ai_interactions with no
-- conversation, so deleting conversations never removes them. This function deletes a workspace's
-- playground records older than that workspace's own conversation retention period. The cutoff is
-- read from the tenant row, not passed in, so the caller cannot choose what to delete. It acts only
-- on the workspace bound for the transaction (ADR-020).
CREATE FUNCTION delete_expired_playground_interactions(p_limit integer) RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    v_tenant uuid := app_current_tenant_id();
    v_days integer;
    v_deleted integer;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'no workspace bound' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT conversation_retention_days INTO v_days FROM tenants WHERE id = v_tenant;
    IF v_days IS NULL THEN
        RETURN 0;
    END IF;

    DELETE FROM ai_interactions
    WHERE tenant_id = v_tenant
      AND id IN (
          SELECT i.id FROM ai_interactions i
          WHERE i.tenant_id = v_tenant
            AND i.source = 'PLAYGROUND'
            AND i.created_at < now() - make_interval(days => v_days)
            AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.tenant_id = v_tenant AND m.interaction_id = i.id)
          ORDER BY i.created_at
          LIMIT greatest(p_limit, 0));
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

REVOKE ALL ON FUNCTION delete_expired_playground_interactions(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION delete_expired_playground_interactions(integer) TO ${app_role};
