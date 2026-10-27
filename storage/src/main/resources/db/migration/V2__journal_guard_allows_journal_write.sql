-- The journal guard ran before every INSERT too, so it refused
-- journal_write()'s own entries and with them every audited write. Only
-- updates, deletes and truncation are guarded: what may insert is already
-- limited by privilege (database.ServiceRole revokes INSERT on the journal
-- from the service, and journal_write() inserts as the journal's owner).

DROP TRIGGER journal_guard ON journal;

CREATE TRIGGER journal_guard
  BEFORE UPDATE OR DELETE ON journal
  FOR EACH ROW EXECUTE FUNCTION journal_guard();

CREATE TRIGGER journal_guard_truncate
  BEFORE TRUNCATE ON journal
  FOR EACH STATEMENT EXECUTE FUNCTION journal_guard();

-- category has no "dateModified", so touch_date_modified() failed any
-- update of a category.
DROP TRIGGER category_touch_date_modified ON category;
