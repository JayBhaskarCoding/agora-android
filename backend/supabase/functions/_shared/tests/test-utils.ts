/**
 * Shared helpers for the notification-pipeline tests.
 *
 * Run with:
 *   deno test --allow-net --allow-env backend/supabase/functions/_shared/tests/
 */

export interface MockRequest {
  method: string;
  url: string;
  headers: Headers;
  body: string;
}

export interface MockServer {
  url: string;
  requests: MockRequest[];
  close(): Promise<void>;
}

/** Starts a real HTTP server on an ephemeral port (no external network). */
export async function startMockServer(
  handler: (request: MockRequest) => Response | Promise<Response>,
): Promise<MockServer> {
  const requests: MockRequest[] = [];
  const abort = new AbortController();
  let port = 0;

  const server = Deno.serve(
    {
      port: 0,
      hostname: "127.0.0.1",
      signal: abort.signal,
      onListen: (address) => {
        port = address.port;
      },
    },
    async (request: Request) => {
      const body = await request.text();
      const record: MockRequest = {
        method: request.method,
        url: request.url,
        headers: request.headers,
        body,
      };
      requests.push(record);
      return await handler(record);
    },
  );

  while (port === 0) await new Promise((resolve) => setTimeout(resolve, 5));

  return {
    url: `http://127.0.0.1:${port}`,
    requests,
    async close() {
      abort.abort();
      try {
        await server.finished;
      } catch {
        /* aborted */
      }
    },
  };
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/* -------------------------------------------------------------------------- */
/* RSA test key material                                                      */
/* -------------------------------------------------------------------------- */

export interface TestKeyPair {
  privateKeyPem: string;
  publicKey: CryptoKey;
  privateKey: CryptoKey;
}

let keypairPromise: Promise<TestKeyPair> | null = null;

/** Generates (once per process) a throwaway RSA keypair for signing tests. */
export function testKeyPair(): Promise<TestKeyPair> {
  if (!keypairPromise) {
    keypairPromise = (async () => {
      const pair = (await crypto.subtle.generateKey(
        {
          name: "RSASSA-PKCS1-v1_5",
          modulusLength: 2048,
          publicExponent: new Uint8Array([1, 0, 1]),
          hash: "SHA-256",
        },
        true,
        ["sign", "verify"],
      )) as CryptoKeyPair;
      return {
        privateKeyPem: toPem(
          await crypto.subtle.exportKey("pkcs8", pair.privateKey),
        ),
        publicKey: pair.publicKey,
        privateKey: pair.privateKey,
      };
    })();
  }
  return keypairPromise;
}

export function toPem(
  input: ArrayBuffer | Uint8Array,
  label = "PRIVATE KEY",
): string {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  const wrapped = (btoa(binary).match(/.{1,64}/g) ?? []).join("\n");
  return `-----BEGIN ${label}-----\n${wrapped}\n-----END ${label}-----\n`;
}

export const TEST_PROJECT_ID = "agora-application";
export const TEST_CLIENT_EMAIL =
  "firebase-adminsdk-fake@agora-application.iam.gserviceaccount.com";
export const TEST_PRIVATE_KEY_ID = "0123456789abcdef0123456789abcdef01234567";

export interface ServiceAccountFixtureOptions {
  privateKey?: string;
  projectId?: string;
  clientEmail?: string;
  privateKeyId?: string;
  extra?: Record<string, unknown>;
  omit?: string[];
}

/** Builds a service-account JSON object shaped like Firebase's download. */
export function serviceAccountObject(
  options: ServiceAccountFixtureOptions = {},
): Record<string, unknown> {
  const record: Record<string, unknown> = {
    type: "service_account",
    project_id: options.projectId ?? TEST_PROJECT_ID,
    private_key_id: options.privateKeyId ?? TEST_PRIVATE_KEY_ID,
    private_key: options.privateKey ?? "",
    client_email: options.clientEmail ?? TEST_CLIENT_EMAIL,
    client_id: "123456789012345678901",
    auth_uri: "https://accounts.google.com/o/oauth2/auth",
    token_uri: "https://oauth2.googleapis.com/token",
    ...(options.extra ?? {}),
  };
  for (const key of options.omit ?? []) delete record[key];
  return record;
}

/* -------------------------------------------------------------------------- */
/* Fake PostgREST client                                                      */
/* -------------------------------------------------------------------------- */

import type {
  PostgrestBuilderLike,
  PostgrestResult,
  SupabaseLikeClient,
} from "../token-cleanup.ts";

export interface FakeCall {
  table: string;
  op: "select" | "update" | "delete";
  columns?: string;
  values?: Record<string, unknown>;
  filters: Array<[string, unknown]>;
}

export type FakeHandler = (
  call: FakeCall,
) => PostgrestResult | Error | Promise<PostgrestResult | Error>;

class FakeBuilder implements PostgrestBuilderLike {
  readonly filters: Array<[string, unknown]> = [];

  constructor(
    private readonly client: FakeClient,
    private readonly call: FakeCall,
  ) {}

  eq(column: string, value: unknown): PostgrestBuilderLike {
    this.filters.push([column, value]);
    this.call.filters.push([column, value]);
    return this;
  }

  select(columns: string): PostgrestBuilderLike {
    this.call.columns = columns;
    return this;
  }

  then<TResult1 = PostgrestResult, TResult2 = never>(
    onfulfilled?:
      | ((value: PostgrestResult) => TResult1 | PromiseLike<TResult1>)
      | null,
    onrejected?: ((reason: unknown) => TResult2 | PromiseLike<TResult2>) | null,
  ): PromiseLike<TResult1 | TResult2> {
    return this.client.execute(this.call).then(onfulfilled, onrejected);
  }
}

/** Minimal in-memory stand-in for the Supabase client. */
export class FakeClient implements SupabaseLikeClient {
  readonly calls: FakeCall[] = [];
  handler: FakeHandler = () => ({ data: [], error: null });

  from(table: string) {
    const start = (op: FakeCall["op"], extra: Partial<FakeCall> = {}) => {
      const call: FakeCall = { table, op, filters: [], ...extra };
      this.calls.push(call);
      return new FakeBuilder(this, call);
    };

    return {
      select: (columns: string) => start("select", { columns }),
      update: (values: Record<string, unknown>) => start("update", { values }),
      delete: () => start("delete"),
    };
  }

  async execute(call: FakeCall): Promise<PostgrestResult> {
    const result = await this.handler(call);
    if (result instanceof Error) throw result;
    return result;
  }

  callsFor(table: string, op?: FakeCall["op"]): FakeCall[] {
    return this.calls.filter((call) =>
      call.table === table && (op === undefined || call.op === op)
    );
  }
}
