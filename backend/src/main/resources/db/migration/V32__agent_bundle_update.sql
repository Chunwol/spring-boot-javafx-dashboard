ALTER TABLE pc_master ADD COLUMN bundle_version VARCHAR(40);
ALTER TABLE file_distribution_job ADD COLUMN update_package_type VARCHAR(24) DEFAULT 'JAR';
