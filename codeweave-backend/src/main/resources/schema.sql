create table if not exists cw_model_config (
  id varchar(64) primary key,
  owner_id varchar(128) not null,
  name varchar(160) not null,
  endpoint varchar(500) not null,
  api_key_ciphertext text not null default '',
  default_model boolean not null default false,
  updated_at timestamptz not null
);
create index if not exists idx_cw_model_config_owner on cw_model_config(owner_id);

create table if not exists cw_user_profile (
  owner_id varchar(128) primary key,
  avatar_object text not null,
  updated_at timestamptz not null default now()
);

create table if not exists cw_execution_environment (
  owner_id varchar(128) primary key,
  name varchar(160) not null,
  endpoint varchar(500) not null,
  cwd text not null default '',
  updated_at timestamptz not null default now()
);
