-- M3: the campaign journal and play sessions.

create table journal_entry (
  id uuid primary key, campaign_id uuid not null references campaign,
  kind text not null check (kind in ('npc','location','item','fact','note')),
  title text not null, body text not null, tags text[] not null default '{}', scene_number int not null,
  search tsvector generated always as (to_tsvector('simple', title || ' ' || body)) stored,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create unique index journal_entry_title_key on journal_entry (campaign_id, kind, lower(title));
create index journal_entry_search_idx on journal_entry using gin (search);

create table session_log (
  id uuid primary key, campaign_id uuid not null references campaign,
  started_at timestamptz not null default now(), ended_at timestamptz, summary text, open_questions text,
  status text not null default 'open' check (status in ('open','ended','abandoned'))
);
create unique index session_log_open_key on session_log (campaign_id) where status = 'open';
