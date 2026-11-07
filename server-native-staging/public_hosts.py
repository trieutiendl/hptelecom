"""Explicit domain allowlist. Missing configuration keeps LAN-only behavior."""
import json,re

def load(path):
 if not path.exists():return set()
 values=json.loads(path.read_text(encoding='utf-8-sig'))
 if not isinstance(values,list):raise ValueError('public-hosts.json must contain a list of domains.')
 result=set()
 for value in values:
  if not isinstance(value,str):raise ValueError('Invalid public hostname.')
  name=value.strip().lower()
  if len(name)>253 or '.' not in name or not all(re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?',part) for part in name.split('.')):
   raise ValueError('Use an exact domain without wildcard, URL, port or path.')
  result.add(name)
 return result
