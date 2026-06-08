-- Rename MEMBER → EDITOR so existing members keep write access
UPDATE watchlist_members SET role = 'EDITOR' WHERE role = 'MEMBER';
