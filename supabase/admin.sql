-- =====================================================================
-- IPTV Lecteur : fonctions de la PAGE DE GESTION des codes
-- À exécuter UNE FOIS dans Supabase > SQL Editor (après licences.sql)
-- Toutes les actions exigent le mot de passe admin (stocké haché).
-- 5 mauvais mots de passe = blocage 15 minutes.
-- =====================================================================

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.iptv_admin (
  id            int primary key default 1 check (id = 1),
  password_hash text,
  failed        int not null default 0,
  locked_until  timestamptz
);
alter table public.iptv_admin enable row level security;  -- aucune policy : illisible depuis l'app/la page
insert into public.iptv_admin (id) values (1) on conflict do nothing;

-- Définir / changer le mot de passe (UNIQUEMENT depuis le SQL Editor) :
--   select public.iptv_admin_set_password('ton-mot-de-passe');
create or replace function public.iptv_admin_set_password(p_new text)
returns text language plpgsql set search_path = public, extensions as $$
begin
  if length(coalesce(p_new, '')) < 8 then
    raise exception 'Le mot de passe doit faire au moins 8 caractères';
  end if;
  update iptv_admin set password_hash = crypt(p_new, gen_salt('bf')), failed = 0, locked_until = null where id = 1;
  return 'Mot de passe enregistré';
end $$;
revoke all on function public.iptv_admin_set_password(text) from public, anon, authenticated;

-- Vérifie le mot de passe. Renvoie NULL si OK, sinon un message d'erreur.
-- (pas d'exception : le compteur d'échecs doit être enregistré)
create or replace function public.iptv_admin_auth(p_password text)
returns text language plpgsql security definer set search_path = public, extensions as $$
declare a iptv_admin;
begin
  select * into a from iptv_admin where id = 1 for update;
  if a.password_hash is null then
    return 'Aucun mot de passe défini (voir admin.sql)';
  end if;
  if a.locked_until is not null and a.locked_until > now() then
    return 'Trop d''essais. Réessaie après ' || to_char(a.locked_until at time zone 'UTC', 'HH24:MI') || ' (heure d''Abidjan)';
  end if;
  if crypt(coalesce(p_password, ''), a.password_hash) = a.password_hash then
    update iptv_admin set failed = 0, locked_until = null where id = 1;
    return null;
  end if;
  update iptv_admin
     set failed = case when a.failed + 1 >= 5 then 0 else a.failed + 1 end,
         locked_until = case when a.failed + 1 >= 5 then now() + interval '15 minutes' else null end
   where id = 1;
  return 'Mot de passe incorrect';
end $$;
revoke all on function public.iptv_admin_auth(text) from public, anon, authenticated;

-- Liste de tous les codes
create or replace function public.iptv_admin_list(p_password text)
returns json language plpgsql security definer set search_path = public as $$
declare err text := iptv_admin_auth(p_password);
begin
  if err is not null then return json_build_object('ok', false, 'error', err); end if;
  return json_build_object('ok', true, 'codes', coalesce((
    select json_agg(row_to_json(t) order by t.created_at desc) from (
      select code, label, device_name, (device_id is not null) as lie, active, expires_at,
             activated_at, last_seen_at, created_at
      from iptv_licences
    ) t), '[]'::json));
end $$;

-- Créer des codes
create or replace function public.iptv_admin_create(p_password text, p_nombre int, p_label text, p_jours int default null)
returns json language plpgsql security definer set search_path = public as $$
declare err text := iptv_admin_auth(p_password);
begin
  if err is not null then return json_build_object('ok', false, 'error', err); end if;
  if p_nombre < 1 or p_nombre > 50 then return json_build_object('ok', false, 'error', 'Entre 1 et 50 codes à la fois'); end if;
  return json_build_object('ok', true, 'codes',
    (select json_agg(c.code) from iptv_creer_codes(p_nombre, left(coalesce(p_label, ''), 80), p_jours) c));
end $$;

-- Activer / désactiver
create or replace function public.iptv_admin_set_active(p_password text, p_code text, p_active boolean)
returns json language plpgsql security definer set search_path = public as $$
declare err text := iptv_admin_auth(p_password);
begin
  if err is not null then return json_build_object('ok', false, 'error', err); end if;
  update iptv_licences set active = p_active where code = p_code;
  return json_build_object('ok', found);
end $$;

-- Libérer le code de son appareil (transfert)
create or replace function public.iptv_admin_reset_device(p_password text, p_code text)
returns json language plpgsql security definer set search_path = public as $$
declare err text := iptv_admin_auth(p_password);
begin
  if err is not null then return json_build_object('ok', false, 'error', err); end if;
  update iptv_licences set device_id = null, device_name = null where code = p_code;
  return json_build_object('ok', found);
end $$;

-- Prolonger (p_jours) ou rendre illimité (p_jours = null)
create or replace function public.iptv_admin_extend(p_password text, p_code text, p_jours int)
returns json language plpgsql security definer set search_path = public as $$
declare err text := iptv_admin_auth(p_password);
begin
  if err is not null then return json_build_object('ok', false, 'error', err); end if;
  update iptv_licences
     set expires_at = case when p_jours is null then null
                           else greatest(coalesce(expires_at, now()), now()) + make_interval(days => p_jours) end
   where code = p_code;
  return json_build_object('ok', found);
end $$;

-- Renommer (changer le nom / poste associé)
create or replace function public.iptv_admin_rename(p_password text, p_code text, p_label text)
returns json language plpgsql security definer set search_path = public as $$
declare err text := iptv_admin_auth(p_password);
begin
  if err is not null then return json_build_object('ok', false, 'error', err); end if;
  update iptv_licences set label = left(coalesce(p_label, ''), 80) where code = p_code;
  return json_build_object('ok', found);
end $$;

-- Supprimer définitivement
create or replace function public.iptv_admin_delete(p_password text, p_code text)
returns json language plpgsql security definer set search_path = public as $$
declare err text := iptv_admin_auth(p_password);
begin
  if err is not null then return json_build_object('ok', false, 'error', err); end if;
  delete from iptv_licences where code = p_code;
  return json_build_object('ok', found);
end $$;

-- Seules les fonctions protégées par mot de passe sont appelables par la page
revoke all on function public.iptv_admin_list(text) from public;
revoke all on function public.iptv_admin_create(text, int, text, int) from public;
revoke all on function public.iptv_admin_set_active(text, text, boolean) from public;
revoke all on function public.iptv_admin_reset_device(text, text) from public;
revoke all on function public.iptv_admin_extend(text, text, int) from public;
revoke all on function public.iptv_admin_rename(text, text, text) from public;
revoke all on function public.iptv_admin_delete(text, text) from public;
grant execute on function public.iptv_admin_list(text) to anon;
grant execute on function public.iptv_admin_create(text, int, text, int) to anon;
grant execute on function public.iptv_admin_set_active(text, text, boolean) to anon;
grant execute on function public.iptv_admin_reset_device(text, text) to anon;
grant execute on function public.iptv_admin_extend(text, text, int) to anon;
grant execute on function public.iptv_admin_rename(text, text, text) to anon;
grant execute on function public.iptv_admin_delete(text, text) to anon;
