# Room schema history

Room's annotation processor exports schema snapshots here during an authorized build.
Version 1's snapshot is retained unchanged. Version 2 has not been generated yet:
the complete-record storage change was made without running tests or a build.

Version 2 stores complete diary records in `diary_metadata`, plus indexed media
references in `diary_media_reference`. Photos, videos, drafts and pending media
retain their existing storage. `MIGRATION_1_2` adds the required columns and table
without discarding the old metadata or initialization state.

Before the first saved-record operation, `DiaryLegacyMigrator` imports legacy JSON
files one at a time into a single transaction, replacing the old derived metadata.
The import and marker row `diary_index_state.id = 2` commit together. The v1 marker
(`id = 1`) only described the old index and cannot skip this import. Any read or
database failure rolls back the import; original files are retained for retry.

Once marker 2 exists, Room is authoritative. Ordinary reads and writes do not scan
the legacy directory, rebuild from JSON, or consult `diary_index.dirty`. Retained
JSON and legacy index files are historical recovery copies, not a current backup;
they must not be reimported after edits or deletions. Keep the Room database and
its import marker together when backing up or restoring app data.

Feeds query at most 10 complete records per request, ordered by `updatedAt DESC,
id DESC`. A separate indexed `EXISTS` query determines whether a next page exists.
The explicit `DiaryStore.list()` API still returns all matching Room records for
callers that need a full list; feeds and topic selection do not use it.

On the next authorized build, generate and review the version 2 JSON snapshot.
For subsequent schema changes, increment `DiaryDatabase.version` and register
explicit migrations. Never enable destructive migration or edit historical snapshots.
