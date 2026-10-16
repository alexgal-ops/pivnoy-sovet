-- Пивной совет: таблицы для пушей и триггер, который зовёт функцию notify на каждую запись в docs.
create extension if not exists pg_net;

create table if not exists public.push_keys (id int primary key, pub text not null, priv text not null);
create table if not exists public.push_subs (endpoint text primary key, member text not null, sub jsonb not null, updated_at timestamptz not null default now());
create table if not exists public.push_sent (event text primary key, at timestamptz not null default now());
-- RLS on with no policies: only the server function (service role) can read or write these.
alter table public.push_keys enable row level security;
alter table public.push_subs enable row level security;
alter table public.push_sent enable row level security;

create or replace function public.notify_push() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform net.http_post(
    url := 'https://rnqqgszaxdjwcmqwytth.supabase.co/functions/v1/notify',
    body := jsonb_build_object('id', NEW.id),
    headers := '{"Content-Type": "application/json"}'::jsonb
  );
  return NEW;
end $$;

drop trigger if exists docs_notify on public.docs;
create trigger docs_notify after insert or update on public.docs
  for each row execute function public.notify_push();

select 'ok' as setup;
