-- =====================================================================
-- IPTV Lecteur (tables préfixées iptv_ : cohabite sans souci avec un autre projet) : codes d'accès uniques (1 code = 1 appareil)
-- À exécuter UNE FOIS dans Supabase > SQL Editor > New query > Run
-- =====================================================================

create table if not exists public.iptv_licences (
  code          text primary key,              -- ex. K7QM-3XPA-9RTD
  label         text not null default '',      -- à qui tu l'as donné (nom, poste…)
  device_id     text,                          -- appareil lié (rempli à la 1re activation)
  device_name   text,                          -- modèle de l'appareil (ex. Samsung SM-A155F)
  active        boolean not null default true, -- false = code désactivé
  expires_at    timestamptz,                   -- vide = sans limite de durée
  activated_at  timestamptz,
  last_seen_at  timestamptz,                   -- dernière ouverture de l'app
  created_at    timestamptz not null default now()
);

-- Sécurité : RLS activé SANS aucune policy.
-- L'app ne peut ni lire ni modifier la table : elle passe uniquement par iptv_activate_licence().
alter table public.iptv_licences enable row level security;

-- ---------------------------------------------------------------------
-- Appelée par l'app à chaque ouverture.
-- ---------------------------------------------------------------------
create or replace function public.iptv_activate_licence(p_code text, p_device text, p_device_name text default '')
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
  l public.iptv_licences;
  c text := upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
begin
  if length(c) < 8 or coalesce(p_device, '') = '' then
    return json_build_object('ok', false, 'reason', 'invalid');
  end if;

  select * into l from public.iptv_licences where replace(code, '-', '') = c;
  if not found then
    return json_build_object('ok', false, 'reason', 'invalid');
  end if;
  if not l.active then
    return json_build_object('ok', false, 'reason', 'revoked');
  end if;
  if l.expires_at is not null and l.expires_at < now() then
    return json_build_object('ok', false, 'reason', 'expired');
  end if;

  if l.device_id is null then
    update public.iptv_licences
       set device_id = p_device, device_name = left(p_device_name, 80),
           activated_at = now(), last_seen_at = now()
     where code = l.code;
  elsif l.device_id <> p_device then
    return json_build_object('ok', false, 'reason', 'used');
  else
    update public.iptv_licences set last_seen_at = now() where code = l.code;
  end if;

  return json_build_object('ok', true, 'label', l.label, 'expires_at', l.expires_at);
end;
$$;

revoke all on function public.iptv_activate_licence(text, text, text) from public;
grant execute on function public.iptv_activate_licence(text, text, text) to anon;

-- ---------------------------------------------------------------------
-- Pour TOI uniquement (SQL Editor) : créer des codes.
-- Exemples :
--   select * from public.iptv_creer_codes(1, 'Kouamé');            -- 1 code sans limite
--   select * from public.iptv_creer_codes(5, 'Bureau Plateau', 30); -- 5 codes valables 30 jours
-- ---------------------------------------------------------------------
create or replace function public.iptv_creer_codes(p_nombre int, p_label text default '', p_jours int default null)
returns table(code text, label text, expire_le timestamptz)
language plpgsql
set search_path = public
as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; -- sans 0/O ni 1/I (évite les confusions)
  pos constant int[] := array[0,1,2,3,4,5,10,11,12,13,14,15];   -- octets 100 % aléatoires de l'UUID
  b bytea;
  c text;
  i int;
  j int;
  exp timestamptz := case when p_jours is null then null else now() + make_interval(days => p_jours) end;
begin
  for i in 1 .. greatest(p_nombre, 0) loop
    loop
      b := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
      c := '';
      for j in 1 .. 12 loop
        c := c || substr(alphabet, 1 + (get_byte(b, pos[j]) % 32), 1);
        if j in (4, 8) then c := c || '-'; end if;
      end loop;
      exit when not exists (select 1 from public.iptv_licences x where x.code = c);
    end loop;
    insert into public.iptv_licences(code, label, expires_at) values (c, coalesce(p_label, ''), exp);
    code := c; label := coalesce(p_label, ''); expire_le := exp;
    return next;
  end loop;
end;
$$;

revoke all on function public.iptv_creer_codes(int, text, int) from public, anon, authenticated;
