REM INSERTING into APP.DEMO_ITEM
SET DEFINE OFF;
Insert into APP.DEMO_ITEM (ID,NOTE) values (1,'SQL Developer; value');
Insert into APP.DEMO_ITEM (ID,NOTE) values (2,q'[alternative; quote]');
COMMIT;
