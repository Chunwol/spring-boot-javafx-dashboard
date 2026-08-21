UPDATE pc_master
SET scmate_workflow_stage = CASE
    WHEN UPPER(scmate_mode) = 'OPERATOR' THEN 'OPERATOR_READY'
    WHEN UPPER(scmate_mode) = 'PROTECTED' THEN 'COMPLETED'
    ELSE scmate_workflow_stage
END
WHERE scmate_available = TRUE
  AND COALESCE(TRIM(scmate_error), '') = ''
  AND scmate_workflow_stage IN ('FAILED', 'INSTALL_REQUESTED', 'INSTALL_PENDING_REBOOT', 'OPERATOR_READY');
