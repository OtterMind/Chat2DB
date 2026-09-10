-- DBeaver data transfer
CREATE TABLE public.demo_item (
  id bigint NOT NULL,
  note character varying(64)
);

INSERT INTO public.demo_item (id, note) VALUES
  (1, 'DBeaver; value'),
  (2, E'escaped\\value');
