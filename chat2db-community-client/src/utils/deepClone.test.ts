async function testDeepClonePreservesFunctionReferences() {
  const { deepClone } = await import('./deepClone');
  let total = 0;
  const callback = (value: number) => {
    total += value;
    return total;
  };
  const source = {
    callback,
    nested: { callback },
    callbacks: [callback],
  };

  const cloned = deepClone(source);

  if (cloned === source || cloned.nested === source.nested || cloned.callbacks === source.callbacks) {
    throw new Error('deepClone did not clone the surrounding data structure');
  }
  if (
    cloned.callback !== callback ||
    cloned.nested.callback !== callback ||
    cloned.callbacks[0] !== callback
  ) {
    throw new Error('deepClone must preserve function references');
  }
  if (cloned.callback(2) !== 2 || total !== 2) {
    throw new Error('the preserved callback lost its closure');
  }
}

testDeepClonePreservesFunctionReferences().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
