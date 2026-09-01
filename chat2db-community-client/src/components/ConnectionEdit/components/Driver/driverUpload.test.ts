import assert from 'node:assert/strict';
import { File as NodeFile } from 'node:buffer';
import { canSaveDriverDraft, resolveDriverSavePayload } from './driverUpload';

async function main() {
  const webFile = new NodeFile(['driver'], 'mysql-test.jar', {
    type: 'application/java-archive',
  }) as unknown as File;
  let uploadedFile: File | null = null;
  const webPayload = await resolveDriverSavePayload(
    {
      dbType: 'MYSQL',
      driverFiles: [webFile],
      jdbcDriverClass: 'com.mysql.cj.jdbc.Driver',
    },
    false,
    async (file) => {
      uploadedFile = file;
      return ['mysql-test.jar'];
    },
  );

  assert.equal(uploadedFile, webFile);
  assert.deepEqual(webPayload, {
    dbType: 'MYSQL',
    jdbcDriver: ['mysql-test.jar'],
    jdbcDriverClass: 'com.mysql.cj.jdbc.Driver',
  });

  const desktopPayload = await resolveDriverSavePayload(
    {
      dbType: 'MYSQL',
      jdbcDriver: ['/tmp/mysql-test.jar'],
      jdbcDriverClass: 'com.mysql.cj.jdbc.Driver',
    },
    true,
    async () => {
      throw new Error('desktop save must not upload through HTTP');
    },
  );
  assert.deepEqual(desktopPayload.jdbcDriver, ['/tmp/mysql-test.jar']);

  assert.equal(canSaveDriverDraft({ dbType: 'MYSQL' }), false);
  assert.equal(
    canSaveDriverDraft({
      dbType: 'MYSQL',
      driverFiles: [webFile],
      jdbcDriverClass: 'com.mysql.cj.jdbc.Driver',
    }),
    true,
  );

  await assert.rejects(
    resolveDriverSavePayload(
      { dbType: 'MYSQL', jdbcDriverClass: 'com.mysql.cj.jdbc.Driver' },
      false,
      async () => [],
    ),
    /driver file is required/i,
  );

  console.log('Custom driver upload lifecycle tests passed');
}

void main();
