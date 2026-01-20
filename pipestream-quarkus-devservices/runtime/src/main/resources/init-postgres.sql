-- PostgreSQL initialization script for compose-devservices
-- Creates additional databases for services (simple naming convention)

-- Create databases
CREATE DATABASE infisical;
CREATE DATABASE apicurio_registry;
CREATE DATABASE platform_registration;
CREATE DATABASE connector_intake;
CREATE DATABASE connector_admin;
CREATE DATABASE account;
CREATE DATABASE repository;
CREATE DATABASE engine;
CREATE DATABASE s3_connector;

-- Grant privileges to pipeline user
GRANT ALL PRIVILEGES ON DATABASE infisical TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE apicurio_registry TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE platform_registration TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE connector_intake TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE connector_admin TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE account TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE repository TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE engine TO pipeline;
GRANT ALL PRIVILEGES ON DATABASE s3_connector TO pipeline;
