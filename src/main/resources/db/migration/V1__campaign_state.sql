-- M2: campaigns with their oracle state, scenes, threads/characters lists and the roll log.
-- journal_entry and session_log arrive with M3.

create table campaign (
  id uuid primary key, name text not null, setting text, tone text,
  pc_summary text, chaos_factor smallint not null default 5 check (chaos_factor between 1 and 9),
  scene_number int not null default 0, created_at timestamptz not null default now()
);
create unique index campaign_name_key on campaign (lower(name));

create table scene (
  campaign_id uuid not null references campaign, number int not null, expected_setup text not null,
  actual_setup text, check_result text not null check (check_result in ('expected','altered','interrupted')),
  summary text, status text not null check (status in ('open','closed')),
  primary key (campaign_id, number)
);

create table list_entry (
  id uuid primary key, campaign_id uuid not null references campaign,
  list text not null check (list in ('thread','character')), title text not null,
  weight smallint not null default 1 check (weight between 1 and 3),
  status text not null, created_at timestamptz not null default now(),
  check ((list = 'thread' and status in ('open','closed'))
      or (list = 'character' and status in ('active','removed')))
);
create unique index list_entry_title_key on list_entry (campaign_id, list, lower(title));

create table roll_log (
  id bigserial primary key, campaign_id uuid not null references campaign, tool text not null,
  inputs jsonb not null, dice jsonb not null, result jsonb not null,
  scene_number int, created_at timestamptz default now()
);
