import { DatabaseTypeCode } from '@/constants/common';
import { composeResultQuery } from './queryComposer';

function assertEqual(actual: string, expected: string, message: string) {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

assertEqual(
  composeResultQuery({
    databaseType: DatabaseTypeCode.MYSQL,
    filterValue: 'status = 1',
    orderByValue: 'created_at desc',
    originalSql: 'SELECT * FROM users',
  }),
  'SELECT * FROM users WHERE status = 1 ORDER BY created_at desc',
  'relational databases keep WHERE and ORDER BY composition',
);

assertEqual(
  composeResultQuery({
    databaseType: DatabaseTypeCode.MONGODB,
    originalSql: 'db.users.find()',
  }),
  'db.users.find()',
  'MongoDB query stays unchanged without filter or sort values',
);

assertEqual(
  composeResultQuery({
    databaseType: DatabaseTypeCode.MONGODB,
    filterValue: '{ age: { $gt: 18 } }',
    originalSql: 'db.users.find()',
  }),
  'db.users.find({ age: { $gt: 18 } })',
  'MongoDB filter is placed inside find',
);

assertEqual(
  composeResultQuery({
    databaseType: DatabaseTypeCode.MONGODB,
    filterValue: 'status: "ACTIVE"',
    originalSql: 'db.users.find()',
  }),
  'db.users.find({ status: "ACTIVE" })',
  'MongoDB filter shorthand is wrapped in a document',
);

assertEqual(
  composeResultQuery({
    databaseType: DatabaseTypeCode.MONGODB,
    filterValue: '{ active: true }',
    originalSql: 'db.users.find({ archived: false }, { name: 1, note: "a,b" })',
  }),
  'db.users.find({ active: true }, { name: 1, note: "a,b" })',
  'MongoDB filter replacement preserves the projection argument',
);

assertEqual(
  composeResultQuery({
    databaseType: DatabaseTypeCode.MONGODB,
    orderByValue: 'profile.age desc, name asc',
    originalSql: 'db.users.find()',
  }),
  'db.users.find().sort({ "profile.age": -1, "name": 1 })',
  'MongoDB converts grid order text into a sort document',
);

assertEqual(
  composeResultQuery({
    databaseType: DatabaseTypeCode.MONGODB,
    orderByValue: '{ createdAt: -1 }',
    originalSql: 'db.users.find();',
  }),
  'db.users.find().sort({ createdAt: -1 });',
  'MongoDB native sort is inserted before a trailing semicolon',
);

assertEqual(
  composeResultQuery({
    databaseType: DatabaseTypeCode.MONGODB,
    orderByValue: '{ name: 1 }',
    originalSql: 'db.users.find({ active: true }).sort({ createdAt: -1 })',
  }),
  'db.users.find({ active: true }).sort({ name: 1 })',
  'MongoDB sort editor replaces an existing sort call',
);

assertEqual(
  composeResultQuery({
    databaseType: DatabaseTypeCode.MONGODB,
    filterValue: '{ active: true, ownerId: ObjectId("abc") }',
    orderByValue: 'createdAt desc',
    originalSql: 'db.users.find().limit(20);',
  }),
  'db.users.find({ active: true, ownerId: ObjectId("abc") }).limit(20).sort({ "createdAt": -1 });',
  'MongoDB filter and sort preserve chained cursor operations',
);

console.log('ScreeningResult queryComposer tests passed');
