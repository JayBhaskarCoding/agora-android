/**
 * Tiny zero-dependency assertion helpers for the notification tests.
 *
 * The Edge Functions themselves have no npm/jsr dependencies, and keeping the
 * tests that way means `deno test` runs anywhere (including sandboxes without
 * registry access). The API mirrors `jsr:@std/assert`, so swapping in the
 * standard module later is a one-line import change per file.
 */

function deepEqual(a: unknown, b: unknown, seen = new Set<unknown>()): boolean {
  if (Object.is(a, b)) return true;
  if (
    a === null || b === null || typeof a !== "object" || typeof b !== "object"
  ) return false;
  if (seen.has(a)) return false; // cycle guard
  seen.add(a);

  const aArray = Array.isArray(a);
  if (aArray !== Array.isArray(b)) return false;

  if (aArray) {
    const left = a as unknown[];
    const right = b as unknown[];
    return left.length === right.length &&
      left.every((value, index) => deepEqual(value, right[index], seen));
  }

  const left = a as Record<string, unknown>;
  const right = b as Record<string, unknown>;
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  if (leftKeys.length !== rightKeys.length) return false;
  return leftKeys.every(
    (key) =>
      Object.prototype.hasOwnProperty.call(right, key) &&
      deepEqual(left[key], right[key], seen),
  );
}

function inspect(value: unknown): string {
  try {
    const text = JSON.stringify(value);
    return text ?? String(value);
  } catch {
    return String(value);
  }
}

export class AssertionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AssertionError";
  }
}

export function assert(
  condition: unknown,
  message = "assertion failed",
): asserts condition {
  if (!condition) throw new AssertionError(message);
}

/** Passes when `actual` is structurally equal to `expected`. */
export function assertEquals(
  actual: unknown,
  expected: unknown,
  message = "",
): void {
  if (!deepEqual(actual, expected)) {
    throw new AssertionError(
      `Values are not equal${message ? ` (${message})` : ""}\n  actual  : ${
        inspect(actual)
      }\n  expected: ${inspect(expected)}`,
    );
  }
}

export function assertNotEquals(
  actual: unknown,
  expected: unknown,
  message = "",
): void {
  if (deepEqual(actual, expected)) {
    throw new AssertionError(
      `Values are equal but should not be${message ? ` (${message})` : ""}: ${
        inspect(actual)
      }`,
    );
  }
}

export function assertStringIncludes(
  haystack: string,
  needle: string,
  message = "",
): void {
  if (!String(haystack).includes(needle)) {
    throw new AssertionError(
      `Expected text to contain ${inspect(needle)}${
        message ? ` (${message})` : ""
      }\n  received: ${inspect(haystack)}`,
    );
  }
}

export function assertStringExcludes(
  haystack: string,
  needle: string,
  message = "",
): void {
  if (String(haystack).includes(needle)) {
    throw new AssertionError(
      `Expected text NOT to contain ${inspect(needle)}${
        message ? ` (${message})` : ""
      }`,
    );
  }
}

/**
 * Asserts that `fn` throws, optionally matching an error class and message
 * substring. Returns the thrown error for further assertions.
 */
export function assertThrows<E extends Error = Error>(
  fn: () => unknown,
  errorClass?: abstract new (...args: never[]) => E,
  messageIncludes?: string,
  message = "",
): E {
  let thrown: unknown;
  let didThrow = false;
  try {
    fn();
  } catch (error) {
    didThrow = true;
    thrown = error;
  }
  if (!didThrow) {
    throw new AssertionError(
      `Expected function to throw${message ? ` (${message})` : ""}`,
    );
  }
  if (errorClass && !(thrown instanceof errorClass)) {
    throw new AssertionError(
      `Expected error to be an instance of ${errorClass.name}, got ${
        (thrown as Error)?.name
      }: ${(thrown as Error)?.message}`,
    );
  }
  if (
    messageIncludes &&
    !String((thrown as Error)?.message ?? "").includes(messageIncludes)
  ) {
    throw new AssertionError(
      `Expected error message to contain ${inspect(messageIncludes)}, got: ${
        inspect((thrown as Error)?.message)
      }`,
    );
  }
  return thrown as E;
}

/** Promise-returning variant of {@link assertThrows}. */
export async function assertRejects<E extends Error = Error>(
  fn: () => Promise<unknown>,
  errorClass?: abstract new (...args: never[]) => E,
  messageIncludes?: string,
  message = "",
): Promise<E> {
  let thrown: unknown;
  let didThrow = false;
  try {
    await fn();
  } catch (error) {
    didThrow = true;
    thrown = error;
  }
  if (!didThrow) {
    throw new AssertionError(
      `Expected promise to reject${message ? ` (${message})` : ""}`,
    );
  }
  if (errorClass && !(thrown instanceof errorClass)) {
    throw new AssertionError(
      `Expected rejection to be an instance of ${errorClass.name}, got ${
        (thrown as Error)?.name
      }: ${(thrown as Error)?.message}`,
    );
  }
  if (
    messageIncludes &&
    !String((thrown as Error)?.message ?? "").includes(messageIncludes)
  ) {
    throw new AssertionError(
      `Expected error message to contain ${inspect(messageIncludes)}, got: ${
        inspect((thrown as Error)?.message)
      }`,
    );
  }
  return thrown as E;
}
