-- Non-secret operational-query summary for the accepted Decision 1 serving proof.
-- Evidence source SHA-256: 2525541c5abfe2b9b18eae7f6a3deb0935fded5b52b407f2668a085bba5590a1
-- Target: subscription ee625b3f-7cb1-4482-be3c-4363c5d76d23; resource group wealth-azure-prod-rg;
-- portfolio-service revision portfolio-service--0000095; database neondb; effective role neondb_owner.

SELECT version, description, success, checksum
FROM flyway_schema_history
WHERE version = '21';

SELECT to_regprocedure('public.repair_archive_row(text,text,text,text,jsonb)') IS NULL AS repair_archive_row_absent,
       to_regprocedure('public.repair_migrate_holdings(text,text,text)') IS NULL AS repair_migrate_holdings_absent,
       to_regprocedure('public.repair_migrate_market_prices(text,text,text,text,boolean)') IS NULL AS repair_migrate_market_prices_absent,
       to_regprocedure('public.repair_migrate_history(text,text,text)') IS NULL AS repair_migrate_history_absent;

SELECT count(*) AS matching_repair_routines_all_schemas
FROM pg_proc
WHERE proname IN ('repair_archive_row', 'repair_migrate_holdings',
                  'repair_migrate_market_prices', 'repair_migrate_history');
