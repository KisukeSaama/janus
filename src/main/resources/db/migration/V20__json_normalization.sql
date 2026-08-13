-- A gateway that spares its callers everything surrounding a third-party call — the credential, the
-- cache, the quota, the retries — still hands them whatever the upstream chose to speak. For a
-- self-hosted deployment that is often XML: Plex answers in it, so do Newznab indexers, podcast
-- feeds, and every SOAP endpoint anyone still runs. Each client service then carries a parser for a
-- format it never asked for, and two clients of the same API carry two different ones.
--
-- This column moves that decision to where the rest of the traffic policy already lives. With it
-- set, Janus converts what comes back into JSON, and a caller talks to Plex the way it talks to
-- everything else.
--
-- What the column deliberately does not say is which format to expect. The converter is chosen from
-- the response's own Content-Type, not from anything recorded here, because an API that answers XML
-- on one route and JSON on another is the ordinary case rather than the exception — Plex itself does
-- it. A response already in JSON is passed through untouched, and a format with no converter is
-- passed through too: a conversion is never a way for a request to fail.
ALTER TABLE providers
  ADD COLUMN normalize_json BOOLEAN NOT NULL DEFAULT FALSE;

-- Which elements must always be arrays, comma-separated.
--
-- XML has no way to say "this is a list". An element appearing once is indistinguishable from a list
-- of one, so a converter can only infer a list from a repetition it happens to see — and a library
-- holding one section returns an object where the same library holding two returns an array. The
-- client that worked yesterday breaks on the day a section is added, which is the worst moment for
-- it to break.
--
-- Nothing but a schema can settle this, and the APIs that answer XML are exactly the ones that
-- publish none. So it is stated here instead: a bare name (Directory) forces that element to an
-- array wherever it appears, and a dotted path (MediaContainer.Directory) forces it at that one
-- place. Left empty, the inference above applies and the caller lives with it.
ALTER TABLE providers
  ADD COLUMN json_array_paths VARCHAR(1000);
