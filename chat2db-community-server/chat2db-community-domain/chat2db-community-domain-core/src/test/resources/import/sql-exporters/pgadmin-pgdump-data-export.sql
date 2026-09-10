-- PostgreSQL database dump
SET statement_timeout = 0;
SET client_encoding = 'UTF8';

CREATE TABLE public.demo_item (
  id bigint NOT NULL,
  note text
);

COPY public.demo_item (id, note) FROM stdin;
1	pgAdmin value
2	semi;colon
\.

COMMIT;
