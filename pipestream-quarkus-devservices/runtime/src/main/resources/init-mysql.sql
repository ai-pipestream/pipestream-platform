-- Initialize additional databases for dev services
CREATE DATABASE IF NOT EXISTS pipeline;
GRANT ALL PRIVILEGES ON pipeline.* TO 'pipeline'@'%';

CREATE DATABASE IF NOT EXISTS apicurio_registry;
GRANT ALL PRIVILEGES ON apicurio_registry.* TO 'pipeline'@'%';

CREATE DATABASE IF NOT EXISTS pipeline_connector_dev;
GRANT ALL PRIVILEGES ON pipeline_connector_dev.* TO 'pipeline'@'%';

CREATE DATABASE IF NOT EXISTS pipeline_connector_intake_dev;
GRANT ALL PRIVILEGES ON pipeline_connector_intake_dev.* TO 'pipeline'@'%';

CREATE DATABASE IF NOT EXISTS pipeline_repo_dev;
GRANT ALL PRIVILEGES ON pipeline_repo_dev.* TO 'pipeline'@'%';

FLUSH PRIVILEGES;
