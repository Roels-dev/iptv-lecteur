# Gérer les codes d'accès (Supabase > SQL Editor)

Chaque code ne fonctionne que sur **un seul appareil** : il se lie au téléphone ou à la TV
sur lequel il est saisi en premier.

## Créer des codes
```sql
select * from public.iptv_creer_codes(1, 'Kouamé');             -- 1 code, sans limite de durée
select * from public.iptv_creer_codes(3, 'Famille Diallo', 30); -- 3 codes valables 30 jours
```

## Voir qui utilise quoi
```sql
select code, label, device_name, active, expires_at, activated_at, last_seen_at
from public.iptv_licences order by created_at desc;
```
(ou simplement **Table Editor > iptv_licences**)

## Désactiver un code (effet à la prochaine ouverture de l'app)
```sql
update public.iptv_licences set active = false where code = 'XXXX-XXXX-XXXX';
```

## Réactiver un code
```sql
update public.iptv_licences set active = true where code = 'XXXX-XXXX-XXXX';
```

## Transférer un code sur un nouvel appareil (téléphone changé, TV réinitialisée…)
```sql
update public.iptv_licences set device_id = null, device_name = null where code = 'XXXX-XXXX-XXXX';
```

## Prolonger un code de 30 jours
```sql
update public.iptv_licences
set expires_at = greatest(coalesce(expires_at, now()), now()) + interval '30 days'
where code = 'XXXX-XXXX-XXXX';
```
