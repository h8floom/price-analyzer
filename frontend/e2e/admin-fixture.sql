-- Test account for the isolated Playwright H2 database only.
INSERT INTO accounts(email, password_hash, role) VALUES ('playwright-admin@example.test', '$2y$12$EaZJmdQnPe0n3EDZguQJe.GTeUBWLHrg.zNxFPelUFcYdZv5dfuh6', 'ADMIN');
