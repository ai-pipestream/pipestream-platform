-- PostgreSQL initialization script for compose-devservices
-- Creates additional databases for services

-- Create databases
CREATE DATABASE infisical;
CREATE DATABASE apicurio_registry;
CREATE DATABASE pipeline_repository_dev;
CREATE DATABASE pipeline_connector_dev;
CREATE DATABASE pipeline_account_dev;
CREATE DATABASE pipeline_engine_dev;
CREATE DATABASE pipeline_connector_intake_dev;


-- Grant privileges to pipeline user
GRANT ALL PRIVILEGES ON DATABASE infisical TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE apicurio_registry TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE pipeline_repository_dev TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE pipeline_repository_test TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE pipeline_connector_dev TO pipeline;

