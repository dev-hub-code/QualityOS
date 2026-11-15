-- Email is in English only, so a user no longer chooses an email language.
ALTER TABLE users DROP COLUMN preferred_language;
