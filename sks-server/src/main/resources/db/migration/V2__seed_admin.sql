INSERT INTO admin_user (username, password_hash, name, status)
VALUES ('__seed__', 'PLACEHOLDER', '站长本人', 'active')
ON CONFLICT (username) DO NOTHING;
