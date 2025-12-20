-- PostgreSQL initialization script for compose-devservices
-- Creates additional databases for services

-- Create Infisical database
CREATE DATABASE infisical;

-- Grant privileges to pipeline user
GRANT ALL PRIVILEGES ON DATABASE infisical TO pipeline;

