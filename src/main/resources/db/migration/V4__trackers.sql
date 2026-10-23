-- M3.4: trackers, numbers the story keeps such as Hunger; the server never reads what they mean.

create table tracker (
  id uuid primary key, campaign_id uuid not null references campaign, name text not null,
  value int not null, min_value int, max_value int, note text,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  check (min_value is null or max_value is null or min_value < max_value),
  check (min_value is null or value >= min_value), check (max_value is null or value <= max_value)
);
create unique index tracker_name_key on tracker (campaign_id, lower(name));
