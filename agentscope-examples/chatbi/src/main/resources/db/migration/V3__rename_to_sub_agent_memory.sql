-- ======================================================
-- V3: Rename data_lineage_memory to sub_agent_memory
-- (only if the old table exists; safe for fresh DBs)
-- ======================================================

-- Rename the old table if it exists (no-op on fresh DB)
RENAME TABLE IF EXISTS data_lineage_memory TO sub_agent_memory;

-- Add `type` column if it doesn't already exist (for old DBs
-- that had the table without the type column).
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'sub_agent_memory'
      AND column_name = 'type'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE sub_agent_memory ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT ''dl'' COMMENT ''Intent type: dl=lineage, re=report'' AFTER session_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;